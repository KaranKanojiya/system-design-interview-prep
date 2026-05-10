# Technologies Reference: Distributed Task Scheduler

## Table of Contents

1. [Overview](#1-overview)
2. [Apache ZooKeeper / etcd — Distributed Coordination](#2-apache-zookeeper--etcd--distributed-coordination)
3. [Apache Kafka — Task Event Streaming](#3-apache-kafka--task-event-streaming)
4. [Redis — Task Queue and Distributed Locks](#4-redis--task-queue-and-distributed-locks)
5. [PostgreSQL — Task Metadata and Execution History](#5-postgresql--task-metadata-and-execution-history)
6. [Apache Airflow — DAG-Based Workflow Orchestration](#6-apache-airflow--dag-based-workflow-orchestration)
7. [Celery — Distributed Task Queue](#7-celery--distributed-task-queue)
8. [Temporal.io — Durable Execution](#8-temporalio--durable-execution)
9. [gRPC — Inter-Service Communication](#9-grpc--inter-service-communication)
10. [Prometheus + Grafana — Monitoring and Alerting](#10-prometheus--grafana--monitoring-and-alerting)
11. [Priority Queue Data Structures](#11-priority-queue-data-structures)
12. [Graph Algorithms — DAG Resolution](#12-graph-algorithms--dag-resolution)
13. [Cron Expression Parsing Libraries](#13-cron-expression-parsing-libraries)
14. [Simulation-to-Production Mapping](#14-simulation-to-production-mapping)
15. [Technology Selection Matrix](#15-technology-selection-matrix)

---

## 1. Overview

This document catalogs the production technologies that underpin a distributed task
scheduler at scale. Each section explains what the technology does, why it matters
for task scheduling, how it integrates with surrounding components, and what
operational considerations to expect.

The simulation in this project uses in-memory Java structures (ConcurrentHashMap,
PriorityQueue, etc.) that map directly to these production technologies. Section 14
provides an explicit mapping from every simulation class to its production
counterpart.

### Architecture at a Glance

```
                          +------------------+
                          |  Scheduler API   |
                          | (gRPC / REST)    |
                          +--------+---------+
                                   |
              +--------------------+--------------------+
              |                    |                     |
     +--------v--------+  +-------v--------+  +--------v--------+
     |   PostgreSQL     |  |   Redis        |  |   Kafka         |
     | (task metadata,  |  | (priority      |  | (event stream,  |
     |  execution log,  |  |  queue, locks, |  |  at-least-once  |
     |  ACID state)     |  |  caching)      |  |  delivery)      |
     +---------+--------+  +-------+--------+  +--------+--------+
               |                   |                     |
     +---------v-------------------v---------------------v--------+
     |                  Scheduler Engine                           |
     |  (leader election, DAG resolution, cron firing,            |
     |   retry management, task dispatch)                         |
     +-----+------------------------------------------+-----------+
           |                                          |
     +-----v------+                           +------v------+
     | ZooKeeper  |                           |  Workers    |
     | / etcd     |                           |  (gRPC      |
     | (leader    |                           |   heartbeat,|
     |  election, |                           |   execution)|
     |  config)   |                           +------+------+
     +------------+                                  |
                                              +------v------+
                                              | Prometheus  |
                                              | + Grafana   |
                                              +-------------+
```

### Technology Stack Summary

| Layer               | Technology         | Role                                          |
|---------------------|--------------------|-----------------------------------------------|
| Coordination        | ZooKeeper / etcd   | Leader election, distributed locks, config    |
| Messaging           | Kafka              | Task events, at-least-once delivery           |
| Queue + Cache       | Redis              | Priority queue, distributed locks, caching    |
| Persistence         | PostgreSQL         | Task metadata, execution history, ACID        |
| Orchestration Ref   | Airflow            | DAG workflow reference architecture            |
| Task Queue Ref      | Celery             | Distributed task queue patterns               |
| Durable Execution   | Temporal.io        | Workflow orchestration, durable state          |
| Communication       | gRPC               | Worker registration, heartbeats, RPC          |
| Monitoring          | Prometheus/Grafana | Metrics collection, dashboards, alerting      |

---

## 2. Apache ZooKeeper / etcd -- Distributed Coordination

### 2.1 What It Is

ZooKeeper is a centralized service for distributed synchronization, configuration
management, and group membership. etcd is a distributed key-value store that serves
similar coordination purposes and is the backbone of Kubernetes.

### 2.2 Role in the Task Scheduler

The distributed task scheduler requires exactly one active leader at any time. The
leader is responsible for:
- Dispatching tasks from the priority queue to workers
- Resolving DAG dependencies and determining ready tasks
- Firing cron schedules at the correct time
- Initiating failover when workers go silent

ZooKeeper/etcd provides the coordination primitives that make this possible.

### 2.3 Leader Election with ZooKeeper

```
ZooKeeper Ensemble (3 or 5 nodes)
       |
       +--- /scheduler/leader         (ephemeral node, current leader)
       +--- /scheduler/nodes/         (children = registered scheduler nodes)
       |       +--- node-001          (ephemeral sequential)
       |       +--- node-002          (ephemeral sequential)
       |       +--- node-003          (ephemeral sequential)
       +--- /scheduler/config/        (persistent, scheduler configuration)
       +--- /scheduler/locks/         (distributed lock recipes)
```

**Election flow (ZooKeeper recipe):**

1. Each scheduler node creates an ephemeral sequential znode under `/scheduler/nodes/`
2. Nodes call `getChildren()` on `/scheduler/nodes/` and sort by sequence number
3. The node with the lowest sequence number becomes the leader
4. All other nodes set a watch on the znode immediately preceding theirs
5. When the leader crashes, its ephemeral node disappears
6. The next node in sequence detects the watch event and becomes the new leader

```java
// Production ZooKeeper leader election (conceptual)
public class ZooKeeperLeaderElection {

    private final CuratorFramework client;
    private final LeaderLatch leaderLatch;

    public ZooKeeperLeaderElection(String connectString, String schedulerId) {
        client = CuratorFrameworkFactory.newClient(
            connectString,
            new ExponentialBackoffRetry(1000, 3)
        );
        leaderLatch = new LeaderLatch(client, "/scheduler/leader", schedulerId);
    }

    // 1. Start participating in leader election
    public void start() throws Exception {
        client.start();
        leaderLatch.start();
    }

    // 2. Check if this node is the current leader
    public boolean isLeader() {
        return leaderLatch.hasLeadership();
    }

    // 3. Block until this node becomes leader
    public void awaitLeadership() throws Exception {
        leaderLatch.await();
    }

    // 4. Relinquish leadership and shut down
    public void close() throws Exception {
        leaderLatch.close();
        client.close();
    }
}
```

### 2.4 Simulation Mapping

The simulation uses `LeaderElectionService` with the Bully algorithm:

| Simulation (LeaderElectionService)       | Production (ZooKeeper)                    |
|------------------------------------------|-------------------------------------------|
| `registerNode(SchedulerNode)`            | Create ephemeral sequential znode         |
| `electLeader()` (Bully: highest wins)    | Curator LeaderLatch (lowest sequence wins)|
| `handleNodeFailure(nodeId)`              | Ephemeral node auto-deleted on disconnect |
| `simulateHeartbeats()`                   | ZooKeeper session keepalive (tickTime)    |
| `isLeader(nodeId)`                       | `leaderLatch.hasLeadership()`             |
| `ALIVE_TIMEOUT = 30s`                    | ZooKeeper `sessionTimeout` configuration  |

The Bully algorithm in the simulation selects the node with the highest priority.
In production, ZooKeeper's LeaderLatch uses the lowest-sequence-number-wins approach,
which is more robust because it avoids O(n) election messages and leverages ZooKeeper's
built-in ordering guarantees.

### 2.5 etcd Alternative

etcd provides equivalent functionality via its lease and election APIs:

```
# etcd leader election via lease
etcdctl lease grant 30          # 30-second TTL (like heartbeat timeout)
etcdctl put /scheduler/leader "node-001" --lease=<leaseId>
etcdctl elect /scheduler/leader "node-001"
```

**Key differences from ZooKeeper:**

| Feature              | ZooKeeper                    | etcd                          |
|----------------------|------------------------------|-------------------------------|
| Consensus            | ZAB (custom)                 | Raft                          |
| Data model           | Hierarchical znodes          | Flat key-value                |
| Watch semantics      | One-time watches             | Streaming watches             |
| Client libraries     | Curator (Java), Kazoo (Py)   | jetcd (Java), etcd3 (Py)     |
| Kubernetes native    | No                           | Yes (built-in)                |
| Minimum cluster size | 3 nodes                      | 3 nodes                       |

### 2.6 Configuration Management

ZooKeeper/etcd also stores dynamic scheduler configuration:

```
/scheduler/config/
    +--- max_workers_per_node          = "10"
    +--- heartbeat_interval_seconds    = "5"
    +--- heartbeat_timeout_seconds     = "30"
    +--- cron_scan_interval_seconds    = "60"
    +--- max_retry_attempts            = "5"
    +--- default_task_timeout_millis   = "60000"
    +--- failover_check_interval       = "10"
```

This allows runtime configuration changes without restarting scheduler nodes. Watches
notify all nodes of configuration updates within milliseconds.

### 2.7 Distributed Locks

ZooKeeper's `InterProcessMutex` (via Curator) provides distributed mutex locks:

```java
// Distributed lock for exactly-once task assignment
InterProcessMutex lock = new InterProcessMutex(client, "/scheduler/locks/task-" + taskId);

if (lock.acquire(5, TimeUnit.SECONDS)) {
    try {
        // Only one scheduler node can assign this task
        assignTaskToWorker(taskId, workerId);
    } finally {
        lock.release();
    }
}
```

This prevents two scheduler nodes from assigning the same task to different workers
during a split-brain scenario.

### 2.8 Operational Considerations

| Concern                | Recommendation                                              |
|------------------------|-------------------------------------------------------------|
| Cluster size           | 3 nodes for dev, 5 nodes for production (tolerates 2 failures) |
| Session timeout        | 10-30 seconds (balance between fast failover and false positives) |
| Transaction log disk   | Dedicated SSD for write-ahead log (WAL)                     |
| Snapshot frequency     | Every 100,000 transactions (default)                        |
| Monitoring             | Watch `/scheduler/leader` for leadership changes            |
| Network partitions     | ZooKeeper stops serving reads during leader election         |
| Client reconnection    | Curator handles reconnection with exponential backoff        |

---

## 3. Apache Kafka -- Task Event Streaming

### 3.1 What It Is

Kafka is a distributed event streaming platform that provides durable, ordered,
fault-tolerant message delivery. It uses a log-based storage model where messages
are appended to partitions and retained for a configurable duration.

### 3.2 Role in the Task Scheduler

Kafka serves as the event bus for all task lifecycle events. Every state transition
(PENDING -> QUEUED -> ASSIGNED -> RUNNING -> COMPLETED/FAILED) is published as a
Kafka event, providing:

1. **Audit trail** -- complete history of every task state change
2. **Decoupling** -- scheduler and workers communicate asynchronously
3. **At-least-once delivery** -- no task events are lost
4. **Fan-out** -- monitoring, analytics, and alerting consume the same events

### 3.3 Topic Design

```
Topic: scheduler.task.commands
  Partitions: 16 (partitioned by taskId for ordering)
  Retention: 7 days
  Purpose: Task submission, cancellation, priority changes

Topic: scheduler.task.events
  Partitions: 16 (partitioned by taskId for ordering)
  Retention: 30 days
  Purpose: State transitions (PENDING, QUEUED, ASSIGNED, RUNNING, COMPLETED, FAILED)

Topic: scheduler.worker.heartbeats
  Partitions: 8 (partitioned by workerId)
  Retention: 1 hour (short TTL, only recent heartbeats matter)
  Purpose: Worker liveness signals

Topic: scheduler.task.deadletter
  Partitions: 4
  Retention: 90 days
  Purpose: Tasks that exhausted all retries

Topic: scheduler.cron.triggers
  Partitions: 4
  Retention: 7 days
  Purpose: Cron schedule fire events
```

### 3.4 Event Schema

```json
{
  "eventId": "evt-a1b2c3",
  "eventType": "TASK_STATE_CHANGED",
  "taskId": "task-xyz-789",
  "previousStatus": "ASSIGNED",
  "newStatus": "RUNNING",
  "workerId": "worker-003",
  "timestamp": "2026-05-09T14:30:00Z",
  "metadata": {
    "attemptNumber": 1,
    "schedulerNodeId": "node-001",
    "dagGroupId": "etl-pipeline-daily"
  }
}
```

### 3.5 Producer Configuration

```java
// Task event producer configuration
Properties props = new Properties();
props.put("bootstrap.servers", "kafka-01:9092,kafka-02:9092,kafka-03:9092");
props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
props.put("value.serializer", "io.confluent.kafka.serializers.KafkaAvroSerializer");
props.put("acks", "all");                      // Wait for all ISR replicas
props.put("enable.idempotence", "true");       // Exactly-once producer semantics
props.put("retries", Integer.MAX_VALUE);       // Retry indefinitely on transient errors
props.put("max.in.flight.requests.per.connection", "5"); // Safe with idempotence
props.put("compression.type", "snappy");        // Compression for throughput
props.put("linger.ms", "10");                   // Small batching window

KafkaProducer<String, TaskEvent> producer = new KafkaProducer<>(props);
```

Key settings explained:
- **acks=all** -- the leader waits for all in-sync replicas to acknowledge, preventing
  data loss if the leader crashes
- **enable.idempotence=true** -- the producer assigns sequence numbers to messages,
  and the broker deduplicates retries, guaranteeing exactly-once publishing
- **Partition key = taskId** -- all events for a given task land on the same partition,
  preserving per-task ordering

### 3.6 Consumer Groups

```
Consumer Group: scheduler-engine
  Consumes: scheduler.task.commands
  Purpose: The leader scheduler node processes task submissions

Consumer Group: task-executors
  Consumes: scheduler.task.events (filter: ASSIGNED)
  Purpose: Workers pick up assigned tasks

Consumer Group: monitoring-pipeline
  Consumes: scheduler.task.events
  Purpose: Prometheus exporter, alerting rules

Consumer Group: audit-writer
  Consumes: scheduler.task.events
  Purpose: Writes to PostgreSQL execution_history table

Consumer Group: dead-letter-processor
  Consumes: scheduler.task.deadletter
  Purpose: Alerting, manual review dashboard
```

### 3.7 At-Least-Once Delivery and Idempotency

Kafka guarantees at-least-once delivery by default. This means a task event might
be delivered more than once if a consumer crashes after processing but before
committing its offset. The scheduler handles this with idempotent consumers:

```java
// Idempotent task state transition
public void handleTaskEvent(TaskEvent event) {
    // 1. Read current state from PostgreSQL
    Task task = taskRepository.findById(event.getTaskId());

    // 2. Check if this transition is valid (prevents duplicate processing)
    if (!isValidTransition(task.getStatus(), event.getNewStatus())) {
        log.warn("Ignoring duplicate/invalid transition: {} -> {} for task {}",
                 task.getStatus(), event.getNewStatus(), event.getTaskId());
        return;
    }

    // 3. Apply state transition within a database transaction
    taskRepository.updateStatus(event.getTaskId(), event.getNewStatus());

    // 4. Commit Kafka offset only after DB commit succeeds
    consumer.commitSync();
}
```

### 3.8 Partition Strategy for Priority

Tasks are partitioned by `taskId` for ordering, but priority handling requires
additional design. Two approaches:

**Approach A: Priority-based topics**
```
scheduler.task.commands.critical   (consumer polls this first)
scheduler.task.commands.high
scheduler.task.commands.medium
scheduler.task.commands.low
```

**Approach B: Single topic with consumer-side priority sorting**
```java
// Consumer polls batch, sorts by priority, processes highest first
ConsumerRecords<String, TaskEvent> records = consumer.poll(Duration.ofMillis(100));
List<TaskEvent> sorted = StreamSupport.stream(records.spliterator(), false)
    .map(ConsumerRecord::value)
    .sorted(Comparator.comparingInt(e -> e.getPriority().getValue()).reversed())
    .collect(Collectors.toList());
```

The simulation uses Approach B via `TaskQueue` with a `PriorityQueue` that sorts
by priority descending, then by `createdAt` ascending.

### 3.9 Kafka Connect for PostgreSQL Sync

```json
{
  "name": "task-events-sink",
  "config": {
    "connector.class": "io.confluent.connect.jdbc.JdbcSinkConnector",
    "topics": "scheduler.task.events",
    "connection.url": "jdbc:postgresql://pg-primary:5432/scheduler",
    "insert.mode": "upsert",
    "pk.mode": "record_value",
    "pk.fields": "taskId,eventId",
    "auto.create": "false"
  }
}
```

### 3.10 Operational Considerations

| Concern                | Recommendation                                              |
|------------------------|-------------------------------------------------------------|
| Cluster size           | 3 brokers minimum, replication factor 3                     |
| Partition count        | 16 per topic (supports up to 16 concurrent consumers)       |
| Retention              | 7 days for commands, 30 days for events, 90 days for DLQ    |
| Consumer lag alerting  | Alert when lag exceeds 1000 messages for > 5 minutes        |
| Offset management      | Commit offsets after successful DB write (at-least-once)    |
| Schema registry        | Confluent Schema Registry with Avro for event schemas       |
| Monitoring             | Kafka JMX metrics exported to Prometheus                    |
| Backpressure           | Consumer `max.poll.records=500` to prevent memory pressure  |

---

## 4. Redis -- Task Queue and Distributed Locks

### 4.1 What It Is

Redis is an in-memory data structure store that supports strings, hashes, lists,
sets, sorted sets, streams, and more. Its sub-millisecond latency and atomic
operations make it ideal for task queuing and distributed coordination.

### 4.2 Role in the Task Scheduler

Redis serves three critical functions:

1. **Priority queue** -- sorted sets (ZSETs) for task ordering by priority and time
2. **Distributed locks** -- Redlock algorithm for exactly-once task assignment
3. **Caching layer** -- task definitions, worker registry, cron schedules

### 4.3 Priority Queue with Sorted Sets

The simulation's `TaskQueue` uses Java's `PriorityQueue`. In production, Redis
sorted sets (ZSETs) provide the same semantics across a distributed cluster.

```
# Score encoding: (MAX_PRIORITY - priority) * 10^13 + epochMillis
# Lower score = higher priority (ZRANGEBYSCORE returns lowest first)

# Enqueue a CRITICAL task (priority value 4)
ZADD task_queue 60001683639000000 "task-abc-123"
#                ^--- (10000-4) * 10^13 + 1683639000000 (epochMillis)

# Enqueue a LOW task (priority value 1)
ZADD task_queue 99991683639001000 "task-def-456"

# Dequeue highest-priority task (atomic pop)
ZPOPMIN task_queue 1
# Returns: "task-abc-123" (lowest score = highest priority)
```

**Score formula explained:**

```
score = (MAX_PRIORITY_VALUE - task.priority.value) * 10_000_000_000_000L
      + task.createdAt.toEpochMilli()
```

This ensures:
- Higher priority tasks have lower scores (dequeued first)
- Tasks with equal priority are ordered by creation time (FIFO within priority)

### 4.4 Production TaskQueue Implementation

```java
// Production Redis-backed priority queue
public class RedisTaskQueue implements TaskQueue {

    private final JedisPool jedisPool;
    private static final String QUEUE_KEY = "scheduler:task_queue";
    private static final long PRIORITY_MULTIPLIER = 10_000_000_000_000L;
    private static final int MAX_PRIORITY = 10000;

    public RedisTaskQueue(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    // 1. Enqueue: ZADD with composite score
    public void enqueue(Task task) {
        double score = computeScore(task);
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.zadd(QUEUE_KEY, score, task.getId());
            // Store task payload in a hash for retrieval
            jedis.hset("scheduler:task:" + task.getId(), serializeTask(task));
        }
    }

    // 2. Dequeue: atomic ZPOPMIN
    public Optional<Task> dequeue() {
        try (Jedis jedis = jedisPool.getResource()) {
            List<Tuple> result = jedis.zpopmin(QUEUE_KEY, 1);
            if (result.isEmpty()) {
                return Optional.empty();
            }
            String taskId = result.get(0).getElement();
            Map<String, String> taskData = jedis.hgetAll("scheduler:task:" + taskId);
            return Optional.of(deserializeTask(taskData));
        }
    }

    // 3. Peek: ZRANGE with LIMIT 0 1
    public Optional<Task> peek() {
        try (Jedis jedis = jedisPool.getResource()) {
            List<String> result = jedis.zrange(QUEUE_KEY, 0, 0);
            if (result.isEmpty()) {
                return Optional.empty();
            }
            String taskId = result.get(0);
            Map<String, String> taskData = jedis.hgetAll("scheduler:task:" + taskId);
            return Optional.of(deserializeTask(taskData));
        }
    }

    // 4. Size: ZCARD
    public int size() {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.zcard(QUEUE_KEY).intValue();
        }
    }

    // 5. Remove by ID: ZREM
    public boolean remove(String taskId) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.zrem(QUEUE_KEY, taskId) > 0;
        }
    }

    private double computeScore(Task task) {
        return (MAX_PRIORITY - task.getPriority().getValue()) * PRIORITY_MULTIPLIER
               + task.getCreatedAt().toEpochMilli();
    }
}
```

### 4.5 Simulation-to-Redis Mapping

| Simulation (TaskQueue)                       | Production (Redis ZSET)                   |
|----------------------------------------------|-------------------------------------------|
| `PriorityQueue<Task>` with Comparator        | `ZADD task_queue <score> <taskId>`        |
| `queue.offer(task)` -- enqueue               | `ZADD` with composite score               |
| `queue.poll()` -- dequeue highest priority   | `ZPOPMIN task_queue 1`                    |
| `queue.peek()` -- peek                       | `ZRANGE task_queue 0 0`                   |
| `queue.size()` -- count                      | `ZCARD task_queue`                        |
| `queue.removeIf(...)` -- remove by ID        | `ZREM task_queue <taskId>`                |
| Comparator: priority desc, createdAt asc     | Score: (MAX - priority) * 10^13 + millis  |

### 4.6 Distributed Locks with Redlock

The Redlock algorithm provides mutual exclusion across scheduler nodes:

```java
// Redlock for exactly-once task assignment
public class RedisDistributedLock {

    private final List<JedisPool> redisPools;  // 5 independent Redis instances
    private static final int LOCK_TTL_MS = 10_000;
    private static final int QUORUM = 3;       // majority of 5

    // 1. Acquire lock on majority of Redis instances
    public boolean acquireLock(String lockKey, String lockValue) {
        int acquiredCount = 0;
        long startTime = System.currentTimeMillis();

        for (JedisPool pool : redisPools) {
            try (Jedis jedis = pool.getResource()) {
                String result = jedis.set(lockKey, lockValue,
                    SetParams.setParams().nx().px(LOCK_TTL_MS));
                if ("OK".equals(result)) {
                    acquiredCount++;
                }
            } catch (Exception e) {
                // Redis instance unavailable, continue
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        long validityTime = LOCK_TTL_MS - elapsed;

        if (acquiredCount >= QUORUM && validityTime > 0) {
            return true;  // Lock acquired
        }

        // Failed to acquire quorum, release all locks
        releaseLock(lockKey, lockValue);
        return false;
    }

    // 2. Release lock (only if we still own it)
    public void releaseLock(String lockKey, String lockValue) {
        String luaScript = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """;
        for (JedisPool pool : redisPools) {
            try (Jedis jedis = pool.getResource()) {
                jedis.eval(luaScript, List.of(lockKey), List.of(lockValue));
            } catch (Exception e) {
                // Best effort release
            }
        }
    }
}
```

### 4.7 Redis Streams for Event Sourcing (Alternative to Kafka)

For smaller deployments, Redis Streams can replace Kafka:

```
# Append task event to stream
XADD scheduler:events * taskId "task-abc" status "RUNNING" workerId "worker-003"

# Consumer group reads events
XREADGROUP GROUP monitoring consumer-1 COUNT 10 BLOCK 1000 STREAMS scheduler:events >

# Acknowledge processed event
XACK scheduler:events monitoring <eventId>
```

### 4.8 Operational Considerations

| Concern                | Recommendation                                              |
|------------------------|-------------------------------------------------------------|
| Deployment             | Redis Cluster (6 nodes: 3 masters, 3 replicas)             |
| Memory                 | Set `maxmemory` with `volatile-lru` eviction policy         |
| Persistence            | AOF with `appendfsync everysec` for durability              |
| Queue monitoring       | Alert when `ZCARD task_queue` exceeds threshold             |
| Lock TTL               | 10-30 seconds (must exceed max task assignment time)        |
| Connection pooling     | JedisPool with max 50 connections per scheduler node        |
| Lua scripts            | Use for atomic multi-step operations (dequeue + status update) |
| Failover               | Redis Sentinel or Cluster automatic failover                |

---

## 5. PostgreSQL -- Task Metadata and Execution History

### 5.1 What It Is

PostgreSQL is a relational database with full ACID compliance, rich indexing,
JSON support, and row-level locking. It serves as the system of record for all
task scheduler data.

### 5.2 Role in the Task Scheduler

PostgreSQL stores:
- **Task definitions** -- the canonical task metadata (name, type, priority, payload)
- **Execution history** -- every task run with start/end times, worker, result
- **Task dependencies** -- DAG edges (taskId depends on dependsOnId)
- **Cron schedules** -- cron expressions with next fire time
- **Worker registry** -- worker metadata and heartbeat timestamps
- **Scheduler nodes** -- node membership and leader flag

### 5.3 Schema Design

```sql
-- Tasks: the canonical source of truth for task definitions
CREATE TABLE tasks (
    id              VARCHAR(64) PRIMARY KEY,
    name            VARCHAR(256) NOT NULL,
    description     TEXT,
    task_type       VARCHAR(32) NOT NULL,      -- ONE_TIME, DELAYED, CRON, DAG
    priority        VARCHAR(16) NOT NULL,       -- CRITICAL, HIGH, MEDIUM, LOW
    status          VARCHAR(32) NOT NULL,       -- PENDING, QUEUED, ASSIGNED, RUNNING, ...
    payload         JSONB,
    cron_expression VARCHAR(128),
    delay_millis    BIGINT DEFAULT 0,
    max_retries     INT DEFAULT 3,
    timeout_millis  BIGINT DEFAULT 60000,
    group_id        VARCHAR(64),
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    scheduled_at    TIMESTAMP WITH TIME ZONE,
    version         INT DEFAULT 0               -- optimistic locking
);

-- Indexes for common access patterns
CREATE INDEX idx_tasks_status ON tasks(status);
CREATE INDEX idx_tasks_group_id ON tasks(group_id);
CREATE INDEX idx_tasks_type_status ON tasks(task_type, status);
CREATE INDEX idx_tasks_scheduled_at ON tasks(scheduled_at)
    WHERE status = 'PENDING' AND task_type = 'DELAYED';

-- Execution history: one row per task attempt
CREATE TABLE task_executions (
    id              VARCHAR(64) PRIMARY KEY,
    task_id         VARCHAR(64) NOT NULL REFERENCES tasks(id),
    worker_id       VARCHAR(64),
    attempt_number  INT NOT NULL,
    status          VARCHAR(32) NOT NULL,       -- RUNNING, COMPLETED, FAILED, TIMED_OUT
    started_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at    TIMESTAMP WITH TIME ZONE,
    duration_millis BIGINT,
    error_message   TEXT,
    result          JSONB,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_executions_task_id ON task_executions(task_id);
CREATE INDEX idx_executions_worker_id ON task_executions(worker_id);
CREATE INDEX idx_executions_status ON task_executions(status);

-- Task dependencies: DAG edges
CREATE TABLE task_dependencies (
    task_id         VARCHAR(64) NOT NULL REFERENCES tasks(id),
    depends_on_id   VARCHAR(64) NOT NULL REFERENCES tasks(id),
    PRIMARY KEY (task_id, depends_on_id),
    CHECK (task_id != depends_on_id)  -- no self-loops
);

CREATE INDEX idx_deps_depends_on ON task_dependencies(depends_on_id);

-- Workers: registered worker nodes
CREATE TABLE workers (
    id              VARCHAR(64) PRIMARY KEY,
    hostname        VARCHAR(256) NOT NULL,
    port            INT NOT NULL,
    capacity        INT NOT NULL,
    current_load    INT DEFAULT 0,
    status          VARCHAR(32) NOT NULL,       -- ACTIVE, BUSY, DRAINING, DEAD, OFFLINE
    last_heartbeat  TIMESTAMP WITH TIME ZONE NOT NULL,
    registered_at   TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    tags            JSONB DEFAULT '[]'::JSONB
);

CREATE INDEX idx_workers_status ON workers(status);
CREATE INDEX idx_workers_heartbeat ON workers(last_heartbeat);

-- Scheduler nodes: cluster membership
CREATE TABLE scheduler_nodes (
    node_id         VARCHAR(64) PRIMARY KEY,
    hostname        VARCHAR(256) NOT NULL,
    port            INT NOT NULL,
    priority        INT NOT NULL,
    is_leader       BOOLEAN DEFAULT FALSE,
    last_heartbeat  TIMESTAMP WITH TIME ZONE NOT NULL,
    registered_at   TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Cron schedules: persisted cron state
CREATE TABLE cron_schedules (
    task_id         VARCHAR(64) PRIMARY KEY REFERENCES tasks(id),
    cron_expression VARCHAR(128) NOT NULL,
    next_fire_time  TIMESTAMP WITH TIME ZONE NOT NULL,
    last_fire_time  TIMESTAMP WITH TIME ZONE,
    is_active       BOOLEAN DEFAULT TRUE
);

CREATE INDEX idx_cron_next_fire ON cron_schedules(next_fire_time)
    WHERE is_active = TRUE;
```

### 5.4 ACID for State Transitions

Task state transitions must be atomic to prevent race conditions:

```sql
-- Atomic state transition with optimistic locking
UPDATE tasks
SET    status = 'ASSIGNED',
       updated_at = NOW(),
       version = version + 1
WHERE  id = 'task-abc-123'
AND    status = 'QUEUED'       -- Only transition from QUEUED
AND    version = 5;            -- Optimistic lock check

-- If 0 rows affected, another node already transitioned this task
```

```java
// Java implementation with optimistic locking
public boolean transitionTaskStatus(String taskId, TaskStatus from, TaskStatus to) {
    String sql = """
        UPDATE tasks SET status = ?, updated_at = NOW(), version = version + 1
        WHERE id = ? AND status = ? AND version = ?
        """;
    int rowsAffected = jdbcTemplate.update(sql, to.name(), taskId, from.name(), currentVersion);
    return rowsAffected == 1;  // true = success, false = conflict
}
```

### 5.5 Simulation-to-PostgreSQL Mapping

| Simulation Class                  | PostgreSQL Table       | Key Differences                      |
|-----------------------------------|------------------------|--------------------------------------|
| `InMemoryTaskRepository`          | `tasks`                | ConcurrentHashMap -> indexed rows    |
| `InMemoryExecutionRepository`     | `task_executions`      | HashMap -> foreign key to tasks      |
| `InMemoryWorkerRepository`        | `workers`              | HashMap -> heartbeat index           |
| `InMemorySchedulerNodeRepository` | `scheduler_nodes`      | HashMap -> leader flag column        |
| `DependencyResolver.dependencies` | `task_dependencies`    | HashMap<Set> -> junction table       |
| `CronSchedule` model              | `cron_schedules`       | Object -> row with next_fire_time    |

### 5.6 Connection Pooling

```java
// HikariCP connection pool configuration
HikariConfig config = new HikariConfig();
config.setJdbcUrl("jdbc:postgresql://pg-primary:5432/scheduler");
config.setUsername("scheduler_app");
config.setMaximumPoolSize(20);           // Match expected concurrent transactions
config.setMinimumIdle(5);
config.setConnectionTimeout(5000);       // 5s max wait for connection
config.setIdleTimeout(300000);           // 5m idle before eviction
config.setMaxLifetime(600000);           // 10m max connection lifetime
config.setLeakDetectionThreshold(30000); // 30s leak detection
```

### 5.7 Partitioning for Scale

For high-volume schedulers processing millions of tasks:

```sql
-- Partition execution history by month
CREATE TABLE task_executions (
    id              VARCHAR(64),
    task_id         VARCHAR(64) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    -- ... other columns
) PARTITION BY RANGE (created_at);

CREATE TABLE task_executions_2026_05 PARTITION OF task_executions
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');

CREATE TABLE task_executions_2026_06 PARTITION OF task_executions
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
```

### 5.8 Operational Considerations

| Concern                | Recommendation                                              |
|------------------------|-------------------------------------------------------------|
| Replication            | Streaming replication with 1 sync + 1 async standby         |
| Backup                 | pg_basebackup daily, WAL archiving for PITR                 |
| Vacuuming              | Autovacuum tuned for high-update `tasks` table              |
| Index maintenance      | `REINDEX CONCURRENTLY` monthly on high-churn indexes        |
| Connection limits      | `max_connections=200`, use PgBouncer for connection pooling  |
| Monitoring             | pg_stat_statements for slow query tracking                  |
| Partitioning           | Range-partition `task_executions` by month                  |
| Lock contention        | Use `SKIP LOCKED` for task claim queries                    |

---

## 6. Apache Airflow -- DAG-Based Workflow Orchestration

### 6.1 What It Is

Apache Airflow is an open-source platform for programmatically authoring, scheduling,
and monitoring workflows. Workflows are defined as Directed Acyclic Graphs (DAGs)
of tasks, where edges represent dependencies.

### 6.2 Relevance to the Task Scheduler

Airflow serves as the reference architecture for our DAG resolution and cron
scheduling features. The simulation's `DependencyResolver` and `CronParser`
implement simplified versions of Airflow's core scheduling logic.

### 6.3 Airflow Architecture

```
+------------------+     +------------------+     +------------------+
|   Web Server     |     |   Scheduler      |     |   Workers        |
| (Flask UI)       |     | (DAG parsing,    |     | (task execution) |
|                  |     |  trigger rules,  |     |                  |
|                  |     |  task dispatch)  |     |                  |
+--------+---------+     +--------+---------+     +--------+---------+
         |                        |                        |
         +------------------------+------------------------+
                                  |
                          +-------v--------+
                          |  Metadata DB   |
                          |  (PostgreSQL)  |
                          +----------------+
```

### 6.4 DAG Concepts Mapped to Simulation

| Airflow Concept          | Simulation Equivalent                           |
|--------------------------|-------------------------------------------------|
| DAG                      | `TaskGroup` (group of related tasks)            |
| Task (operator)          | `Task` model with type, priority, payload       |
| Dependency edge          | `DependencyResolver.addDependency(taskId, dep)` |
| Trigger rule (all_done)  | `getReadyTasks(completedSet)`                   |
| DagRun                   | Implicit -- group execution via `groupId`       |
| TaskInstance             | `TaskExecution` (one attempt of a task)         |
| Schedule interval        | `CronSchedule` + `CronParser`                  |

### 6.5 Airflow Executor Models

Airflow supports multiple executor backends, each with different scaling properties:

```
1. LocalExecutor       -- single machine, multiprocess
2. CeleryExecutor      -- distributed via Celery + Redis/RabbitMQ
3. KubernetesExecutor  -- one pod per task, auto-scaling
4. DaskExecutor        -- Dask distributed for data workloads
```

The simulation most closely resembles the CeleryExecutor model, where a central
scheduler dispatches tasks to a pool of workers:

```
Simulation:                         Airflow CeleryExecutor:
SchedulerEngine.tick()              Airflow Scheduler loop
  -> DependencyResolver             -> DagFileProcessor (parse DAGs)
  -> TaskQueue.dequeue()            -> CeleryExecutor.queue_command()
  -> AssignmentStrategy.assign()    -> Celery broker (Redis/RabbitMQ)
  -> Worker executes                -> Celery worker executes
```

### 6.6 Lessons from Airflow for the Scheduler

1. **Single active scheduler**: Airflow ran a single scheduler until Airflow 2.0
   introduced HA scheduler mode. Our simulation mirrors this with leader election.

2. **Database as queue anti-pattern**: Airflow historically used PostgreSQL as a
   task queue (polling `task_instance` table). This caused DB hotspots. Our design
   separates the queue (Redis) from the metadata store (PostgreSQL).

3. **DAG parsing overhead**: Airflow re-parses DAG files every `dag_dir_list_interval`.
   Our simulation's `DependencyResolver` keeps the graph in memory, which maps to
   a Redis or in-memory cache in production.

4. **Zombie task detection**: Airflow detects "zombie" tasks (tasks that appear to
   be running but their worker process has died) by checking heartbeats. Our
   `FailoverService.detectDeadWorkers()` implements the same pattern.

### 6.7 Operational Notes

| Concern              | Airflow Pattern                              | Our Scheduler Pattern           |
|----------------------|----------------------------------------------|---------------------------------|
| HA                   | Multiple scheduler instances (2.0+)          | Leader election (Bully/ZK)     |
| Task isolation       | KubernetesExecutor (pod per task)            | Worker pool with capacity       |
| Retry                | `retries` + `retry_delay` in DAG def         | `RetryPolicy` + backoff strategy|
| Monitoring           | Airflow UI + StatsD                          | Prometheus + Grafana            |
| Scheduling           | Cron presets or timetable                    | CronParser + CronSchedule      |

---

## 7. Celery -- Distributed Task Queue

### 7.1 What It Is

Celery is a distributed task queue system written in Python. While our simulation
is Java-based, Celery's architectural patterns (broker, worker, result backend)
are directly applicable.

### 7.2 Celery Architecture

```
+------------+    +--------+    +----------+    +----------------+
|  Producer  +--->| Broker +--->| Workers  +--->| Result Backend |
| (submit    |    | (Redis |    | (consume |    | (Redis/DB)     |
|  tasks)    |    |  /AMQP)|    |  & exec) |    |                |
+------------+    +--------+    +----------+    +----------------+
```

### 7.3 Celery Concepts Mapped to Simulation

| Celery Concept              | Simulation Equivalent                            |
|-----------------------------|--------------------------------------------------|
| `@app.task` decorator       | `Task.builder("name").build()`                   |
| Broker (Redis/RabbitMQ)     | `TaskQueue` (PriorityQueue in memory)            |
| Worker process              | `Worker` model with capacity and status          |
| Prefetch count              | `Worker.capacity` (max concurrent tasks)         |
| Task routes                 | `Worker.tags` (affinity-based routing)           |
| ETA / countdown             | `Task.delayMillis` + `DelayedSchedulingStrategy` |
| Retry with backoff          | `ExponentialBackoffRetryStrategy`                |
| Celery beat                 | `CronSchedulingStrategy` + `CronParser`          |
| Chain/chord/group           | `TaskGroup` + `DependencyResolver`               |
| Result backend              | `ExecutionRepository` (stores `TaskExecution`)   |
| Worker heartbeat            | `Worker.lastHeartbeat` + `FailoverService`       |

### 7.4 Priority Queue in Celery vs. Simulation

Celery supports priority via separate queues or broker-level priority:

```python
# Celery: priority via separate queues
app.conf.task_routes = {
    'tasks.critical_*': {'queue': 'critical'},
    'tasks.batch_*':    {'queue': 'low'},
}

# Celery: broker-level priority (Redis)
# Redis sorted sets with priority as score
@app.task(priority=9)  # 0-9, higher = more urgent
def critical_task():
    pass
```

The simulation's `TaskQueue` uses a single `PriorityQueue` with a comparator,
equivalent to Celery's broker-level priority with Redis sorted sets.

### 7.5 Celery Canvas (Workflow Primitives)

```python
# Celery chain = sequential execution (DAG: A -> B -> C)
chain(task_a.s(), task_b.s(), task_c.s())()

# Celery group = parallel execution
group(task_a.s(), task_b.s(), task_c.s())()

# Celery chord = group + callback (fan-out then fan-in)
chord(group(task_a.s(), task_b.s()), task_c.s())()
```

The simulation's `DependencyResolver` generalizes these patterns into arbitrary
DAGs, where any task can depend on any set of upstream tasks.

### 7.6 Lessons from Celery

1. **Visibility timeout**: Celery's `visibility_timeout` re-queues tasks that are
   not acknowledged within the timeout. This prevents tasks from being lost if a
   worker crashes mid-execution. Our `FailoverService` implements the same concept.

2. **Prefetch multiplier**: Celery workers prefetch multiple tasks to reduce latency.
   The simulation's `Worker.capacity` serves a similar purpose.

3. **Late acknowledgment**: Celery can be configured to acknowledge tasks only after
   completion (`task_acks_late=True`). This provides at-least-once execution
   guarantees, matching our scheduler's exactly-once semantics via idempotent
   state transitions.

---

## 8. Temporal.io -- Durable Execution

### 8.1 What It Is

Temporal is a microservice orchestration platform that provides durable execution --
workflows that survive process crashes, infrastructure failures, and deployments
without losing state.

### 8.2 Relevance to the Task Scheduler

Temporal represents the next evolution of task scheduling beyond Airflow/Celery.
While our simulation implements manual state management, Temporal automates this
with its event-sourced workflow engine.

### 8.3 Temporal Architecture

```
+------------------+     +------------------+     +------------------+
|  Temporal Server |     |  Workflow Worker  |     |  Activity Worker |
| (history, queue, |     | (runs workflow   |     | (runs individual |
|  matching, timer)|     |  logic/DAG)      |     |  tasks)          |
+--------+---------+     +--------+---------+     +--------+---------+
         |                        |                        |
         +------------------------+------------------------+
                                  |
                          +-------v--------+
                          |  Persistence   |
                          |  (Cassandra/   |
                          |   PostgreSQL)  |
                          +----------------+
```

### 8.4 Temporal Concepts Mapped to Simulation

| Temporal Concept        | Simulation Equivalent                             |
|-------------------------|---------------------------------------------------|
| Workflow                | `TaskGroup` (DAG of tasks)                        |
| Activity                | Individual `Task` execution                       |
| Workflow history        | `TaskExecution` records in `ExecutionRepository`  |
| Task queue              | `TaskQueue` (priority queue)                      |
| Retry policy            | `RetryPolicy` + `ExponentialBackoffRetryStrategy` |
| Timer (sleep/cron)      | `CronParser` + `CronSchedulingStrategy`           |
| Heartbeat               | `Worker.lastHeartbeat`                            |
| Workflow ID dedup       | `Task.id` uniqueness check                        |

### 8.5 What Temporal Automates That the Simulation Does Manually

1. **State persistence**: Temporal automatically persists workflow state after each
   step. The simulation manually writes to `ExecutionRepository`.

2. **Retry logic**: Temporal retries failed activities automatically based on a
   retry policy. The simulation's `ExponentialBackoffRetryStrategy` is the manual
   equivalent.

3. **Timer management**: Temporal manages timers durably (survives restarts). The
   simulation's `CronParser.getNextFireTime()` recalculates on each scheduler tick.

4. **Exactly-once semantics**: Temporal provides exactly-once workflow execution via
   workflow ID deduplication. The simulation achieves this via idempotent state
   transitions and distributed locks.

### 8.6 When to Choose Temporal Over Custom Scheduler

| Scenario                               | Custom Scheduler | Temporal              |
|----------------------------------------|------------------|-----------------------|
| Simple cron + priority queue           | Good fit         | Over-engineered       |
| Complex multi-step workflows           | Manageable       | Excellent fit         |
| Long-running workflows (hours/days)    | Challenging      | Built for this        |
| Need custom scheduling algorithms      | Full control     | Limited customization |
| Team knows Java/internal infra         | Lower overhead   | Learning curve        |
| Need workflow versioning               | Build it yourself| Built-in              |

---

## 9. gRPC -- Inter-Service Communication

### 9.1 What It Is

gRPC is a high-performance RPC framework built on HTTP/2 and Protocol Buffers.
It supports bidirectional streaming, multiplexing, and automatic code generation
for client/server stubs.

### 9.2 Role in the Task Scheduler

gRPC handles three communication patterns:

1. **Worker registration** -- workers register with the scheduler on startup
2. **Heartbeats** -- workers send periodic heartbeat streams to the scheduler
3. **Task dispatch** -- scheduler pushes assigned tasks to workers

### 9.3 Protocol Buffer Definitions

```protobuf
syntax = "proto3";
package scheduler;

// --- Service definitions ---

service SchedulerService {
    // Worker registration
    rpc RegisterWorker(RegisterWorkerRequest) returns (RegisterWorkerResponse);
    rpc DeregisterWorker(DeregisterWorkerRequest) returns (DeregisterWorkerResponse);

    // Task operations
    rpc SubmitTask(SubmitTaskRequest) returns (SubmitTaskResponse);
    rpc CancelTask(CancelTaskRequest) returns (CancelTaskResponse);
    rpc GetTaskStatus(GetTaskStatusRequest) returns (GetTaskStatusResponse);

    // Bidirectional streaming for heartbeats
    rpc WorkerHeartbeat(stream HeartbeatRequest) returns (stream HeartbeatResponse);

    // Server streaming for task assignments
    rpc StreamTaskAssignments(WorkerIdentity) returns (stream TaskAssignment);
}

// --- Messages ---

message RegisterWorkerRequest {
    string hostname = 1;
    int32 port = 2;
    int32 capacity = 3;
    repeated string tags = 4;
}

message RegisterWorkerResponse {
    string worker_id = 1;
    bool success = 2;
}

message HeartbeatRequest {
    string worker_id = 1;
    int32 current_load = 2;
    WorkerStatus status = 3;
    int64 timestamp_millis = 4;
}

message HeartbeatResponse {
    bool acknowledged = 1;
    repeated string cancel_task_ids = 2;  // tasks to cancel
}

message TaskAssignment {
    string task_id = 1;
    string task_name = 2;
    TaskType task_type = 3;
    Priority priority = 4;
    map<string, string> payload = 5;
    int64 timeout_millis = 6;
    int32 max_retries = 7;
}

enum WorkerStatus {
    WORKER_ACTIVE = 0;
    WORKER_BUSY = 1;
    WORKER_DRAINING = 2;
}

enum TaskType {
    ONE_TIME = 0;
    DELAYED = 1;
    CRON = 2;
    DAG = 3;
}

enum Priority {
    LOW = 0;
    MEDIUM = 1;
    HIGH = 2;
    CRITICAL = 3;
}
```

### 9.4 Bidirectional Streaming for Heartbeats

```java
// Worker-side: send heartbeats every 5 seconds
StreamObserver<HeartbeatRequest> requestObserver =
    asyncStub.workerHeartbeat(new StreamObserver<HeartbeatResponse>() {
        @Override
        public void onNext(HeartbeatResponse response) {
            if (!response.getCancelTaskIdsList().isEmpty()) {
                cancelTasks(response.getCancelTaskIdsList());
            }
        }

        @Override
        public void onError(Throwable t) {
            log.error("Heartbeat stream error", t);
            reconnect();
        }

        @Override
        public void onCompleted() {
            log.info("Heartbeat stream completed");
        }
    });

// Periodic heartbeat sender
scheduler.scheduleAtFixedRate(() -> {
    requestObserver.onNext(HeartbeatRequest.newBuilder()
        .setWorkerId(workerId)
        .setCurrentLoad(getCurrentLoad())
        .setStatus(getWorkerStatus())
        .setTimestampMillis(System.currentTimeMillis())
        .build());
}, 0, 5, TimeUnit.SECONDS);
```

### 9.5 Server Streaming for Task Push

```java
// Scheduler-side: push task assignments to workers via server streaming
@Override
public void streamTaskAssignments(WorkerIdentity request,
                                   StreamObserver<TaskAssignment> responseObserver) {
    String workerId = request.getWorkerId();
    // Register stream observer for this worker
    workerStreams.put(workerId, responseObserver);

    // When a task is assigned to this worker, push it
    // (called from SchedulerEngine when dispatch happens)
}

// Push a task assignment to a specific worker
public void pushTaskToWorker(String workerId, Task task) {
    StreamObserver<TaskAssignment> observer = workerStreams.get(workerId);
    if (observer != null) {
        observer.onNext(TaskAssignment.newBuilder()
            .setTaskId(task.getId())
            .setTaskName(task.getName())
            .setTaskType(toProtoType(task.getTaskType()))
            .setPriority(toProtoPriority(task.getPriority()))
            .putAllPayload(task.getPayload())
            .setTimeoutMillis(task.getTimeoutMillis())
            .setMaxRetries(task.getMaxRetries())
            .build());
    }
}
```

### 9.6 Simulation Mapping

| Simulation                                   | Production gRPC                            |
|----------------------------------------------|--------------------------------------------|
| `WorkerService.registerWorker(Worker)`       | `RegisterWorker` RPC                       |
| `Worker.updateHeartbeat()`                   | `WorkerHeartbeat` bidirectional stream     |
| `SchedulerEngine` dispatches to Worker       | `StreamTaskAssignments` server stream      |
| `FailoverService.detectDeadWorkers()`        | Heartbeat stream timeout detection         |
| Direct method calls on Worker objects        | Network RPCs with serialized protobuf      |

### 9.7 Why gRPC Over REST

| Feature                  | REST (HTTP/1.1 JSON)      | gRPC (HTTP/2 Protobuf)       |
|--------------------------|---------------------------|------------------------------|
| Serialization            | JSON (text, larger)       | Protobuf (binary, compact)   |
| Streaming                | WebSockets (complex)      | Native bidirectional         |
| Connection multiplexing  | No (1 request/connection) | Yes (many requests/conn)     |
| Code generation          | OpenAPI (optional)        | Built-in from .proto         |
| Heartbeat latency        | ~5ms                      | ~0.5ms                       |
| Schema evolution         | Manual versioning         | Protobuf field numbering     |

### 9.8 Operational Considerations

| Concern                | Recommendation                                              |
|------------------------|-------------------------------------------------------------|
| Load balancing         | Client-side LB (pick_first, round_robin) or Envoy proxy    |
| Deadline/timeout       | Set `deadline` on every RPC (e.g., 5s for registration)    |
| Retry                  | gRPC retry policy with exponential backoff                  |
| Health checking        | gRPC Health Checking Protocol for readiness probes          |
| TLS                    | Mutual TLS between scheduler and workers                    |
| Interceptors           | Logging, metrics, authentication interceptors               |
| Max message size       | Default 4MB, increase for large task payloads if needed     |
| Keepalive              | `GRPC_ARG_KEEPALIVE_TIME_MS=30000` for long-lived streams  |

---

## 10. Prometheus + Grafana -- Monitoring and Alerting

### 10.1 What They Are

**Prometheus** is a time-series database and monitoring system that scrapes metrics
from instrumented services via HTTP endpoints. **Grafana** is a visualization
platform that queries Prometheus and renders dashboards.

### 10.2 Role in the Task Scheduler

The monitoring stack provides visibility into:
- Task throughput and latency
- Queue depth and wait times
- Worker utilization and health
- Retry rates and failure patterns
- Cron schedule adherence
- Leader election events

### 10.3 Metric Design

```
# ---- Task Metrics ----

# Counter: total tasks submitted by type and priority
scheduler_tasks_submitted_total{task_type="ONE_TIME", priority="HIGH"}

# Counter: total task completions by result
scheduler_tasks_completed_total{result="SUCCESS"}
scheduler_tasks_completed_total{result="FAILED"}
scheduler_tasks_completed_total{result="TIMED_OUT"}

# Histogram: task execution duration in seconds
scheduler_task_duration_seconds_bucket{task_type="CRON", le="1.0"}
scheduler_task_duration_seconds_bucket{task_type="CRON", le="5.0"}
scheduler_task_duration_seconds_bucket{task_type="CRON", le="30.0"}
scheduler_task_duration_seconds_bucket{task_type="CRON", le="+Inf"}

# Gauge: current queue depth by priority
scheduler_queue_depth{priority="CRITICAL"}
scheduler_queue_depth{priority="HIGH"}
scheduler_queue_depth{priority="MEDIUM"}
scheduler_queue_depth{priority="LOW"}

# Histogram: time spent waiting in queue before dispatch
scheduler_queue_wait_seconds_bucket{priority="CRITICAL", le="1.0"}
scheduler_queue_wait_seconds_bucket{priority="CRITICAL", le="5.0"}

# ---- Worker Metrics ----

# Gauge: worker utilization (currentLoad / capacity)
scheduler_worker_utilization{worker_id="worker-001", hostname="host-a"}

# Gauge: number of active workers
scheduler_workers_active_total

# Counter: worker failover events
scheduler_worker_failovers_total

# Histogram: time between heartbeats
scheduler_heartbeat_interval_seconds_bucket{le="5.0"}
scheduler_heartbeat_interval_seconds_bucket{le="10.0"}

# ---- Retry Metrics ----

# Counter: retry attempts by strategy
scheduler_retry_attempts_total{strategy="EXPONENTIAL_BACKOFF"}
scheduler_retry_attempts_total{strategy="FIXED_INTERVAL"}

# Histogram: retry delay applied
scheduler_retry_delay_seconds_bucket{le="1.0"}
scheduler_retry_delay_seconds_bucket{le="10.0"}
scheduler_retry_delay_seconds_bucket{le="60.0"}

# Counter: tasks sent to dead letter queue
scheduler_deadletter_total

# ---- Cron Metrics ----

# Gauge: time until next cron fire (per schedule)
scheduler_cron_next_fire_seconds{task_id="daily-report"}

# Counter: cron fires
scheduler_cron_fires_total{task_id="daily-report"}

# Histogram: cron schedule drift (actual fire time - expected fire time)
scheduler_cron_drift_seconds_bucket{le="1.0"}
scheduler_cron_drift_seconds_bucket{le="5.0"}

# ---- Leader Election Metrics ----

# Counter: leader elections triggered
scheduler_leader_elections_total

# Gauge: is this node the leader (0 or 1)
scheduler_is_leader{node_id="node-001"}

# Histogram: time to complete leader election
scheduler_leader_election_duration_seconds_bucket{le="1.0"}
scheduler_leader_election_duration_seconds_bucket{le="5.0"}

# ---- DAG Metrics ----

# Histogram: DAG resolution time (topological sort)
scheduler_dag_resolution_seconds_bucket{le="0.01"}
scheduler_dag_resolution_seconds_bucket{le="0.1"}

# Counter: cycle detection events
scheduler_dag_cycles_detected_total
```

### 10.4 Java Instrumentation with Micrometer

```java
// Micrometer metrics registry (exports to Prometheus)
public class SchedulerMetrics {

    private final MeterRegistry registry;
    private final Counter tasksSubmitted;
    private final Counter tasksCompleted;
    private final Counter tasksFailed;
    private final AtomicInteger queueDepth;
    private final Timer taskDuration;
    private final Counter retryAttempts;
    private final Counter failovers;

    public SchedulerMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.tasksSubmitted = Counter.builder("scheduler.tasks.submitted")
            .description("Total tasks submitted")
            .tag("component", "scheduler")
            .register(registry);

        this.tasksCompleted = Counter.builder("scheduler.tasks.completed")
            .tag("result", "success")
            .register(registry);

        this.tasksFailed = Counter.builder("scheduler.tasks.completed")
            .tag("result", "failed")
            .register(registry);

        this.queueDepth = registry.gauge("scheduler.queue.depth",
            new AtomicInteger(0));

        this.taskDuration = Timer.builder("scheduler.task.duration")
            .description("Task execution duration")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);

        this.retryAttempts = Counter.builder("scheduler.retry.attempts")
            .register(registry);

        this.failovers = Counter.builder("scheduler.worker.failovers")
            .register(registry);
    }

    public void recordTaskSubmitted() { tasksSubmitted.increment(); }
    public void recordTaskCompleted() { tasksCompleted.increment(); }
    public void recordTaskFailed() { tasksFailed.increment(); }
    public void updateQueueDepth(int depth) { queueDepth.set(depth); }

    public void recordTaskDuration(long durationMs) {
        taskDuration.record(Duration.ofMillis(durationMs));
    }
}
```

### 10.5 Alerting Rules

```yaml
# Prometheus alerting rules
groups:
  - name: scheduler_alerts
    rules:
      # Queue depth too high
      - alert: SchedulerQueueBacklog
        expr: scheduler_queue_depth{priority="CRITICAL"} > 100
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Critical task queue backlog exceeds 100"
          description: "{{ $value }} critical tasks waiting in queue"

      # No healthy workers
      - alert: SchedulerNoWorkers
        expr: scheduler_workers_active_total == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "No active workers available"

      # High task failure rate
      - alert: SchedulerHighFailureRate
        expr: >
          rate(scheduler_tasks_completed_total{result="FAILED"}[5m])
          / rate(scheduler_tasks_completed_total[5m]) > 0.1
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "Task failure rate exceeds 10%"

      # Worker heartbeat missing
      - alert: SchedulerWorkerUnresponsive
        expr: >
          time() - scheduler_worker_last_heartbeat_seconds > 30
        for: 0m
        labels:
          severity: critical
        annotations:
          summary: "Worker {{ $labels.worker_id }} missed heartbeat"

      # Leader election too frequent
      - alert: SchedulerLeaderFlapping
        expr: rate(scheduler_leader_elections_total[10m]) > 0.1
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Frequent leader elections detected (flapping)"

      # Cron schedule drift
      - alert: SchedulerCronDrift
        expr: scheduler_cron_drift_seconds > 60
        for: 0m
        labels:
          severity: warning
        annotations:
          summary: "Cron schedule {{ $labels.task_id }} drifted >60s"

      # Dead letter queue growing
      - alert: SchedulerDeadLetterGrowing
        expr: rate(scheduler_deadletter_total[1h]) > 10
        for: 30m
        labels:
          severity: warning
        annotations:
          summary: "Dead letter queue receiving >10 tasks/hour"
```

### 10.6 Grafana Dashboard Panels

```
Row 1: Overview
  [Tasks Submitted Rate]  [Tasks Completed Rate]  [Failure Rate %]  [Queue Depth]

Row 2: Task Performance
  [P50/P95/P99 Duration]  [Duration by Type]  [Queue Wait Time]

Row 3: Workers
  [Active Workers]  [Worker Utilization Heatmap]  [Failover Events]

Row 4: Retry & Errors
  [Retry Attempts Rate]  [Retry Delay Distribution]  [DLQ Count]

Row 5: Cron & DAG
  [Cron Fire Rate]  [Cron Drift]  [DAG Resolution Time]  [Cycle Detections]

Row 6: Infrastructure
  [Leader Node]  [Election Events]  [Redis Latency]  [PostgreSQL Query Time]
```

### 10.7 Simulation Mapping

| Simulation (MonitoringService)                | Production (Prometheus)                    |
|-----------------------------------------------|--------------------------------------------|
| `System.out.println("[STATS]...")`            | Micrometer Counter/Gauge/Timer             |
| `SchedulerStatsDisplay` console output        | Grafana dashboard panels                   |
| Manual stat tracking in-memory                | Prometheus time-series storage             |
| No alerting                                   | Alertmanager rules + PagerDuty/Slack       |

---

## 11. Priority Queue Data Structures

### 11.1 Overview

The priority queue is the heart of the scheduler's dispatch mechanism. Tasks are
ordered by priority (CRITICAL > HIGH > MEDIUM > LOW) and within the same priority
by creation time (FIFO). This section compares implementation choices.

### 11.2 Java PriorityQueue (Simulation)

The simulation uses `java.util.PriorityQueue` with a custom comparator:

```java
// From TaskQueue.java
private static final Comparator<Task> TASK_COMPARATOR = Comparator
    .comparingInt((Task t) -> t.getPriority().getValue())
    .reversed()                           // higher value = higher priority = first
    .thenComparing(Task::getCreatedAt);   // earlier creation = first (FIFO within priority)
```

**Characteristics:**
- Binary min-heap (array-backed)
- O(log n) enqueue and dequeue
- O(n) remove by ID (linear scan)
- Not thread-safe (single-threaded scheduler loop)
- In-process only (no distribution)

### 11.3 Redis ZSET (Production)

Redis sorted sets provide distributed priority queuing:

```
Command         | Time Complexity | Description
ZADD            | O(log n)        | Enqueue with priority score
ZPOPMIN         | O(log n)        | Atomic dequeue lowest score
ZRANGEBYSCORE   | O(log n + k)    | Range query by score (priority range)
ZREM            | O(log n)        | Remove specific task by ID
ZCARD           | O(1)            | Queue size
ZSCORE          | O(1)            | Get score for a task
```

**Advantages over Java PriorityQueue:**
- Distributed -- accessible from any scheduler or worker node
- Atomic operations -- ZPOPMIN is thread-safe across processes
- Persistence -- AOF/RDB snapshots survive restarts
- Range queries -- efficiently query by priority range

### 11.4 Kafka Partitioning for Priority

Kafka can implement priority queuing via partition assignment:

```
Topic: scheduler.tasks
  Partition 0: CRITICAL tasks (consumer processes first)
  Partition 1: HIGH tasks
  Partition 2: MEDIUM tasks
  Partition 3: LOW tasks

Consumer: round-robin poll with priority weighting
  - Poll partition 0 with weight 4
  - Poll partition 1 with weight 3
  - Poll partition 2 with weight 2
  - Poll partition 3 with weight 1
```

This approach provides ordering within each priority level (partition ordering
guarantee) but requires careful consumer logic to respect priority weighting.

### 11.5 Comparison Matrix

| Feature            | Java PQ           | Redis ZSET         | Kafka Partitions    |
|--------------------|-------------------|--------------------|---------------------|
| Distribution       | Single process    | Cluster-wide       | Cluster-wide        |
| Thread safety      | No                | Yes (atomic ops)   | Yes (consumer groups)|
| Persistence        | No                | AOF/RDB            | Log retention       |
| Enqueue            | O(log n)          | O(log n)           | O(1) append         |
| Dequeue            | O(log n)          | O(log n)           | O(1) poll           |
| Priority ordering  | Exact             | Exact (by score)   | Partition-level     |
| FIFO within prio   | Via comparator    | Via score encoding  | Partition ordering  |
| Remove by ID       | O(n)              | O(log n)           | Not supported       |
| Backpressure       | Heap memory       | Redis maxmemory    | Consumer lag        |
| Use case           | Simulation        | Production queue   | Event streaming     |

### 11.6 Delayed Queue Implementation

For delayed tasks, the simulation uses `Task.delayMillis`. In production:

```
# Redis: use ZSET with scheduled time as score
ZADD scheduler:delayed_queue <scheduledTimeEpochMillis> <taskId>

# Polling loop: move due tasks to ready queue
local due = redis.call('ZRANGEBYSCORE', 'scheduler:delayed_queue', 0, ARGV[1])
for _, taskId in ipairs(due) do
    redis.call('ZREM', 'scheduler:delayed_queue', taskId)
    redis.call('ZADD', 'scheduler:task_queue', <priorityScore>, taskId)
end
```

---

## 12. Graph Algorithms -- DAG Resolution

### 12.1 Overview

The scheduler's DAG resolution engine uses two graph algorithms:
1. **Kahn's topological sort** -- determines execution order
2. **DFS three-coloring cycle detection** -- validates DAG structure

Both are implemented in `DependencyResolver.java`.

### 12.2 Kahn's Algorithm (Topological Sort)

**Purpose:** Determine a valid execution order for tasks with dependencies.

**Algorithm (from DependencyResolver.getTopologicalOrder()):**

```
1. Build in-degree map: for each node, count incoming edges
2. Build adjacency map: dependsOn -> Set<dependents>
3. Initialize queue with all zero-in-degree nodes (no dependencies)
4. While queue is not empty:
   a. Poll node from queue, add to result order
   b. For each dependent of this node:
      - Decrement dependent's in-degree
      - If in-degree reaches 0, add to queue
5. Return result order
```

**Time complexity:** O(V + E) where V = tasks, E = dependency edges
**Space complexity:** O(V + E) for adjacency and in-degree maps

**Production considerations:**
- The simulation keeps the graph in memory (`HashMap<String, Set<String>>`)
- In production, edges live in the `task_dependencies` PostgreSQL table
- For hot DAGs (frequently resolved), cache the topological order in Redis
  with invalidation on dependency change

### 12.3 DFS Cycle Detection (Three-Coloring)

**Purpose:** Detect cycles before attempting topological sort.

**Algorithm (from DependencyResolver.hasCycle()):**

```
Color states: WHITE (unvisited), GRAY (in current DFS stack), BLACK (fully processed)

1. Initialize all nodes as WHITE
2. For each WHITE node, run DFS:
   a. Color node GRAY (entering DFS stack)
   b. For each dependency of this node:
      - If GRAY: back edge found -> CYCLE DETECTED
      - If WHITE: recurse
   c. Color node BLACK (leaving DFS stack)
3. If no back edges found, graph is acyclic
```

**Time complexity:** O(V + E)
**Space complexity:** O(V) for color map + O(V) for recursion stack

**Production considerations:**
- Cycle detection runs on task submission (fail-fast) and before scheduling
- For very large DAGs (10,000+ tasks), iterative DFS avoids stack overflow:

```java
// Iterative DFS cycle detection (production-safe for deep graphs)
public boolean hasCycleIterative() {
    Map<String, Integer> color = new HashMap<>();
    dependencies.keySet().forEach(n -> color.put(n, WHITE));
    Deque<Frame> stack = new ArrayDeque<>();

    for (String start : dependencies.keySet()) {
        if (color.get(start) != WHITE) continue;
        stack.push(new Frame(start, dependencies.getOrDefault(start, Set.of()).iterator()));
        color.put(start, GRAY);

        while (!stack.isEmpty()) {
            Frame frame = stack.peek();
            if (frame.neighbors.hasNext()) {
                String neighbor = frame.neighbors.next();
                if (color.getOrDefault(neighbor, WHITE) == GRAY) return true;
                if (color.getOrDefault(neighbor, WHITE) == WHITE) {
                    color.put(neighbor, GRAY);
                    stack.push(new Frame(neighbor,
                        dependencies.getOrDefault(neighbor, Set.of()).iterator()));
                }
            } else {
                color.put(frame.node, BLACK);
                stack.pop();
            }
        }
    }
    return false;
}
```

### 12.4 getReadyTasks -- Dependency Resolution at Runtime

The most frequently called method in DAG resolution:

```java
// From DependencyResolver.java
public Set<String> getReadyTasks(Set<String> completedTaskIds) {
    Set<String> ready = new HashSet<>();
    for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
        String taskId = entry.getKey();
        if (completedTaskIds.contains(taskId)) continue;
        if (completedTaskIds.containsAll(entry.getValue())) {
            ready.add(taskId);
        }
    }
    return ready;
}
```

**Production optimization:**
- Maintain a reverse index (dependsOn -> dependents) so that when a task completes,
  only its direct dependents need to be checked (not the entire graph)
- Cache the ready set in Redis and invalidate on task completion

### 12.5 Comparison with Airflow's DagBag

| Feature                   | Simulation DependencyResolver      | Airflow DagBag                  |
|---------------------------|------------------------------------|---------------------------------|
| Graph storage             | HashMap<String, Set<String>>       | Python DAG objects in memory    |
| Cycle detection           | DFS three-coloring                 | Implicit (DAG class validates)  |
| Topological sort          | Kahn's algorithm                   | Not exposed (scheduler handles) |
| Ready task resolution     | `getReadyTasks(completedSet)`      | Trigger rules (all_success, etc)|
| Dynamic dependencies      | `addDependency` / `removeDependency`| DAG file re-parsing             |
| Persistence               | In-memory                          | Serialized to metadata DB       |

---

## 13. Cron Expression Parsing Libraries

### 13.1 Overview

The simulation's `CronParser` implements a simplified 5-field cron parser that
handles `*` and literal values for minute, hour, day-of-month, month, and
day-of-week. Production systems require richer expression support.

### 13.2 Quartz CronExpression (Java Standard)

Quartz Scheduler's `CronExpression` is the de facto standard for cron parsing in
Java. It supports 6-7 fields (including seconds and optional year).

```java
import org.quartz.CronExpression;

// Quartz 7-field: seconds minute hour dayOfMonth month dayOfWeek [year]
CronExpression expr = new CronExpression("0 30 2 * * ?");

// Get next fire time after now
Date nextFire = expr.getNextValidTimeAfter(new Date());

// Validate expression
boolean valid = CronExpression.isValidExpression("0 */5 * * * ?");
```

**Quartz field capabilities:**
| Field        | Allowed Values  | Special Characters           |
|--------------|-----------------|------------------------------|
| Seconds      | 0-59            | , - * /                      |
| Minutes      | 0-59            | , - * /                      |
| Hours        | 0-23            | , - * /                      |
| Day of Month | 1-31            | , - * ? / L W                |
| Month        | 1-12 or JAN-DEC| , - * /                      |
| Day of Week  | 1-7 or SUN-SAT | , - * ? / L #                |
| Year         | 1970-2099       | , - * / (optional)           |

### 13.3 Simulation vs. Quartz Comparison

| Feature                      | Simulation CronParser              | Quartz CronExpression         |
|------------------------------|------------------------------------|-------------------------------|
| Fields                       | 5 (minute to dayOfWeek)            | 6-7 (seconds to optional year)|
| Wildcards                    | `*` only                           | `*`, `?`                      |
| Ranges                       | Not supported                      | `1-5`, `MON-FRI`             |
| Steps                        | Not supported                      | `*/5`, `0/15`                |
| Lists                        | Not supported                      | `1,3,5`                      |
| Last day of month            | Not supported                      | `L`                          |
| Nth weekday                  | Not supported                      | `#` (e.g., `2#3` = 3rd Monday)|
| Next fire calculation        | Minute-by-minute scan (max 24h)   | Efficient calendar arithmetic |
| Thread safety                | Stateless (safe)                   | Stateless (safe)              |
| Performance                  | O(1440) worst case per fire calc   | O(1) calendar arithmetic      |

### 13.4 cron-utils Library (Alternative)

```java
import com.cronutils.model.Cron;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

// Define cron format (Unix 5-field)
CronDefinition def = CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX);
CronParser parser = new CronParser(def);

// Parse and compute next execution
Cron cron = parser.parse("30 2 * * 1-5");
ExecutionTime execTime = ExecutionTime.forCron(cron);
Optional<ZonedDateTime> nextExec = execTime.nextExecution(ZonedDateTime.now());
```

### 13.5 Spring @Scheduled (Framework Integration)

```java
// Spring Boot cron scheduling
@Component
public class CronTaskScheduler {

    @Scheduled(cron = "0 30 2 * * MON-FRI")
    public void runWeekdayReport() {
        // Executes at 2:30 AM on weekdays
    }

    @Scheduled(fixedRate = 60000)
    public void pollForDueTasks() {
        // Fixed-rate polling for delayed tasks
    }
}
```

### 13.6 Production Cron Architecture

```
+-------------------+
| Cron Schedule DB  |  (PostgreSQL: cron_schedules table)
|  task_id          |
|  cron_expression  |
|  next_fire_time   |  <-- pre-computed, indexed
|  last_fire_time   |
|  is_active        |
+--------+----------+
         |
         v
+-------------------+
| Cron Scanner      |  (runs every minute on leader node)
|                   |
|  1. SELECT tasks WHERE next_fire_time <= NOW() AND is_active
|  2. For each due task:
|     a. Enqueue task into TaskQueue (Redis ZSET)
|     b. Compute next fire time (Quartz CronExpression)
|     c. UPDATE cron_schedules SET next_fire_time = ?, last_fire_time = NOW()
|  3. Commit transaction
+-------------------+
```

**Why pre-compute next_fire_time:**
- The simulation's `CronParser.getNextFireTime()` scans minute-by-minute (O(1440))
- Production pre-computes the next fire time and stores it in an indexed column
- The cron scanner query is O(log n) on the index, not O(tasks * 1440)

### 13.7 Timezone Handling

```java
// Simulation: UTC only
ZonedDateTime current = after.atZone(ZoneOffset.UTC);

// Production: user-specified timezone per cron schedule
public Instant getNextFireTime(CronSchedule schedule, Instant after) {
    ZoneId zone = ZoneId.of(schedule.getTimezone());  // e.g., "America/New_York"
    ZonedDateTime current = after.atZone(zone);
    // Quartz CronExpression handles DST transitions correctly
    CronExpression expr = new CronExpression(schedule.getExpression());
    expr.setTimeZone(TimeZone.getTimeZone(zone));
    return expr.getNextValidTimeAfter(Date.from(after)).toInstant();
}
```

### 13.8 Cron Anti-Patterns

| Anti-Pattern                          | Problem                               | Solution                              |
|---------------------------------------|---------------------------------------|---------------------------------------|
| Midnight stampede (`0 0 * * *`)       | All tasks fire at midnight            | Spread with random minute offset      |
| No overlap protection                 | Cron fires while previous run active  | Check `last_fire_time` status before enqueue |
| Hardcoded timezone                    | DST causes missed/double fires        | Use Quartz with explicit timezone     |
| No catch-up after downtime            | Missed fires during maintenance       | Track `last_fire_time`, fire missed runs |
| Minute-scan for next fire time        | O(1440) per computation               | Pre-compute with Quartz calendar math |

---

## 14. Simulation-to-Production Mapping

### 14.1 Complete Mapping Table

This section provides the authoritative mapping from every simulation class to its
production technology counterpart.

#### 14.1.1 Engine Layer

| Simulation Class       | Production Technology              | Notes                                     |
|------------------------|------------------------------------|-------------------------------------------|
| `TaskQueue`            | Redis ZSET (`scheduler:task_queue`)| PriorityQueue -> ZADD/ZPOPMIN with score  |
| `DependencyResolver`   | PostgreSQL `task_dependencies` + Redis cache | HashMap -> DB table + cache      |
| `CronParser`           | Quartz `CronExpression` or cron-utils | Minute scan -> calendar arithmetic    |
| `SchedulerEngine`      | Leader-elected scheduler process   | Single-threaded loop -> distributed engine|

#### 14.1.2 Repository Layer

| Simulation Class              | Production Technology              | Notes                               |
|-------------------------------|------------------------------------|--------------------------------------|
| `InMemoryTaskRepository`      | PostgreSQL `tasks` table + Redis cache | ConcurrentHashMap -> ACID rows   |
| `InMemoryExecutionRepository` | PostgreSQL `task_executions` table  | HashMap -> partitioned table         |
| `InMemoryWorkerRepository`    | PostgreSQL `workers` table + Redis | HashMap -> DB + heartbeat cache      |
| `InMemorySchedulerNodeRepository` | ZooKeeper ephemeral znodes     | HashMap -> ZK session management     |

#### 14.1.3 Service Layer

| Simulation Class         | Production Technology                    | Notes                                |
|--------------------------|------------------------------------------|--------------------------------------|
| `TaskService`            | gRPC `SchedulerService` + PostgreSQL     | Direct calls -> RPC + DB transactions|
| `WorkerService`          | gRPC worker registration + Redis registry| Object method -> network RPC         |
| `LeaderElectionService`  | ZooKeeper LeaderLatch / etcd election    | Bully algorithm -> ZK recipe         |
| `FailoverService`        | Heartbeat timeout + task reassignment    | In-process -> distributed detection  |
| `ExecutionService`       | Kafka events + PostgreSQL writes         | Direct writes -> event-sourced       |
| `SchedulerService`       | Orchestration layer above all services   | Facade over distributed components   |
| `MonitoringService`      | Prometheus metrics + Grafana dashboards  | println -> metric counters/histograms|

#### 14.1.4 Strategy Layer

| Simulation Class                    | Production Technology                       | Notes                          |
|-------------------------------------|---------------------------------------------|--------------------------------|
| `ExponentialBackoffRetryStrategy`   | Same algorithm, Kafka retry topics          | In-process delay -> event delay|
| `FixedIntervalRetryStrategy`        | Same algorithm, simple delay mechanism      | Configurable per task          |
| `RoundRobinAssignmentStrategy`      | gRPC + load balancer (round-robin)          | In-memory -> network dispatch  |
| `LeastLoadedAssignmentStrategy`     | Worker load metrics from Prometheus/Redis   | Local load -> distributed load |
| `ImmediateSchedulingStrategy`       | Direct enqueue to Redis ZSET                | Same semantics                 |
| `DelayedSchedulingStrategy`         | Redis delayed queue (ZSET with time score)  | Thread.sleep -> scheduled score|
| `CronSchedulingStrategy`            | Quartz + PostgreSQL `cron_schedules`        | CronParser -> Quartz           |

#### 14.1.5 Model Layer

| Simulation Class    | Production Storage                          | Notes                              |
|---------------------|---------------------------------------------|------------------------------------|
| `Task`              | PostgreSQL `tasks` row + Redis cache        | POJO -> serialized row             |
| `Worker`            | PostgreSQL `workers` row + Redis heartbeat  | POJO -> registered worker entry    |
| `TaskExecution`     | PostgreSQL `task_executions` row            | POJO -> partitioned history row    |
| `SchedulerNode`     | ZooKeeper znode + PostgreSQL row            | POJO -> ephemeral coordination node|
| `CronSchedule`      | PostgreSQL `cron_schedules` row             | POJO -> indexed row                |
| `TaskGroup`         | Implicit via `group_id` column in tasks     | Object -> FK/column group          |
| `TaskDependency`    | PostgreSQL `task_dependencies` row          | POJO -> junction table row         |
| `RetryPolicy`       | Task-level configuration in `tasks.payload` | POJO -> JSON field                 |

### 14.2 Data Flow: Simulation vs. Production

**Simulation data flow:**
```
DistributedTaskSchedulerApp.main()
  -> SchedulerController.runDemo()
     -> TaskService.createTask()          [saves to ConcurrentHashMap]
     -> SchedulerEngine.tick()            [reads from PriorityQueue]
     -> DependencyResolver.getReadyTasks() [checks HashMap]
     -> Worker executes task              [direct method call]
     -> ExecutionService.recordResult()   [saves to HashMap]
     -> MonitoringService.printStats()    [System.out.println]
```

**Production data flow:**
```
Client -> gRPC SchedulerService
  -> PostgreSQL INSERT task              [ACID write]
  -> Redis ZADD task_queue               [priority enqueue]
  -> Kafka PRODUCE task.submitted event  [audit]
  -> Scheduler Engine (leader node)
     -> Redis ZPOPMIN                    [dequeue highest priority]
     -> PostgreSQL SELECT dependencies   [or Redis cache]
     -> DependencyResolver.getReadyTasks()
     -> gRPC push to Worker              [network dispatch]
     -> Worker executes task
     -> gRPC report result
     -> PostgreSQL INSERT execution      [record history]
     -> Kafka PRODUCE task.completed     [event stream]
     -> Prometheus metrics update        [monitoring]
```

### 14.3 What the Simulation Simplifies

| Production Concern                 | Simulation Approach                        |
|------------------------------------|--------------------------------------------|
| Network partitions                 | All in-process, no network                 |
| Clock skew                         | Single JVM clock                           |
| Serialization overhead             | Direct object references                   |
| Concurrent access                  | ConcurrentHashMap (good enough)            |
| Disk I/O                           | All in-memory                              |
| Schema migrations                  | No database                                |
| Authentication/authorization       | None                                       |
| Rate limiting                      | None                                       |
| Encryption in transit              | None                                       |
| Multi-region replication           | None                                       |

### 14.4 What the Simulation Gets Right

| Production Concern                 | Simulation Approach                        |
|------------------------------------|--------------------------------------------|
| Priority ordering                  | Correct comparator semantics               |
| DAG cycle detection                | DFS three-coloring (textbook correct)      |
| Topological sort                   | Kahn's algorithm (textbook correct)        |
| Bully leader election              | Full algorithm with node priority           |
| Exponential backoff with jitter    | Correct formula with +-10% jitter          |
| Worker failover detection          | Heartbeat timeout + task reassignment      |
| State machine transitions          | Valid transition checking                  |
| Builder pattern for tasks          | Clean immutable construction               |
| Strategy pattern for pluggability  | Assignment, retry, scheduling strategies   |

---

## 15. Technology Selection Matrix

### 15.1 Decision Framework

When selecting technologies for a distributed task scheduler, evaluate along
these dimensions:

| Dimension           | Weight | Description                                      |
|---------------------|--------|--------------------------------------------------|
| Consistency         | High   | Task assignment must be exactly-once             |
| Availability        | High   | Scheduler downtime = tasks not running           |
| Throughput          | Medium | Tasks/second capacity                            |
| Latency             | Medium | Time from submission to execution start          |
| Operational cost    | Medium | Team expertise, maintenance burden               |
| Scalability         | Medium | Ability to add workers and schedulers            |

### 15.2 Small Scale (< 1K tasks/day)

```
Queue:         PostgreSQL (poll-based with SKIP LOCKED)
Coordination:  Single scheduler (no leader election needed)
Persistence:   PostgreSQL (single DB for everything)
Monitoring:    Application logs + simple health checks
Communication: REST API
Cron:          Spring @Scheduled
```

### 15.3 Medium Scale (1K - 100K tasks/day)

```
Queue:         Redis ZSET
Coordination:  ZooKeeper / etcd (3-node cluster)
Persistence:   PostgreSQL (primary + read replica)
Messaging:     Kafka (3-broker cluster)
Monitoring:    Prometheus + Grafana
Communication: gRPC
Cron:          Quartz Scheduler
```

### 15.4 Large Scale (100K+ tasks/day)

```
Queue:         Redis Cluster (6+ nodes) + Kafka for overflow
Coordination:  etcd (5-node cluster, Kubernetes-native)
Persistence:   PostgreSQL (partitioned) or Cassandra for execution history
Messaging:     Kafka (multi-partition, multi-consumer-group)
Monitoring:    Prometheus + Thanos (long-term storage) + Grafana
Communication: gRPC with Envoy service mesh
Cron:          Quartz cluster mode or Temporal.io
Workflow:      Temporal.io (replaces custom DAG engine)
```

### 15.5 When to Build vs. Buy

| Scenario                                          | Build Custom     | Use Temporal/Airflow |
|---------------------------------------------------|------------------|----------------------|
| Simple cron + queue + retry                       | Good fit         | Over-engineered      |
| Complex DAG workflows with branching              | Significant work | Excellent fit        |
| Need fine-grained priority control                | Full control     | Limited              |
| Team has strong distributed systems experience    | Feasible         | Faster to start      |
| Regulatory requirement for on-prem                | Necessary        | Temporal self-hosted |
| Already running Kubernetes                        | Use Argo/Temporal| Good ecosystem fit   |
| Interview / learning project                      | The whole point  | N/A                  |

### 15.6 Technology Compatibility Matrix

```
                 ZK    etcd   Kafka  Redis  PG    Airflow  Temporal  gRPC
ZooKeeper        --    alt    yes    yes    yes   yes      no        yes
etcd             alt   --     yes    yes    yes   no       yes       yes
Kafka            yes   yes    --     yes    yes   yes      yes       yes
Redis            yes   yes    yes    --     yes   yes      no        yes
PostgreSQL       yes   yes    yes    yes    --    yes      yes       yes
Airflow          yes   no     yes    yes    yes   --       no        no
Temporal         no    yes    yes    no     yes   no       --        yes
gRPC             yes   yes    yes    yes    yes   no       yes       --

yes = commonly used together
alt = alternative to each other
no  = not typically combined
```

---

## Appendix A: Version Recommendations

| Technology      | Recommended Version | EOL/LTS Notes                          |
|-----------------|--------------------|-----------------------------------------|
| ZooKeeper       | 3.9.x              | Stable, widely deployed                 |
| etcd            | 3.5.x              | Kubernetes standard                     |
| Kafka           | 3.7.x (KRaft)      | KRaft mode removes ZK dependency        |
| Redis           | 7.2.x              | Redis Stack for modules                 |
| PostgreSQL      | 16.x               | 5-year support cycle                    |
| Airflow         | 2.9.x              | HA scheduler, timetable API             |
| Temporal        | 1.24.x             | Self-hosted or cloud                    |
| gRPC            | 1.64.x             | Java, Go, Python stubs                  |
| Prometheus      | 2.53.x             | OpenMetrics compatible                  |
| Grafana         | 11.x               | OSS or Enterprise                       |
| Quartz          | 2.5.x              | Cron expression parsing                 |
| Micrometer      | 1.13.x             | Spring Boot native integration          |
| HikariCP        | 5.1.x              | Connection pooling standard             |

## Appendix B: Dependency Diagram

```
                        +-----------+
                        | gRPC API  |
                        +-----+-----+
                              |
              +---------------+---------------+
              |               |               |
        +-----v-----+  +-----v-----+  +------v------+
        | Scheduler  |  |   Task    |  |   Worker    |
        | Engine     |  |  Service  |  |   Service   |
        +-----+------+  +-----+----+  +------+------+
              |               |               |
    +---------+-----+---------+------+--------+
    |               |                |
+---v---+   +------v------+  +------v------+
| Redis |   | PostgreSQL  |  | ZooKeeper   |
| ZSET  |   | (tasks,     |  | / etcd      |
| Queue |   |  executions,|  | (leader     |
|       |   |  workers)   |  |  election)  |
+---+---+   +------+------+  +------+------+
    |               |                |
    +-------+-------+----------------+
            |
     +------v------+
     |    Kafka     |
     | (events,     |
     |  audit,      |
     |  dead letter)|
     +------+------+
            |
     +------v------+
     | Prometheus  |
     | + Grafana   |
     +-------------+
```

## Appendix C: Further Reading

1. **Designing Data-Intensive Applications** (Martin Kleppmann) -- Ch. 9: Consistency and Consensus
2. **ZooKeeper: Distributed Process Coordination** (Flavio Junqueira, Benjamin Reed)
3. **Kafka: The Definitive Guide** (Gwen Shapira et al.)
4. **Redis in Action** (Josiah Carlson) -- Ch. 6: Application Components in Redis
5. **Temporal.io documentation** -- https://docs.temporal.io/
6. **Apache Airflow documentation** -- https://airflow.apache.org/docs/
7. **gRPC documentation** -- https://grpc.io/docs/
8. **Quartz Scheduler documentation** -- http://www.quartz-scheduler.org/
