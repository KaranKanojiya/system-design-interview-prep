# Caching Strategy: Distributed Task Scheduler

## Table of Contents

1. [Overview](#1-overview)
2. [What to Cache](#2-what-to-cache)
3. [What NOT to Cache](#3-what-not-to-cache)
4. [Multi-Level Caching Architecture](#4-multi-level-caching-architecture)
5. [Cache Invalidation Strategies](#5-cache-invalidation-strategies)
6. [Queue as Cache — TaskQueue Pattern](#6-queue-as-cache--taskqueue-pattern)
7. [Worker Registry Caching](#7-worker-registry-caching)
8. [Cache Warming on Startup](#8-cache-warming-on-startup)
9. [Cache Failure Handling](#9-cache-failure-handling)
10. [Metrics Caching](#10-metrics-caching)
11. [Cache Decision Matrix](#11-cache-decision-matrix)
12. [Simulation-to-Production Cache Mapping](#12-simulation-to-production-cache-mapping)

---

## 1. Overview

The distributed task scheduler operates on a mix of data with widely varying
access patterns: task definitions are read-heavy and rarely change, task status
is updated on every state transition, worker registry data changes with
heartbeats, and execution records are write-once-read-rarely.

A well-designed caching strategy must handle these different access patterns
without introducing stale data in the critical path (task assignment and
state transitions).

### Design Principles

1. **Cache the stable, not the volatile** -- task definitions and DAG structure
   change rarely and benefit enormously from caching. Task state transitions
   must not be cached because staleness causes double-assignment.

2. **The queue IS a cache** -- the `TaskQueue` (PriorityQueue / Redis ZSET)
   already caches the set of ready-to-dispatch tasks. This is the most
   important "cache" in the system.

3. **Fail open, not closed** -- cache misses must fall through to the source
   of truth (PostgreSQL). A cache failure should degrade performance, not
   correctness.

4. **Invalidate aggressively on the write path** -- when a task definition
   changes or a worker goes dead, the cache must be invalidated immediately,
   not after a TTL expires.

### Access Pattern Summary

```
+----------------------+----------+----------+----------+------------------+
| Data                 | Reads/s  | Writes/s | Staleness| Cache Strategy   |
+----------------------+----------+----------+----------+------------------+
| Task definitions     | 1000+    | 1-10     | Minutes  | L1+L2, long TTL  |
| Task status          | 500+     | 100+     | 0 (none) | Write-through L2 |
| Worker registry      | 100+     | 10-20    | Seconds  | L2, heartbeat inv|
| DAG structure        | 50+      | 1-5      | Minutes  | L1+L2, on-change |
| Cron schedules       | 10       | <1       | Minutes  | L1, long TTL     |
| Execution records    | 10       | 100+     | N/A      | Do NOT cache     |
| Execution metrics    | 20       | 20       | Seconds  | L2, short TTL    |
| Leader state         | 5        | <1       | 0 (none) | Do NOT cache     |
+----------------------+----------+----------+----------+------------------+
```

---

## 2. What to Cache

### 2.1 Task Definitions

**Why cache:** Task definitions (name, type, priority, payload, maxRetries,
timeoutMillis) are created once and read many times. Every scheduler tick
reads task definitions when resolving dependencies, checking retry eligibility,
and dispatching to workers.

**Access pattern:** Write-once, read-many. A task definition might be created
once but read hundreds of times during its lifecycle (dependency checks,
status display, monitoring queries, retry decisions).

**Cache location:** L1 (JVM local) + L2 (Redis hash)

**TTL:** 10 minutes (L1), 1 hour (L2)

**Invalidation:** Event-driven on task update (rare)

```java
// L1: Caffeine in-process cache for task definitions
Cache<String, Task> taskDefCache = Caffeine.newBuilder()
    .maximumSize(10_000)               // cap memory usage
    .expireAfterWrite(10, TimeUnit.MINUTES) // TTL for staleness bound
    .recordStats()                     // expose hit rate to Prometheus
    .build();

// Read path: L1 -> L2 -> PostgreSQL
public Task getTaskDefinition(String taskId) {
    // 1. Check L1 (JVM local, ~100ns)
    Task cached = taskDefCache.getIfPresent(taskId);
    if (cached != null) return cached;

    // 2. Check L2 (Redis, ~1ms)
    Map<String, String> redisData = redis.hgetAll("task:def:" + taskId);
    if (!redisData.isEmpty()) {
        Task task = deserialize(redisData);
        taskDefCache.put(taskId, task);  // backfill L1
        return task;
    }

    // 3. Fall through to PostgreSQL (~5ms)
    Task task = taskRepository.findById(taskId).orElseThrow();
    redis.hset("task:def:" + taskId, serialize(task));  // backfill L2
    redis.expire("task:def:" + taskId, 3600);           // 1h TTL
    taskDefCache.put(taskId, task);                      // backfill L1
    return task;
}
```

**Simulation equivalent:** `InMemoryTaskRepository` backed by `ConcurrentHashMap`
is effectively an L1 cache with no TTL and no persistence layer behind it.

### 2.2 Worker Registry

**Why cache:** The scheduler checks worker availability on every task dispatch.
With 50+ workers and 100+ dispatches per second, querying PostgreSQL for worker
status on every dispatch is too expensive.

**Access pattern:** Read-heavy (every dispatch), moderate writes (heartbeats
every 5 seconds per worker, status changes on load change).

**Cache location:** L2 (Redis hash per worker)

**TTL:** 60 seconds (refreshed by heartbeat)

**Invalidation:** Heartbeat-based (each heartbeat refreshes the cache entry)
plus event-driven on status change (ACTIVE -> DEAD).

```java
// Redis hash for worker registry
// Key: worker:registry:<workerId>
// Fields: hostname, port, capacity, currentLoad, status, lastHeartbeat

// Worker heartbeat refreshes cache
public void processHeartbeat(String workerId, int currentLoad, WorkerStatus status) {
    String key = "worker:registry:" + workerId;

    // 1. Update Redis cache atomically
    redis.hset(key, Map.of(
        "currentLoad", String.valueOf(currentLoad),
        "status", status.name(),
        "lastHeartbeat", String.valueOf(Instant.now().toEpochMilli())
    ));
    redis.expire(key, 60);  // auto-expire if heartbeat stops

    // 2. Async write to PostgreSQL (eventual consistency is fine for workers)
    asyncExecutor.submit(() -> {
        workerRepository.updateHeartbeat(workerId, currentLoad, status);
    });
}

// Get available workers for task assignment (hot path)
public List<Worker> getAvailableWorkers() {
    // 1. Read from Redis (all worker keys matching pattern)
    Set<String> workerKeys = redis.keys("worker:registry:*");

    List<Worker> available = new ArrayList<>();
    for (String key : workerKeys) {
        Map<String, String> data = redis.hgetAll(key);
        if ("ACTIVE".equals(data.get("status"))) {
            int load = Integer.parseInt(data.get("currentLoad"));
            int capacity = Integer.parseInt(data.get("capacity"));
            if (load < capacity) {
                available.add(deserializeWorker(data));
            }
        }
    }
    return available;
}
```

**Simulation equivalent:** `InMemoryWorkerRepository` with `ConcurrentHashMap`.
`Worker.isAvailable()` checks `status == ACTIVE && currentLoad < capacity`
directly in memory.

### 2.3 Cron Schedules

**Why cache:** Cron schedules are checked every minute (or every scheduler tick)
to determine which tasks need to fire. The schedule definitions rarely change.

**Access pattern:** Read on every tick (once per minute per schedule), writes
only on schedule creation or modification (very rare).

**Cache location:** L1 (JVM local map of all active schedules)

**TTL:** 30 minutes (or until explicit invalidation)

**Invalidation:** On schedule creation, update, or deactivation.

```java
// L1 cache: all active cron schedules loaded into memory
private Map<String, CronScheduleEntry> cronCache = new ConcurrentHashMap<>();

// Loaded on startup, refreshed on change events
public void refreshCronCache() {
    List<CronScheduleEntry> active = cronScheduleRepository.findAllActive();
    Map<String, CronScheduleEntry> fresh = new HashMap<>();
    for (CronScheduleEntry entry : active) {
        fresh.put(entry.getTaskId(), entry);
    }
    cronCache = new ConcurrentHashMap<>(fresh);
    log.info("Cron cache refreshed: {} active schedules", fresh.size());
}

// Scheduler tick: check all cached schedules
public List<String> getDueCronTasks(Instant now) {
    List<String> dueTasks = new ArrayList<>();
    for (CronScheduleEntry entry : cronCache.values()) {
        if (entry.getNextFireTime().isBefore(now) || entry.getNextFireTime().equals(now)) {
            dueTasks.add(entry.getTaskId());
        }
    }
    return dueTasks;
}
```

**Simulation equivalent:** `CronParser` and `CronSchedule` are stateless. The
`SchedulerEngine` holds cron-related state in memory and calls
`CronParser.getNextFireTime()` on each tick.

### 2.4 DAG Structure

**Why cache:** DAG dependency resolution (`DependencyResolver.getReadyTasks()`)
is called on every scheduler tick. The dependency graph changes only when new
DAG tasks are submitted, which is far less frequent than scheduling ticks.

**Access pattern:** Read on every tick, write on task/dependency creation only.

**Cache location:** L1 (JVM in-memory graph, exactly like the simulation's
`DependencyResolver`) + L2 (Redis for sharing across scheduler failover).

**TTL:** No TTL (invalidated on change)

**Invalidation:** On `addDependency()` or `removeDependency()` calls.

```java
// L1: In-memory DependencyResolver (identical to simulation)
// This IS the cache — the simulation's HashMap<String, Set<String>> is the
// production pattern for a single scheduler node.

// L2: Redis backup for failover recovery
public void addDependency(String taskId, String dependsOn) {
    // 1. Update L1 in-memory graph
    dependencyResolver.addDependency(taskId, dependsOn);

    // 2. Persist to PostgreSQL (source of truth)
    dependencyRepository.save(new TaskDependency(taskId, dependsOn));

    // 3. Update Redis backup (for new leader after failover)
    redis.sadd("dag:deps:" + taskId, dependsOn);
}

// On leader failover: rebuild L1 from Redis (fast) or PostgreSQL (slower)
public void rebuildDependencyGraph() {
    Set<String> dagKeys = redis.keys("dag:deps:*");
    if (!dagKeys.isEmpty()) {
        // Fast path: rebuild from Redis
        for (String key : dagKeys) {
            String taskId = key.substring("dag:deps:".length());
            Set<String> deps = redis.smembers(key);
            for (String dep : deps) {
                dependencyResolver.addDependency(taskId, dep);
            }
        }
    } else {
        // Slow path: rebuild from PostgreSQL
        List<TaskDependency> allDeps = dependencyRepository.findAll();
        for (TaskDependency dep : allDeps) {
            dependencyResolver.addDependency(dep.getTaskId(), dep.getDependsOnId());
        }
    }
}
```

**Simulation equivalent:** `DependencyResolver` with `HashMap<String, Set<String>>`
is already a pure in-memory cache of the dependency graph.

### 2.5 Task Status (Hot Path)

**Why cache:** Task status is read on every dependency check (`getReadyTasks`
needs to know which tasks are COMPLETED) and on every monitoring/display query.

**Access pattern:** Read-heavy AND write-heavy. Status changes on every state
transition (PENDING -> QUEUED -> ASSIGNED -> RUNNING -> COMPLETED).

**Cache location:** L2 (Redis) with write-through from PostgreSQL

**TTL:** No TTL (write-through keeps it current)

**Invalidation:** Write-through (every status change writes to both Redis and
PostgreSQL atomically).

```java
// Write-through: update cache AND database on every state transition
public boolean transitionStatus(String taskId, TaskStatus from, TaskStatus to) {
    // 1. Atomic PostgreSQL update with optimistic lock
    boolean success = taskRepository.updateStatus(taskId, from, to);
    if (!success) {
        return false;  // another node already transitioned this task
    }

    // 2. Write-through to Redis (best effort — PostgreSQL is authoritative)
    try {
        redis.hset("task:status:" + taskId, "status", to.name());
        redis.hset("task:status:" + taskId, "updatedAt",
                    String.valueOf(Instant.now().toEpochMilli()));
    } catch (Exception e) {
        log.warn("Redis write-through failed for task {}, will self-heal on next read", taskId);
        // Cache will be corrected on next read (read-through from PostgreSQL)
    }

    return true;
}

// Read: prefer Redis, fall through to PostgreSQL
public TaskStatus getTaskStatus(String taskId) {
    // 1. Read from Redis (~1ms)
    String cachedStatus = redis.hget("task:status:" + taskId, "status");
    if (cachedStatus != null) {
        return TaskStatus.valueOf(cachedStatus);
    }

    // 2. Fall through to PostgreSQL (~5ms) and backfill cache
    Task task = taskRepository.findById(taskId).orElseThrow();
    redis.hset("task:status:" + taskId, "status", task.getStatus().name());
    return task.getStatus();
}
```

**Simulation equivalent:** `Task.updateStatus()` modifies the in-memory object
directly. The `ConcurrentHashMap` in `InMemoryTaskRepository` holds the current
status with zero latency.

### 2.6 Execution Metrics (Aggregated)

**Why cache:** Dashboard queries for metrics like "tasks completed in last hour"
or "average execution time" should not hit PostgreSQL on every refresh. Pre-
computed aggregates with short TTL are ideal.

**Access pattern:** Read by monitoring dashboards (every 10-30 seconds), written
by aggregation jobs (every 30-60 seconds).

**Cache location:** L2 (Redis hash with aggregated values)

**TTL:** 30-60 seconds

```java
// Aggregated metrics cache
public class MetricsCache {

    private static final String METRICS_KEY = "scheduler:metrics:aggregate";
    private static final int TTL_SECONDS = 30;

    private final Jedis redis;
    private final ExecutionRepository execRepo;

    // Periodic aggregation job (runs every 30 seconds)
    public void refreshMetrics() {
        long now = Instant.now().toEpochMilli();
        long oneHourAgo = now - 3_600_000;

        // Compute aggregates from PostgreSQL
        int completedLastHour = execRepo.countByStatusSince(TaskStatus.COMPLETED, oneHourAgo);
        int failedLastHour = execRepo.countByStatusSince(TaskStatus.FAILED, oneHourAgo);
        double avgDurationMs = execRepo.averageDurationSince(oneHourAgo);
        int currentQueueDepth = taskQueue.size();
        int activeWorkers = workerService.getAvailableWorkers().size();

        // Write aggregates to Redis
        redis.hset(METRICS_KEY, Map.of(
            "completedLastHour", String.valueOf(completedLastHour),
            "failedLastHour", String.valueOf(failedLastHour),
            "avgDurationMs", String.valueOf(avgDurationMs),
            "queueDepth", String.valueOf(currentQueueDepth),
            "activeWorkers", String.valueOf(activeWorkers),
            "computedAt", String.valueOf(now)
        ));
        redis.expire(METRICS_KEY, TTL_SECONDS);
    }

    // Dashboard reads from cache (fast)
    public SchedulerMetrics getMetrics() {
        Map<String, String> cached = redis.hgetAll(METRICS_KEY);
        if (cached.isEmpty()) {
            refreshMetrics();  // cold start: compute synchronously
            cached = redis.hgetAll(METRICS_KEY);
        }
        return SchedulerMetrics.from(cached);
    }
}
```

**Simulation equivalent:** `MonitoringService` and `SchedulerStatsDisplay`
compute metrics on-the-fly from in-memory data structures. No caching is needed
because all data is already in-process.

---

## 3. What NOT to Cache

### 3.1 Task State Transitions (Authoritative Path)

**Why not cache:** Task state transitions are the critical path for correctness.
Caching the "current state" in a way that allows two scheduler nodes to read
stale state would cause double-assignment (two workers executing the same task).

**Risk of caching:**
```
Timeline:
  t=0  Node A reads task status from cache: QUEUED
  t=1  Node B reads task status from cache: QUEUED  (stale!)
  t=2  Node A transitions task to ASSIGNED, updates cache
  t=3  Node B transitions task to ASSIGNED (thinks it's still QUEUED)
  Result: Task assigned to TWO workers -> duplicate execution
```

**Correct approach:** Use PostgreSQL optimistic locking for state transitions.
The `UPDATE ... WHERE status = ? AND version = ?` query is the serialization
point. Redis task status caching (Section 2.5) is used for READ queries only;
the WRITE path always goes through PostgreSQL first.

```java
// WRONG: cache-based state transition
public void assignTask(String taskId) {
    String status = redis.hget("task:status:" + taskId, "status");
    if ("QUEUED".equals(status)) {
        redis.hset("task:status:" + taskId, "status", "ASSIGNED"); // RACE CONDITION
    }
}

// CORRECT: database-authoritative state transition
public boolean assignTask(String taskId) {
    // PostgreSQL is the single source of truth for transitions
    boolean success = taskRepository.updateStatus(taskId, TaskStatus.QUEUED, TaskStatus.ASSIGNED);
    if (success) {
        // Only update cache AFTER successful DB write
        redis.hset("task:status:" + taskId, "status", "ASSIGNED");
    }
    return success;
}
```

### 3.2 Execution Records (Write-Heavy)

**Why not cache:** Task execution records (`TaskExecution`) are written once
when a task starts and updated once when it finishes. They are queried
infrequently (monitoring dashboards, debugging). The write-to-read ratio
is extremely high, making caching wasteful.

**Numbers:**
- Writes: 2 per task (start + complete) = 200/s at 100 tasks/s
- Reads: dashboard queries = 1-2/minute
- Cache hit rate would be < 1% (almost all cache entries expire before being read)

**Better approach:** Aggregate metrics (Section 2.6) provide the dashboard data.
Individual execution records are queried only for debugging and can tolerate
PostgreSQL latency.

### 3.3 Leader Election State

**Why not cache:** Leader election state must come from the coordination service
(ZooKeeper/etcd) directly. Caching "who is the leader" risks operating under
a stale leader identity after a failover.

**Risk of caching:**
```
Timeline:
  t=0  Node A is leader. All nodes cache: leader = "node-A"
  t=1  Node A crashes
  t=2  ZooKeeper elects Node B as new leader
  t=3  Node C still reads cached leader = "node-A"
  t=4  Node C sends task assignment request to dead Node A
  Result: Task lost until cache TTL expires
```

**Correct approach:** Always query ZooKeeper/etcd for leader identity, or use
watch-based notification to update local state immediately on leader change.

```java
// WRONG: cached leader with TTL
private String cachedLeaderId;
private long cachedAt;
private static final long LEADER_CACHE_TTL_MS = 5000;

public String getLeaderId() {
    if (System.currentTimeMillis() - cachedAt < LEADER_CACHE_TTL_MS) {
        return cachedLeaderId;  // STALE for up to 5 seconds after failover
    }
    cachedLeaderId = zookeeper.getLeader();
    cachedAt = System.currentTimeMillis();
    return cachedLeaderId;
}

// CORRECT: watch-based leader tracking
private volatile String currentLeaderId;

public void startLeaderWatch() {
    leaderLatch.addListener(new LeaderLatchListener() {
        @Override
        public void isLeader() {
            currentLeaderId = myNodeId;
        }

        @Override
        public void notLeader() {
            currentLeaderId = leaderLatch.getLeader().getId();
        }
    });
}

public String getLeaderId() {
    return currentLeaderId;  // always current via watch callbacks
}
```

### 3.4 Distributed Lock State

**Why not cache:** Lock ownership must be verified in real-time. Caching "I hold
the lock" without checking the lock service can lead to split-brain scenarios
where two nodes believe they hold the same lock.

### 3.5 Summary: Cache Exclusion Rules

| Data Type              | Reason for Exclusion                                        |
|------------------------|-------------------------------------------------------------|
| State transitions      | Double-assignment risk from stale reads                     |
| Execution records      | Write-heavy, low read frequency, cache waste                |
| Leader election        | Stale leader causes task routing failures                   |
| Lock state             | Split-brain risk from cached lock ownership                 |
| Retry attempt count    | Must be authoritative to prevent infinite retries           |
| Task version numbers   | Optimistic lock field, must be current                      |

---

## 4. Multi-Level Caching Architecture

### 4.1 Architecture Overview

```
+------------------------------------------------------+
|                  Scheduler Node (JVM)                 |
|                                                       |
|  +----------------+     +-------------------------+   |
|  | L1: Caffeine   |     | In-Memory Structures    |   |
|  | (task defs,    |     | (DependencyResolver,    |   |
|  |  cron sched,   |     |  TaskQueue, completed   |   |
|  |  DAG structure)|     |  set)                   |   |
|  +-------+--------+     +------------+------------+   |
|          |                           |                |
+------------------------------------------------------+
           | miss                      | read/write
           v                           v
+------------------------------------------------------+
|                  L2: Redis Cluster                    |
|                                                       |
|  +------------+  +-------------+  +---------------+   |
|  | task:def:* |  | task:status:|  | worker:reg:*  |   |
|  | (hashes)   |  | (hashes)    |  | (hashes)      |   |
|  +------------+  +-------------+  +---------------+   |
|  +------------+  +-------------+  +---------------+   |
|  | dag:deps:* |  | metrics:agg |  | cron:cache    |   |
|  | (sets)     |  | (hash)      |  | (hash)        |   |
|  +------------+  +-------------+  +---------------+   |
|                                                       |
+------------------------------------------------------+
           | miss
           v
+------------------------------------------------------+
|              L3: PostgreSQL (Source of Truth)          |
|                                                       |
|  tasks | task_executions | task_dependencies           |
|  workers | scheduler_nodes | cron_schedules             |
+------------------------------------------------------+
```

### 4.2 L1: JVM Local Cache (Caffeine)

**Technology:** Caffeine (successor to Guava Cache)

**Characteristics:**
- Access latency: ~100 nanoseconds
- Scope: single JVM process
- Eviction: size-based (LRU) + time-based (TTL)
- Thread-safe: yes (concurrent access built-in)
- Coherence: none (each scheduler node has its own L1)

**Configuration:**

```java
// Task definition cache: large, long TTL (definitions rarely change)
Cache<String, Task> taskDefL1 = Caffeine.newBuilder()
    .maximumSize(10_000)
    .expireAfterWrite(10, TimeUnit.MINUTES)
    .refreshAfterWrite(5, TimeUnit.MINUTES)  // async refresh before expiry
    .recordStats()
    .build();

// Cron schedule cache: small, long TTL (schedules rarely change)
Cache<String, CronScheduleEntry> cronL1 = Caffeine.newBuilder()
    .maximumSize(1_000)
    .expireAfterWrite(30, TimeUnit.MINUTES)
    .recordStats()
    .build();

// DAG structure: kept in DependencyResolver directly (no separate cache needed)
// The DependencyResolver IS the L1 cache for the dependency graph.
```

**When L1 is insufficient:**
- After a scheduler failover, the new leader's L1 is cold
- L1 entries have no cross-node coherence (Node A's invalidation does not
  propagate to Node B's L1)
- For data that must be shared across scheduler nodes, L2 (Redis) is required

### 4.3 L2: Redis Distributed Cache

**Technology:** Redis Cluster (or Redis Sentinel for smaller deployments)

**Characteristics:**
- Access latency: ~0.5-2 milliseconds
- Scope: cluster-wide (all scheduler and worker nodes)
- Eviction: configurable (`volatile-lru` for TTL keys, `allkeys-lru` otherwise)
- Thread-safe: yes (single-threaded event loop, atomic commands)
- Coherence: single source (all nodes read/write the same Redis)

**Key namespace design:**

```
task:def:<taskId>          -> Hash (task definition fields)
task:status:<taskId>       -> Hash (status, updatedAt)
worker:registry:<workerId> -> Hash (hostname, port, capacity, load, status, heartbeat)
dag:deps:<taskId>          -> Set (set of dependency task IDs)
cron:schedule:<taskId>     -> Hash (expression, nextFire, lastFire, isActive)
scheduler:metrics:aggregate -> Hash (completedLastHour, failedLastHour, ...)
scheduler:task_queue       -> Sorted Set (task priority queue)
scheduler:delayed_queue    -> Sorted Set (delayed tasks by fire time)
```

**Memory budget estimation:**

```
Component                 | Entries  | Size/Entry | Total Memory
Task definitions          | 10,000   | ~500 bytes | ~5 MB
Task status               | 10,000   | ~50 bytes  | ~500 KB
Worker registry           | 100      | ~200 bytes | ~20 KB
DAG dependencies          | 5,000    | ~100 bytes | ~500 KB
Cron schedules            | 500      | ~100 bytes | ~50 KB
Metrics aggregate         | 1        | ~200 bytes | ~200 B
Task queue (sorted set)   | 5,000    | ~50 bytes  | ~250 KB
                                       Total:      ~6.3 MB
```

Redis memory usage for the caching layer is minimal. A single Redis node with
1 GB of memory can handle 100x this workload.

### 4.4 L3: PostgreSQL (Source of Truth)

**Role:** PostgreSQL is never a "cache" -- it is the authoritative source of truth.
All state transitions, execution records, and task definitions are persisted here.

**Access latency:** 2-10 milliseconds (depending on query complexity and connection
pool availability)

**When L3 is accessed directly (bypassing cache):**
- State transitions (`UPDATE tasks SET status = ...`)
- Execution record writes (`INSERT INTO task_executions`)
- Leader election changes
- Retry count updates
- Version number checks (optimistic locking)

### 4.5 Cache Read Flow

```
Request: getTaskDefinition("task-abc-123")

  +---[L1: Caffeine]---+
  | Key: "task-abc-123" |
  | HIT?                |
  +-----+----+----------+
        |    |
       YES   NO (miss)
        |    |
        v    +---[L2: Redis]---+
   Return    | Key: task:def:  |
   cached    | task-abc-123    |
   task      | HIT?            |
             +-----+----+------+
                   |    |
                  YES   NO (miss)
                   |    |
                   v    +---[L3: PostgreSQL]---+
              Backfill  | SELECT * FROM tasks  |
              L1, return| WHERE id = ?         |
              task      +-----+----------------+
                              |
                              v
                         Backfill L1 + L2,
                         return task
```

### 4.6 Cache Write Flow (Write-Through for Task Status)

```
Request: transitionStatus("task-abc", QUEUED, ASSIGNED)

  +---[L3: PostgreSQL]---+
  | UPDATE tasks         |
  | SET status='ASSIGNED'|
  | WHERE id=? AND       |
  |   status='QUEUED'    |
  |   AND version=?      |
  +-----+----+-----------+
        |    |
     SUCCESS FAILURE (optimistic lock conflict)
        |    |
        |    +---> Return false (another node won)
        |
  +-----v-----------+
  | [L2: Redis]     |
  | HSET task:status|
  |   :task-abc     |
  |   status ASSIGNED|
  +---------+-------+
            |
  +---------v-------+
  | [L1: Caffeine]  |
  | invalidate or   |
  | update entry    |
  +-----------------+
            |
            v
       Return true (success)
```

---

## 5. Cache Invalidation Strategies

### 5.1 Strategy Overview

| Cache Target        | Invalidation Strategy    | Trigger                          |
|---------------------|--------------------------|----------------------------------|
| Task definitions    | Event-driven + TTL       | Kafka task.updated event         |
| Task status         | Write-through            | Every state transition           |
| Worker registry     | Heartbeat refresh + TTL  | Worker heartbeat (every 5s)      |
| DAG structure       | Event-driven             | addDependency/removeDependency   |
| Cron schedules      | Event-driven + TTL       | Schedule create/update/deactivate|
| Metrics aggregate   | TTL only                 | 30-second expiry                 |

### 5.2 Write-Through Invalidation (Task Status)

Write-through ensures the cache is always consistent with the database by
updating both simultaneously on every write.

```java
public boolean updateTaskStatus(String taskId, TaskStatus from, TaskStatus to) {
    // 1. Database first (authoritative)
    boolean success = jdbcTemplate.update(
        "UPDATE tasks SET status=?, version=version+1, updated_at=NOW() WHERE id=? AND status=?",
        to.name(), taskId, from.name()
    ) == 1;

    if (success) {
        // 2. Cache second (best-effort write-through)
        try {
            redis.hset("task:status:" + taskId, Map.of(
                "status", to.name(),
                "updatedAt", String.valueOf(Instant.now().toEpochMilli())
            ));
        } catch (Exception e) {
            // Log but do not fail the operation
            log.warn("Cache write-through failed for task {}", taskId, e);
        }

        // 3. Invalidate L1 on this node
        taskStatusL1.invalidate(taskId);
    }

    return success;
}
```

**Trade-off:** Write-through adds ~1ms latency to every state transition (the Redis
write). This is acceptable because state transitions are not latency-critical
(the task is already being dispatched/executed).

### 5.3 Heartbeat-Based Invalidation (Worker Registry)

Worker registry entries are refreshed by heartbeats and auto-expire via TTL
if heartbeats stop:

```java
// Worker sends heartbeat every 5 seconds
public void onHeartbeat(HeartbeatRequest heartbeat) {
    String key = "worker:registry:" + heartbeat.getWorkerId();

    // Refresh cache entry with latest state
    redis.hset(key, Map.of(
        "currentLoad", String.valueOf(heartbeat.getCurrentLoad()),
        "status", heartbeat.getStatus().name(),
        "lastHeartbeat", String.valueOf(System.currentTimeMillis())
    ));

    // Reset TTL (auto-expire if heartbeat stops)
    redis.expire(key, 60);  // 60 seconds = 12 missed heartbeats before expiry
}
```

**Auto-invalidation flow:**
```
Worker healthy:  heartbeat every 5s -> Redis TTL reset to 60s -> entry persists
Worker crashes:  no heartbeat -> Redis TTL counts down -> entry expires at 60s
FailoverService: detects missing heartbeat -> marks worker DEAD -> removes cache entry
```

### 5.4 Event-Driven Invalidation (Task Definitions, DAG)

For data that changes rarely (task definitions, DAG structure), Kafka events
trigger targeted cache invalidation:

```java
// Kafka consumer: invalidate cache on task definition change
@KafkaListener(topics = "scheduler.task.events", groupId = "cache-invalidator")
public void onTaskEvent(TaskEvent event) {
    if (event.getEventType() == EventType.TASK_UPDATED) {
        // Invalidate L2
        redis.del("task:def:" + event.getTaskId());

        // Invalidate L1 (local node only — other nodes handle their own events)
        taskDefL1.invalidate(event.getTaskId());

        log.info("Cache invalidated for updated task {}", event.getTaskId());
    }

    if (event.getEventType() == EventType.DEPENDENCY_ADDED
            || event.getEventType() == EventType.DEPENDENCY_REMOVED) {
        // Invalidate DAG cache for affected task
        redis.del("dag:deps:" + event.getTaskId());

        // Rebuild in-memory dependency graph for this task
        rebuildDependenciesForTask(event.getTaskId());
    }
}
```

### 5.5 TTL-Only Invalidation (Metrics)

Aggregated metrics are recomputed periodically. TTL-based expiry is sufficient
because slight staleness (up to 30 seconds) is acceptable for dashboard data.

```java
// Metrics cache with TTL-only invalidation
redis.hset("scheduler:metrics:aggregate", metricsMap);
redis.expire("scheduler:metrics:aggregate", 30);  // 30-second TTL

// No explicit invalidation — the TTL handles it
// Dashboard reads may see up to 30-second-old data (acceptable)
```

### 5.6 Invalidation Consistency Guarantees

| Strategy         | Consistency       | Staleness Window    | Risk                  |
|------------------|-------------------|---------------------|-----------------------|
| Write-through    | Strong            | 0 (synchronous)    | Latency on write path |
| Event-driven     | Eventual          | Kafka lag (~100ms)  | Brief stale reads     |
| Heartbeat-based  | Bounded staleness | 5-60 seconds        | Missed heartbeats     |
| TTL-only         | Bounded staleness | 0 to TTL duration   | Stale dashboard data  |

---

## 6. Queue as Cache -- TaskQueue Pattern

### 6.1 Concept

The `TaskQueue` (PriorityQueue in simulation, Redis ZSET in production) is not
traditionally thought of as a "cache," but it serves a caching function: it
maintains an in-memory (or in-Redis) copy of all ready-to-dispatch tasks,
eliminating the need to query PostgreSQL on every scheduler tick.

```
Without TaskQueue (naive approach):
  Every tick: SELECT * FROM tasks WHERE status='QUEUED' ORDER BY priority DESC, created_at ASC
  -> Hits PostgreSQL every 100ms -> massive DB load

With TaskQueue (queue-as-cache):
  On task creation: ZADD task_queue <score> <taskId>  (once)
  Every tick: ZPOPMIN task_queue 1                     (cached read, no DB query)
  -> PostgreSQL only touched on creation and state transitions
```

### 6.2 What the Queue Caches

| Cached Information              | Source of Truth    | Invalidation              |
|---------------------------------|--------------------|---------------------------|
| Set of ready (QUEUED) tasks     | PostgreSQL `tasks` | On enqueue/dequeue/cancel |
| Priority ordering of tasks      | PostgreSQL `tasks` | Score encoding at enqueue |
| Task IDs for dispatch           | PostgreSQL `tasks` | On dequeue (ZPOPMIN)      |

### 6.3 Queue-Cache Consistency

The queue can become inconsistent with PostgreSQL in several scenarios:

**Scenario 1: Task cancelled after enqueue**
```
t=0  Task enqueued into TaskQueue (Redis ZSET)
t=1  User cancels task (PostgreSQL status -> CANCELLED)
t=2  Scheduler dequeues task from ZSET (still in queue!)
t=3  Scheduler reads task from PostgreSQL: status = CANCELLED
t=4  Scheduler skips execution (correct behavior via DB check)
```

**Mitigation:** Always verify task status from PostgreSQL after dequeue.
The queue is an optimization hint, not the source of truth.

```java
public Optional<Task> dequeueAndValidate() {
    Optional<String> taskId = taskQueue.dequeue();  // Redis ZPOPMIN
    if (taskId.isEmpty()) return Optional.empty();

    // ALWAYS verify against PostgreSQL before dispatching
    Task task = taskRepository.findById(taskId.get()).orElse(null);
    if (task == null || task.getStatus() != TaskStatus.QUEUED) {
        log.info("Dequeued task {} is no longer QUEUED (status={}), skipping",
                 taskId.get(), task != null ? task.getStatus() : "DELETED");
        return Optional.empty();
    }

    return Optional.of(task);
}
```

**Scenario 2: Scheduler crashes after PostgreSQL write but before Redis enqueue**
```
t=0  Task created in PostgreSQL (status = PENDING -> QUEUED)
t=1  Scheduler crashes before ZADD to Redis
t=2  Task is QUEUED in PostgreSQL but NOT in the Redis queue
```

**Mitigation:** Periodic reconciliation job that finds orphaned QUEUED tasks:

```java
// Reconciliation: find QUEUED tasks missing from the queue (runs every 5 minutes)
public void reconcileQueue() {
    List<Task> queuedInDb = taskRepository.findByStatus(TaskStatus.QUEUED);
    Set<String> queuedInRedis = redis.zrange("scheduler:task_queue", 0, -1);

    for (Task task : queuedInDb) {
        if (!queuedInRedis.contains(task.getId())) {
            log.warn("Reconciliation: task {} is QUEUED in DB but missing from Redis queue, re-enqueuing",
                     task.getId());
            taskQueue.enqueue(task);
        }
    }
}
```

### 6.4 Simulation Equivalent

The simulation's `TaskQueue` (Java `PriorityQueue`) is the in-process version of
this pattern. Since everything is in-memory and single-process, there is no
consistency gap between the queue and the repository. The `ConcurrentHashMap`
in `InMemoryTaskRepository` and the `PriorityQueue` in `TaskQueue` are always
consistent because they are updated synchronously in the same thread.

---

## 7. Worker Registry Caching

### 7.1 Why Worker Registry Needs Dedicated Caching

Task assignment reads the worker registry on every dispatch to find available
workers. With 100+ dispatches per second and 50+ workers, this is one of the
hottest read paths in the system.

```
Dispatch hot path:
  1. Dequeue task from TaskQueue          (~0.5ms Redis ZPOPMIN)
  2. Get available workers                (~??? depends on caching)
  3. Apply assignment strategy            (~0.1ms in-memory)
  4. Update task status to ASSIGNED       (~2ms PostgreSQL + Redis)
  5. Push task to worker via gRPC         (~1ms network)
```

Without caching step 2 (PostgreSQL query): ~5ms
With caching step 2 (Redis hash scan): ~1ms
With caching step 2 (L1 JVM cache): ~0.1ms

### 7.2 Cache Design

```java
public class WorkerRegistryCache {

    // L1: JVM-local snapshot of available workers (refreshed every heartbeat cycle)
    private volatile List<Worker> availableWorkersSnapshot = List.of();
    private volatile Instant snapshotTime = Instant.EPOCH;
    private static final Duration SNAPSHOT_MAX_AGE = Duration.ofSeconds(5);

    // L2: Redis hashes per worker (source for L1 refresh)
    private final JedisPool redisPool;

    // Hot path: return cached snapshot if fresh enough
    public List<Worker> getAvailableWorkers() {
        if (Duration.between(snapshotTime, Instant.now()).compareTo(SNAPSHOT_MAX_AGE) < 0) {
            return availableWorkersSnapshot;  // L1 hit (~100ns)
        }

        // L1 expired, refresh from L2 (Redis)
        return refreshFromRedis();
    }

    // Called by heartbeat processor to keep L2 current
    public void updateWorkerInRedis(Worker worker) {
        String key = "worker:registry:" + worker.getId();
        try (Jedis jedis = redisPool.getResource()) {
            jedis.hset(key, Map.of(
                "id", worker.getId(),
                "hostname", worker.getHostname(),
                "port", String.valueOf(worker.getPort()),
                "capacity", String.valueOf(worker.getCapacity()),
                "currentLoad", String.valueOf(worker.getCurrentLoad()),
                "status", worker.getStatus().name(),
                "lastHeartbeat", String.valueOf(worker.getLastHeartbeat().toEpochMilli()),
                "tags", String.join(",", worker.getTags())
            ));
            jedis.expire(key, 60);  // auto-expire if heartbeats stop
        }
    }

    // Refresh L1 from L2
    private synchronized List<Worker> refreshFromRedis() {
        // Double-check after acquiring lock
        if (Duration.between(snapshotTime, Instant.now()).compareTo(SNAPSHOT_MAX_AGE) < 0) {
            return availableWorkersSnapshot;
        }

        try (Jedis jedis = redisPool.getResource()) {
            Set<String> keys = jedis.keys("worker:registry:*");
            List<Worker> available = new ArrayList<>();

            Pipeline pipeline = jedis.pipelined();
            List<Response<Map<String, String>>> responses = new ArrayList<>();
            for (String key : keys) {
                responses.add(pipeline.hgetAll(key));
            }
            pipeline.sync();

            for (Response<Map<String, String>> resp : responses) {
                Map<String, String> data = resp.get();
                if ("ACTIVE".equals(data.get("status"))) {
                    int load = Integer.parseInt(data.get("currentLoad"));
                    int capacity = Integer.parseInt(data.get("capacity"));
                    if (load < capacity) {
                        available.add(deserializeWorker(data));
                    }
                }
            }

            this.availableWorkersSnapshot = List.copyOf(available);
            this.snapshotTime = Instant.now();
            return this.availableWorkersSnapshot;
        }
    }

    // Force invalidation when a worker dies
    public void invalidateWorker(String workerId) {
        try (Jedis jedis = redisPool.getResource()) {
            jedis.del("worker:registry:" + workerId);
        }
        // Force L1 refresh on next access
        this.snapshotTime = Instant.EPOCH;
    }
}
```

### 7.3 Heartbeat-Based Cache Lifecycle

```
Worker joins:
  1. RegisterWorker RPC -> PostgreSQL INSERT worker
  2. Redis HSET worker:registry:<id> with EXPIRE 60
  3. L1 snapshot refreshed on next access

Worker sends heartbeat (every 5s):
  1. HeartbeatRequest received via gRPC stream
  2. Redis HSET update (load, status, heartbeat timestamp)
  3. Redis EXPIRE reset to 60 seconds
  4. PostgreSQL async UPDATE (eventual consistency)
  5. L1 snapshot refreshed within 5 seconds

Worker crashes:
  1. No heartbeats received
  2. Redis TTL counts down from 60 seconds
  3. FailoverService detects missing heartbeat (30s timeout)
  4. FailoverService calls invalidateWorker() -> Redis DEL + L1 reset
  5. Redis entry auto-expires at 60s (backup cleanup)
  6. Tasks reassigned to other workers
```

### 7.4 Simulation Equivalent

`InMemoryWorkerRepository` backed by `ConcurrentHashMap` is the L1 cache.
`WorkerService.getAvailableWorkers()` filters workers in-memory. No L2 or
persistence layer exists in the simulation.

---

## 8. Cache Warming on Startup

### 8.1 Why Cache Warming Matters

When a scheduler node starts (fresh deployment or leader failover), its caches
are completely cold. Without warming, the first wave of operations hits
PostgreSQL directly, causing a latency spike and potential DB overload.

### 8.2 Startup Cache Warming Sequence

```
Scheduler Node Startup
  |
  +-> [Phase 1: Load active cron schedules]
  |     SELECT * FROM cron_schedules WHERE is_active = TRUE
  |     -> Populate L1 cronCache map
  |     -> Populate L2 Redis cron:schedule:* hashes
  |     Duration: ~100ms for 500 schedules
  |
  +-> [Phase 2: Load active worker registry]
  |     SELECT * FROM workers WHERE status IN ('ACTIVE', 'BUSY')
  |     -> Populate L2 Redis worker:registry:* hashes
  |     -> Build L1 available workers snapshot
  |     Duration: ~50ms for 100 workers
  |
  +-> [Phase 3: Rebuild dependency graph]
  |     SELECT * FROM task_dependencies
  |       WHERE task_id IN (SELECT id FROM tasks WHERE status NOT IN ('COMPLETED','FAILED','CANCELLED'))
  |     -> Populate L1 DependencyResolver in-memory graph
  |     -> Populate L2 Redis dag:deps:* sets
  |     Duration: ~200ms for 5000 edges
  |
  +-> [Phase 4: Rebuild task queue]
  |     SELECT id, priority, created_at FROM tasks WHERE status = 'QUEUED'
  |     -> Populate L2 Redis ZSET scheduler:task_queue
  |     Duration: ~100ms for 5000 queued tasks
  |
  +-> [Phase 5: Warm task definition cache (top N by recency)]
  |     SELECT * FROM tasks ORDER BY updated_at DESC LIMIT 1000
  |     -> Populate L2 Redis task:def:* hashes
  |     -> Populate L1 Caffeine cache
  |     Duration: ~200ms for 1000 tasks
  |
  +-> [Phase 6: Compute initial metrics aggregate]
  |     Aggregate queries on task_executions
  |     -> Populate L2 Redis scheduler:metrics:aggregate
  |     Duration: ~300ms
  |
  +-> Node ready to process tasks
       Total warm-up time: ~1 second
```

### 8.3 Implementation

```java
public class CacheWarmer {

    private final TaskRepository taskRepo;
    private final WorkerRepository workerRepo;
    private final DependencyRepository depRepo;
    private final CronScheduleRepository cronRepo;
    private final ExecutionRepository execRepo;
    private final JedisPool redis;
    private final DependencyResolver depResolver;
    private final TaskQueue taskQueue;
    private final Cache<String, Task> taskDefL1;

    // Called once during scheduler startup, before accepting work
    public void warmAll() {
        long start = System.currentTimeMillis();
        log.info("Starting cache warm-up...");

        // Phase 1-2 can run in parallel
        CompletableFuture<Void> cronFuture = CompletableFuture.runAsync(this::warmCronSchedules);
        CompletableFuture<Void> workerFuture = CompletableFuture.runAsync(this::warmWorkerRegistry);

        CompletableFuture.allOf(cronFuture, workerFuture).join();

        // Phase 3-4 depend on Phase 1-2 completing
        CompletableFuture<Void> dagFuture = CompletableFuture.runAsync(this::warmDependencyGraph);
        CompletableFuture<Void> queueFuture = CompletableFuture.runAsync(this::warmTaskQueue);

        CompletableFuture.allOf(dagFuture, queueFuture).join();

        // Phase 5-6
        warmTaskDefinitions();
        warmMetricsAggregate();

        long elapsed = System.currentTimeMillis() - start;
        log.info("Cache warm-up completed in {}ms", elapsed);
    }

    private void warmCronSchedules() {
        List<CronScheduleEntry> active = cronRepo.findAllActive();
        for (CronScheduleEntry entry : active) {
            cronCache.put(entry.getTaskId(), entry);
            redis.hset("cron:schedule:" + entry.getTaskId(), serialize(entry));
        }
        log.info("Warmed {} cron schedules", active.size());
    }

    private void warmWorkerRegistry() {
        List<Worker> activeWorkers = workerRepo.findByStatusIn(
            List.of(WorkerStatus.ACTIVE, WorkerStatus.BUSY));
        for (Worker worker : activeWorkers) {
            redis.hset("worker:registry:" + worker.getId(), serialize(worker));
            redis.expire("worker:registry:" + worker.getId(), 60);
        }
        log.info("Warmed {} worker registry entries", activeWorkers.size());
    }

    private void warmDependencyGraph() {
        List<TaskDependency> activeDeps = depRepo.findActiveDependencies();
        for (TaskDependency dep : activeDeps) {
            depResolver.addDependency(dep.getTaskId(), dep.getDependsOnId());
            redis.sadd("dag:deps:" + dep.getTaskId(), dep.getDependsOnId());
        }
        log.info("Warmed dependency graph with {} edges", activeDeps.size());
    }

    private void warmTaskQueue() {
        List<Task> queuedTasks = taskRepo.findByStatus(TaskStatus.QUEUED);
        for (Task task : queuedTasks) {
            taskQueue.enqueue(task);
        }
        log.info("Warmed task queue with {} tasks", queuedTasks.size());
    }

    private void warmTaskDefinitions() {
        List<Task> recentTasks = taskRepo.findRecentTasks(1000);
        for (Task task : recentTasks) {
            taskDefL1.put(task.getId(), task);
            redis.hset("task:def:" + task.getId(), serialize(task));
            redis.expire("task:def:" + task.getId(), 3600);
        }
        log.info("Warmed {} task definitions", recentTasks.size());
    }

    private void warmMetricsAggregate() {
        // Compute fresh aggregates from PostgreSQL
        metricsCache.refreshMetrics();
        log.info("Warmed metrics aggregate");
    }
}
```

### 8.4 Gradual Warming vs. Full Warming

**Full warming (recommended for leader election):**
- Load everything before accepting work
- Guarantees no cold-cache latency spikes
- Adds ~1 second to startup time

**Gradual warming (acceptable for new worker nodes):**
- Start accepting work immediately
- Cache misses fill the cache on-demand
- First 100-200 operations may be slower

```java
// Gradual warming: Caffeine's refreshAfterWrite handles this naturally
Cache<String, Task> taskDefL1 = Caffeine.newBuilder()
    .maximumSize(10_000)
    .expireAfterWrite(10, TimeUnit.MINUTES)
    .refreshAfterWrite(5, TimeUnit.MINUTES)  // proactive refresh before expiry
    .build(taskId -> loadTaskFromRedisOrDb(taskId));  // auto-load on miss
```

### 8.5 Simulation Equivalent

The simulation's `AppConfig` and `DistributedTaskSchedulerApp.main()` perform
a form of cache warming by creating and registering all tasks, workers, and
dependencies before starting the scheduler loop. This is analogous to the
full-warming approach.

---

## 9. Cache Failure Handling

### 9.1 Failure Modes

| Failure                     | Impact                                          | Recovery             |
|-----------------------------|--------------------------------------------------|----------------------|
| Redis node crash            | L2 cache unavailable, all reads hit PostgreSQL   | Failover to replica  |
| Redis network partition     | Timeout on cache reads/writes                    | Circuit breaker      |
| L1 eviction (memory pressure)| Cold cache for evicted entries, higher L2 hits  | Self-healing         |
| Stale L1 entry              | Brief stale read until TTL or event invalidation | TTL expiry           |
| Redis ZSET corruption       | Task queue inconsistency                         | Reconciliation job   |
| Full Redis memory           | Eviction of cache entries, possible OOM          | maxmemory policy     |

### 9.2 Graceful Degradation Architecture

```
Normal operation:
  L1 -> L2 (Redis) -> L3 (PostgreSQL)
  Latency: ~0.1ms (L1 hit) or ~1ms (L2 hit) or ~5ms (L3 hit)

Redis failure:
  L1 -> L3 (PostgreSQL) directly
  Latency: ~0.1ms (L1 hit) or ~5ms (L3 hit)
  Impact: Higher PostgreSQL load, but system remains functional
```

### 9.3 Circuit Breaker for Redis

```java
public class ResilientCacheClient {

    private final JedisPool redisPool;
    private final CircuitBreaker circuitBreaker;

    public ResilientCacheClient(JedisPool redisPool) {
        this.redisPool = redisPool;
        this.circuitBreaker = CircuitBreaker.ofDefaults("redis-cache");

        // Configure circuit breaker
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .failureRateThreshold(50)              // open after 50% failure rate
            .waitDurationInOpenState(Duration.ofSeconds(30))  // retry after 30s
            .slidingWindowSize(10)                 // evaluate last 10 calls
            .permittedNumberOfCallsInHalfOpenState(3) // probe with 3 calls
            .build();
        this.circuitBreaker = CircuitBreaker.of("redis-cache", config);
    }

    // Resilient cache get: returns Optional.empty() on Redis failure
    public Optional<String> get(String key) {
        try {
            return circuitBreaker.executeSupplier(() -> {
                try (Jedis jedis = redisPool.getResource()) {
                    String value = jedis.get(key);
                    return Optional.ofNullable(value);
                }
            });
        } catch (Exception e) {
            // Circuit open or Redis error -> degrade gracefully
            log.debug("Redis cache miss (circuit: {}): {}", circuitBreaker.getState(), key);
            return Optional.empty();
        }
    }

    // Resilient cache set: silently fails on Redis error
    public void set(String key, String value, int ttlSeconds) {
        try {
            circuitBreaker.executeRunnable(() -> {
                try (Jedis jedis = redisPool.getResource()) {
                    jedis.setex(key, ttlSeconds, value);
                }
            });
        } catch (Exception e) {
            log.debug("Redis cache set failed (circuit: {}): {}", circuitBreaker.getState(), key);
            // Fail silently — PostgreSQL is the source of truth
        }
    }
}
```

### 9.4 Fallback Read Path

```java
public Task getTaskDefinition(String taskId) {
    // 1. Try L1 (always available, in-process)
    Task cached = taskDefL1.getIfPresent(taskId);
    if (cached != null) {
        return cached;
    }

    // 2. Try L2 (Redis, may fail)
    Optional<String> redisResult = resilientCache.get("task:def:" + taskId);
    if (redisResult.isPresent()) {
        Task task = deserialize(redisResult.get());
        taskDefL1.put(taskId, task);  // backfill L1
        return task;
    }

    // 3. Fall through to L3 (PostgreSQL, always available)
    Task task = taskRepository.findById(taskId)
        .orElseThrow(() -> new TaskNotFoundException(taskId));

    // Backfill L1 (always safe)
    taskDefL1.put(taskId, task);

    // Backfill L2 (best effort, may fail if Redis is down)
    resilientCache.set("task:def:" + taskId, serialize(task), 3600);

    return task;
}
```

### 9.5 Task Queue Failure Recovery

If Redis (which holds the task queue ZSET) goes down, the scheduler cannot
dequeue tasks. Recovery strategy:

```java
public class ResilientTaskQueue {

    private final JedisPool redisPool;
    private final TaskRepository taskRepo;
    private final PriorityQueue<Task> localFallback;  // in-memory backup
    private volatile boolean redisAvailable = true;

    // Dequeue with fallback
    public Optional<Task> dequeue() {
        if (redisAvailable) {
            try {
                return dequeueFromRedis();
            } catch (Exception e) {
                log.error("Redis task queue unavailable, switching to local fallback", e);
                redisAvailable = false;
                rebuildLocalFallback();
            }
        }

        // Fallback: dequeue from local PriorityQueue
        return Optional.ofNullable(localFallback.poll());
    }

    // Rebuild local queue from PostgreSQL
    private void rebuildLocalFallback() {
        List<Task> queued = taskRepo.findByStatus(TaskStatus.QUEUED);
        localFallback.clear();
        localFallback.addAll(queued);
        log.info("Rebuilt local fallback queue with {} tasks", queued.size());
    }

    // Periodic check: try to reconnect to Redis
    @Scheduled(fixedDelay = 10000)
    public void checkRedisHealth() {
        if (!redisAvailable) {
            try (Jedis jedis = redisPool.getResource()) {
                jedis.ping();
                redisAvailable = true;
                log.info("Redis reconnected, switching back to Redis queue");
                // Re-sync Redis ZSET from PostgreSQL
                reconcileRedisQueue();
            } catch (Exception e) {
                log.debug("Redis still unavailable");
            }
        }
    }
}
```

### 9.6 Cache Corruption Detection

```java
// Periodic validation: compare cache state with PostgreSQL
public void validateCacheConsistency() {
    // Sample 100 random tasks and compare status
    List<Task> sample = taskRepo.findRandomSample(100);
    int mismatches = 0;

    for (Task dbTask : sample) {
        String cachedStatus = redis.hget("task:status:" + dbTask.getId(), "status");
        if (cachedStatus != null && !cachedStatus.equals(dbTask.getStatus().name())) {
            mismatches++;
            log.warn("Cache inconsistency: task {} status cache={} db={}",
                     dbTask.getId(), cachedStatus, dbTask.getStatus().name());
            // Fix: overwrite cache with DB value
            redis.hset("task:status:" + dbTask.getId(), "status", dbTask.getStatus().name());
        }
    }

    // Report to Prometheus
    cacheInconsistencyGauge.set(mismatches);
    if (mismatches > 10) {
        log.error("High cache inconsistency rate: {}/100 sampled tasks", mismatches);
    }
}
```

---

## 10. Metrics Caching

### 10.1 Why Cache Metrics

Dashboard queries like "tasks completed in last hour" require aggregation over
`task_executions`. Running these queries on every dashboard refresh (every 10-30
seconds) would create significant PostgreSQL load:

```sql
-- Expensive: runs on every dashboard refresh
SELECT COUNT(*) FROM task_executions
WHERE status = 'COMPLETED' AND created_at > NOW() - INTERVAL '1 hour';

-- Also expensive: average duration
SELECT AVG(duration_millis) FROM task_executions
WHERE created_at > NOW() - INTERVAL '1 hour';
```

### 10.2 Aggregate Metrics Cache

```java
public class MetricsAggregateCache {

    private static final String KEY = "scheduler:metrics:aggregate";
    private static final int TTL_SECONDS = 30;

    private final JedisPool redisPool;
    private final ExecutionRepository execRepo;
    private final TaskQueue taskQueue;
    private final WorkerService workerService;

    // Computed fields
    public static class AggregateMetrics {
        public int completedLastHour;
        public int failedLastHour;
        public int timedOutLastHour;
        public double avgDurationMs;
        public double p95DurationMs;
        public double p99DurationMs;
        public int currentQueueDepth;
        public int activeWorkers;
        public int totalWorkers;
        public double avgWorkerUtilization;
        public int retryAttemptsLastHour;
        public int deadLetterLastHour;
        public Instant computedAt;
    }

    // Background job: refresh aggregates every 30 seconds
    @Scheduled(fixedDelay = 30_000)
    public void refreshAggregates() {
        Instant now = Instant.now();
        Instant oneHourAgo = now.minus(Duration.ofHours(1));

        AggregateMetrics metrics = new AggregateMetrics();
        metrics.completedLastHour = execRepo.countByStatusSince("COMPLETED", oneHourAgo);
        metrics.failedLastHour = execRepo.countByStatusSince("FAILED", oneHourAgo);
        metrics.timedOutLastHour = execRepo.countByStatusSince("TIMED_OUT", oneHourAgo);
        metrics.avgDurationMs = execRepo.averageDurationSince(oneHourAgo);
        metrics.p95DurationMs = execRepo.percentileDurationSince(oneHourAgo, 95);
        metrics.p99DurationMs = execRepo.percentileDurationSince(oneHourAgo, 99);
        metrics.currentQueueDepth = taskQueue.size();
        metrics.activeWorkers = workerService.getAvailableWorkers().size();
        metrics.totalWorkers = workerService.getAllWorkers().size();
        metrics.avgWorkerUtilization = computeAvgUtilization();
        metrics.retryAttemptsLastHour = execRepo.countRetriesSince(oneHourAgo);
        metrics.deadLetterLastHour = execRepo.countDeadLetterSince(oneHourAgo);
        metrics.computedAt = now;

        try (Jedis jedis = redisPool.getResource()) {
            jedis.hset(KEY, serializeMetrics(metrics));
            jedis.expire(KEY, TTL_SECONDS);
        }
    }

    // Dashboard reads: always fast, at most 30s stale
    public AggregateMetrics getAggregates() {
        try (Jedis jedis = redisPool.getResource()) {
            Map<String, String> cached = jedis.hgetAll(KEY);
            if (!cached.isEmpty()) {
                return deserializeMetrics(cached);
            }
        }
        // Cold start: compute synchronously
        refreshAggregates();
        try (Jedis jedis = redisPool.getResource()) {
            return deserializeMetrics(jedis.hgetAll(KEY));
        }
    }
}
```

### 10.3 Real-Time Counters with Redis INCR

For counters that need real-time accuracy (e.g., "tasks submitted today"),
use Redis atomic increments instead of PostgreSQL aggregation:

```java
// On every task submission
public void onTaskSubmitted(Task task) {
    String dateKey = "scheduler:counter:submitted:" + LocalDate.now();
    redis.incr(dateKey);
    redis.expire(dateKey, 86400 * 2);  // auto-expire after 2 days

    String priorityKey = "scheduler:counter:submitted:" + task.getPriority().name();
    redis.incr(priorityKey);
}

// On every task completion
public void onTaskCompleted(Task task, TaskResult result) {
    String dateKey = "scheduler:counter:" + result.name().toLowerCase() + ":" + LocalDate.now();
    redis.incr(dateKey);
    redis.expire(dateKey, 86400 * 2);
}

// Read: instant, no aggregation needed
public long getSubmittedToday() {
    String dateKey = "scheduler:counter:submitted:" + LocalDate.now();
    String count = redis.get(dateKey);
    return count != null ? Long.parseLong(count) : 0;
}
```

### 10.4 Simulation Equivalent

`MonitoringService` and `SchedulerStatsDisplay` compute metrics by iterating over
in-memory collections (`findAll()`, `findByStatus()`, etc.). This is the equivalent
of querying PostgreSQL without caching -- acceptable in simulation because the
data is already in-memory. In production, the metrics caching layer prevents
this pattern from overloading the database.

---

## 11. Cache Decision Matrix

### 11.1 Full Decision Matrix

Use this matrix to determine the caching strategy for any data type in the
scheduler system.

```
+------------------+--------+--------+--------+--------+---------+-----------+
| Data             | Cache? | L1?    | L2?    | TTL    | Inval.  | Write     |
|                  |        | (JVM)  | (Redis)|        | Strategy| Strategy  |
+------------------+--------+--------+--------+--------+---------+-----------+
| Task definitions | YES    | YES    | YES    | 10m/1h | Event   | Read-thru |
| Task status      | YES    | NO*    | YES    | none   | Write-  | Write-thru|
|                  |        |        |        |        | through |           |
| Worker registry  | YES    | YES**  | YES    | 60s    | Heartbt | Heartbeat |
| DAG structure    | YES    | YES    | YES    | none   | Event   | Write-thru|
| Cron schedules   | YES    | YES    | NO***  | 30m    | Event   | Read-thru |
| Metrics aggregate| YES    | NO     | YES    | 30s    | TTL     | Periodic  |
| Task queue       | YES    | N/A    | YES    | none   | Dequeue | Enqueue   |
|                  |        | (is L2)|        |        |         |           |
| Execution records| NO     | --     | --     | --     | --      | --        |
| Leader state     | NO     | --     | --     | --     | --      | --        |
| Lock state       | NO     | --     | --     | --     | --      | --        |
| Retry count      | NO     | --     | --     | --     | --      | --        |
| Task version     | NO     | --     | --     | --     | --      | --        |
+------------------+--------+--------+--------+--------+---------+-----------+

*  Task status L1 is avoided because stale status on the dispatch hot path
   could cause double-assignment. L2 (Redis) with write-through is sufficient.

** Worker registry L1 is a volatile snapshot refreshed every 5 seconds.
   It is not a traditional cache but a performance optimization for the
   dispatch hot path.

*** Cron schedules are small enough (< 500 entries typically) to fit entirely
    in L1. L2 is optional but not necessary.
```

### 11.2 Decision Flowchart

```
Is the data on the critical path for task assignment?
  |
  YES -> Is it a STATE TRANSITION (write)?
  |        |
  |       YES -> Do NOT cache. Use PostgreSQL with optimistic locking.
  |        |
  |       NO -> Is it a STATUS READ?
  |               |
  |              YES -> Cache in L2 (Redis) with write-through.
  |               |     Do NOT cache in L1 (staleness risk).
  |              NO -> Cache in L1 + L2 with appropriate TTL.
  |
  NO -> Is it read-heavy (>10 reads per write)?
          |
         YES -> Is the data small (< 10,000 entries)?
         |        |
         |       YES -> Cache in L1 (Caffeine) with TTL.
         |        |
         |       NO -> Cache in L2 (Redis) with TTL.
         |
         NO -> Is it write-heavy (> 100 writes/sec)?
                 |
                YES -> Do NOT cache. Write directly to PostgreSQL.
                 |
                NO -> Cache in L2 (Redis) with short TTL (30-60s).
```

### 11.3 Cache Hit Rate Targets

| Cache Layer    | Target Hit Rate | Action if Below Target                     |
|----------------|-----------------|--------------------------------------------|
| L1 (Caffeine)  | > 90%           | Increase max size or TTL                   |
| L2 (Redis)     | > 85%           | Check invalidation frequency, increase TTL |
| Overall        | > 95%           | Review cache warming, add missing data     |

Monitor cache hit rates via Caffeine's `recordStats()` and Redis `INFO stats`
(keyspace_hits / keyspace_misses).

---

## 12. Simulation-to-Production Cache Mapping

### 12.1 Mapping Table

| Simulation Pattern                          | Production Cache Equivalent                |
|---------------------------------------------|--------------------------------------------|
| `ConcurrentHashMap` in InMemoryTaskRepo     | L1 Caffeine + L2 Redis + L3 PostgreSQL     |
| `ConcurrentHashMap` in InMemoryWorkerRepo   | L1 volatile snapshot + L2 Redis hashes     |
| `ConcurrentHashMap` in InMemoryExecRepo     | No cache (write to PostgreSQL directly)    |
| `HashMap` in DependencyResolver             | L1 in-memory graph + L2 Redis sets backup  |
| `PriorityQueue` in TaskQueue                | L2 Redis ZSET (queue-as-cache)             |
| `Task.getStatus()` direct field access      | L2 Redis hash with write-through           |
| `Worker.isAvailable()` method call          | L1 snapshot + L2 Redis hash filter         |
| `MonitoringService` in-memory stats         | L2 Redis aggregate hash with 30s TTL       |
| `CronParser.getNextFireTime()` computation  | L1 precomputed map + PostgreSQL column     |

### 12.2 What the Simulation Already Does Right

The simulation's `ConcurrentHashMap`-backed repositories are essentially
unbounded L1 caches with no TTL, no eviction, and no persistence. This is
the correct architectural pattern (cache in front of storage) simplified
for in-process demonstration.

The `DependencyResolver` keeping the graph in a `HashMap` is exactly what
production does with an L1 in-memory graph. The only difference is that
production adds L2 (Redis) for cross-node sharing and L3 (PostgreSQL) for
durability.

### 12.3 What the Simulation Misses

| Production Concern                      | Simulation Gap                           |
|-----------------------------------------|------------------------------------------|
| Cache eviction under memory pressure    | No eviction (unbounded maps)             |
| Cross-node cache coherence              | Single process (no coherence needed)     |
| Cache warming after failover            | No failover (single process)             |
| Write-through to persistent storage     | No persistent storage                    |
| Circuit breaker for cache failures      | No external dependencies to fail         |
| Cache hit/miss metrics                  | No metrics collection                    |
| TTL-based staleness bounds              | No TTL (always fresh in-process)         |

### 12.4 Key Takeaway for Interviews

When discussing caching in a distributed task scheduler interview:

1. **Start with the simulation model** -- "In-memory maps give us the right
   access patterns. ConcurrentHashMap for task definitions, PriorityQueue for
   the ready queue."

2. **Explain the production evolution** -- "In production, the in-memory map
   becomes Caffeine (L1) + Redis (L2) + PostgreSQL (L3). The PriorityQueue
   becomes a Redis sorted set with composite priority+timestamp scoring."

3. **Highlight what NOT to cache** -- "State transitions must go through
   PostgreSQL with optimistic locking. Caching state transitions risks
   double-assignment."

4. **Discuss failure modes** -- "Redis failure degrades to L1 + PostgreSQL.
   Circuit breakers prevent cascading failures. The task queue falls back to
   a local PriorityQueue rebuilt from PostgreSQL."

5. **Quantify the impact** -- "L1 hit: 100ns. L2 hit: 1ms. L3 hit: 5ms.
   On the dispatch hot path (100+ dispatches/sec), caching reduces per-dispatch
   latency from ~15ms to ~1.5ms."
