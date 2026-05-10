# Distributed Task Scheduler (Airflow / Celery / Temporal)

## Problem Summary

Design a **distributed task scheduler** (like Apache Airflow, Celery, or Temporal) that orchestrates millions of tasks across a pool of heterogeneous workers. The core challenge is the **scheduler engine** -- maintain an in-memory **priority queue** (PriorityQueue with Comparator: CRITICAL > HIGH > MEDIUM > LOW, ties broken by createdAt FIFO) that feeds a **dispatcher** which assigns tasks to workers using pluggable strategies (round-robin for homogeneous clusters, least-loaded for heterogeneous clusters). **DAG-based dependency resolution** uses **Kahn's algorithm** (topological sort via BFS peeling of zero-in-degree nodes) to determine execution order, with **DFS three-coloring** (WHITE/GRAY/BLACK) for cycle detection -- a back edge (GRAY -> GRAY) means circular dependency, which would deadlock the pipeline. **Leader election** via the **Bully algorithm** ensures exactly one scheduler node coordinates task dispatch: the highest-priority alive node wins, and when the leader fails (heartbeat timeout), the next-highest-priority node triggers re-election. **Worker failover** detects dead workers via heartbeat staleness, reassigns their RUNNING tasks to healthy workers, and decrements load counters. **Exactly-once execution** is achieved through idempotency keys in the task payload, terminal-state guard checks (don't re-execute COMPLETED tasks), and fencing tokens that prevent stale workers from writing results after failover. **Exponential backoff retry** with jitter (formula: `min(initialDelay * multiplier^(attempt-1), maxDelay) +/- 10%`) prevents thundering herd when many tasks fail simultaneously. The system is **CP for task state** (a lost task or duplicate execution is a correctness violation -- imagine double-charging a customer) and **AP for monitoring metrics** (a dashboard showing 99.1% instead of 99.2% success rate for 30 seconds is acceptable).

---

## 1-Minute Interview Revision

**Memorize these bullets. They cover 80% of what interviewers want to hear.**

- **Priority Queue: PriorityQueue with Comparator (CRITICAL > HIGH > MEDIUM > LOW). Ties broken by createdAt (FIFO). O(log n) enqueue/dequeue.** The priority queue is the central data structure of the scheduler. Tasks enter via `enqueue()` and exit via `dequeue()` in strict priority order. The Comparator sorts by priority value descending (CRITICAL=3, HIGH=2, MEDIUM=1, LOW=0), then by `createdAt` ascending (earliest task first among same priority). This gives priority-time ordering analogous to price-time priority in an order book. The queue is not thread-safe by design -- it is consumed only by the single-threaded scheduler loop, eliminating lock contention. In production, replace with Redis sorted sets (ZADD/ZPOPMIN) or Kafka topic partitions with priority headers for distributed queuing.

- **DAG Dependencies: Kahn's algorithm (topological sort). Cycle detection via DFS three-coloring. Tasks only dispatch when ALL upstream dependencies complete.** When tasks have dependencies (e.g., ETL: Extract -> Transform -> Load), the scheduler maintains an adjacency list (taskId -> Set<dependsOnIds>). Before dispatching, `getReadyTasks(completedSet)` checks: is every dependency of this task in the completed set? Kahn's algorithm computes the global execution order: (1) compute in-degree for every node, (2) seed a queue with zero-in-degree nodes, (3) BFS peel: dequeue node, add to result, decrement in-degree of all dependents, enqueue any that hit zero. Cycle detection uses DFS three-coloring: WHITE=unvisited, GRAY=in-current-path, BLACK=fully-explored. A GRAY->GRAY edge is a back edge = cycle. Cycles are rejected immediately to prevent deadlock.

- **Leader Election: Bully algorithm -- highest-priority alive node wins. On leader failure, next-highest triggers re-election.** In a distributed scheduler cluster, exactly one node must be the coordinator (assigns tasks, rebalances load). The Bully algorithm: each node challenges all higher-priority nodes. If no higher-priority node responds (heartbeat timeout), you become leader. If a higher-priority node responds, you defer. The winner broadcasts COORDINATOR to all. Complexity: O(n^2) messages worst case. In production, use Raft (etcd/Consul), ZooKeeper (ZAB), or lease-based election (DynamoDB conditional writes). Bully is simple and correct for interviews; Raft handles network partitions.

- **Worker Assignment: Round-robin (even distribution, ignores load) vs Least-loaded (respects capacity, prefers idle workers).** Round-robin cycles through available workers via an atomic counter: `index = counter.getAndIncrement() % workers.size()`. Simple, predictable, zero overhead. But it ignores worker capacity (a 4-core machine and a 32-core machine get equal tasks). Least-loaded picks the worker with minimum `currentLoad / capacity` ratio, breaking ties by `registeredAt` (prefer older, more stable workers). Better for heterogeneous clusters. In production, add consistent hashing for stateful tasks (cache locality) and tag-based affinity (GPU tasks -> GPU workers).

- **Retry: Exponential backoff with jitter. Formula: min(initial * 2^(attempt-1), maxDelay) +/- 10%.** When a task fails, the retry strategy decides: should we retry (attempt <= maxRetries?) and when (delay = ?). Exponential backoff: 1s, 2s, 4s, 8s, 16s... capped at 30s. Jitter adds +/-10% randomness to prevent thundering herd (1000 tasks failing at the same time all retrying at exactly 2s would spike load). After `maxRetries` exhausted, task transitions to FAILED permanently. In production, implement dead-letter queues for failed tasks and separate retry topics in Kafka with per-partition delay.

- **Exactly-Once: Idempotency key + terminal state guard + fencing token.** In distributed systems, exactly-once = at-least-once delivery + idempotent processing. Three layers: (1) **Idempotency key**: client includes a UUID in the task payload; server deduplicates via unique index on (idempotency_key). (2) **Terminal state guard**: before executing, check `task.getStatus().isTerminal()` -- if COMPLETED/FAILED/CANCELLED, skip. (3) **Fencing token**: monotonically increasing token assigned at dispatch time; worker must present the token when writing results; stale tokens (from pre-failover workers) are rejected. In production, combine with Kafka consumer group rebalancing and transactional outbox pattern.

---

## Class Hierarchy

```
Task (domain entity, Builder pattern)          TaskExecution (execution attempt record)
  |-- id (UUID)                                  |-- id (UUID)
  |-- name                                       |-- taskId (references Task)
  |-- description                                |-- workerId (references Worker)
  |-- taskType: ONE_TIME | RECURRING             |-- attemptNumber (1, 2, 3...)
  |           | CRON | DELAYED                   |-- status: PENDING | RUNNING
  |-- priority: LOW(0) | MEDIUM(1)               |          | COMPLETED | FAILED
  |           | HIGH(2) | CRITICAL(3)            |-- startTime, endTime
  |-- status: PENDING | QUEUED | ASSIGNED        |-- result: TaskResult (success/failure)
  |         | RUNNING | COMPLETED | FAILED       |-- errorMessage
  |         | RETRYING | CANCELLED | TIMED_OUT   |-- getDuration() -> wall-clock time
  |-- payload: Map<String,String> (immutable)    |-- markStarted(), markCompleted(), markFailed()
  |-- cronExpression ("0 * * * *")
  |-- delayMillis (delayed execution)
  |-- maxRetries (default 3)
  |-- timeoutMillis (default 60s)
  |-- createdAt, updatedAt, scheduledAt
  |-- groupId (task group membership)
  |-- updateStatus(newStatus) -> state transition

Worker (worker node)                           SchedulerNode (scheduler cluster node)
  |-- id (UUID)                                  |-- nodeId
  |-- hostname, port                             |-- hostname
  |-- capacity (max concurrent tasks)            |-- priority (Bully algorithm rank)
  |-- currentLoad (active task count)            |-- isLeader (boolean)
  |-- status: ACTIVE | BUSY | DEAD | OFFLINE     |-- lastHeartbeat
  |-- lastHeartbeat (Instant)                    |-- startedAt
  |-- tags: Set<String> ("gpu", "high-memory")   |-- isAlive(timeout) -> boolean
  |-- incrementLoad() / decrementLoad()          |-- updateHeartbeat()
  |-- isAvailable() -> ACTIVE && load < capacity

TaskQueue (priority queue engine)              DependencyResolver (DAG engine)
  |-- PriorityQueue<Task> with Comparator:       |-- dependencies: Map<taskId, Set<depIds>>
  |     CRITICAL(3) > HIGH(2) > MEDIUM(1) > LOW  |-- addDependency(taskId, dependsOn)
  |     Ties: earlier createdAt first (FIFO)     |-- removeDependency(taskId, dependsOn)
  |-- enqueue(task) -> O(log n)                  |-- getReadyTasks(completedSet) -> Set<taskId>
  |-- dequeue() -> Optional<Task>, O(log n)      |-- hasCycle() -> DFS three-coloring
  |-- peek() -> Optional<Task>, O(1)             |     WHITE=unvisited, GRAY=in-stack, BLACK=done
  |-- remove(taskId) -> linear scan              |-- getTopologicalOrder() -> Kahn's BFS
  |-- getAllTasks() -> sorted snapshot            |     in-degree map, seed zero-deg, BFS peel

SchedulerEngine (central coordinator)          SchedulerService (Facade Pattern)
  |-- taskQueue: TaskQueue                       |-- taskService: TaskService
  |-- dependencyResolver: DependencyResolver     |-- workerService: WorkerService
  |-- cronParser: CronParser                     |-- executionService: ExecutionService
  |-- waitingOnDeps: Map<taskId, Task>           |-- engine: SchedulerEngine
  |-- cronTasks: Map<taskId, CronSchedule>       |-- assignmentStrategy: TaskAssignmentStrategy
  |-- cronLastFired: Map<taskId, Instant>        |-- schedulingStrategy: SchedulingStrategy
  |-- submitTask(task) -> enqueue                |-- submitTask(task) -> validate + enqueue
  |-- submitTaskWithDependencies(task, deps)     |-- scheduleAndDispatch() -> tick + assign
  |-- tick() -> check cron, resolve deps,        |-- cancelTask(taskId) -> cancel both layers
  |            drain queue -> List<Task>          |-- getTaskStatus(taskId) -> Optional<Status>
  |-- getNextTasks(maxBatch) -> List<Task>        |-- notifyTaskCompletion(taskId)

TaskAssignmentStrategy (Strategy Pattern)      RetryStrategy (Strategy Pattern)
  |-- RoundRobinAssignmentStrategy               |-- ExponentialBackoffRetryStrategy
  |     AtomicInteger counter, mod workers.size   |     initialDelay, maxDelay, multiplier
  |-- LeastLoadedAssignmentStrategy              |     delay = min(init * mult^(att-1), max)
  |     min(currentLoad), tie-break by age       |     jitter: +/-10% random factor
  |                                              |-- FixedIntervalRetryStrategy
SchedulingStrategy (Strategy Pattern)          |     constant delay between retries
  |-- ImmediateSchedulingStrategy                |-- shouldRetry(task, attempt, error) -> bool
  |-- CronSchedulingStrategy                     |-- getRetryDelayMillis(attempt) -> long
  |-- DelayedSchedulingStrategy

ExecutionService                               FailoverService
  |-- executeTask(task, worker) -> record exec   |-- detectDeadWorkers(timeout) -> List<Worker>
  |-- handleTaskCompletion(exec) -> update state |-- reassignTasks(deadWorkers) -> int count
  |-- handleTaskFailure(exec, error) -> retry?   |-- performFailover(timeout) -> detect + reassign
  |-- simulateExecution(task) -> TaskResult

LeaderElectionService                          MonitoringService
  |-- registerNode(node)                         |-- getTaskCountByStatus() -> Map
  |-- electLeader() -> Bully algorithm           |-- getWorkerUtilization() -> Map
  |-- handleNodeFailure(nodeId) -> re-elect      |-- getAverageExecutionTime() -> Duration
  |-- isLeader(nodeId) -> boolean                |-- getFailureRate() -> double
  |-- simulateHeartbeats()                       |-- getRetryRate() -> double
                                                 |-- getThroughput() -> long
AppConfig (Composition Root / Factory)           |-- printDashboard()
  |-- creates repositories (4 InMemory impls)
  |-- creates engine (TaskQueue + DependencyResolver + CronParser)
  |-- creates strategies (assignment, retry, scheduling)
  |-- creates services (Task, Worker, Execution, Scheduler, Leader, Failover, Monitoring)
  |-- creates controller + display
  |-- setAssignmentStrategy() / setRetryStrategy() / setSchedulingStrategy() -> swap and re-wire
```

---

## Key Components

| Component | Role |
|-----------|------|
| `Task` | Core domain entity. Represents a schedulable unit of work. Created via Builder pattern with fluent API. Tracks lifecycle from PENDING through QUEUED, ASSIGNED, RUNNING to terminal states (COMPLETED, FAILED, CANCELLED, TIMED_OUT). Immutable payload (Map), configurable retries, timeout, cron expression. |
| `TaskQueue` | The central scheduling data structure. PriorityQueue with custom Comparator: CRITICAL(3) > HIGH(2) > MEDIUM(1) > LOW(0), ties broken by createdAt ascending (FIFO). O(log n) enqueue/dequeue. Not thread-safe by design -- consumed only by the single-threaded scheduler loop. |
| `DependencyResolver` | DAG-based dependency tracker. Maintains adjacency list (taskId -> Set of dependencies). `getReadyTasks(completedSet)` returns tasks whose all upstream dependencies are in the completed set. Cycle detection via DFS three-coloring. Topological sort via Kahn's algorithm. |
| `SchedulerEngine` | The "brain" of the scheduler. On each `tick()`: (1) check CRON tasks and enqueue those whose fire time has passed, (2) resolve dependencies and move ready tasks into the priority queue, (3) expose `getNextTasks()` for the dispatcher. Coordinates TaskQueue, DependencyResolver, and CronParser. |
| `Worker` | Represents a worker node. Tracks hostname, port, capacity (max concurrent tasks), currentLoad (active tasks), status (ACTIVE/BUSY/DEAD/OFFLINE), heartbeat timestamp, and tags for affinity routing. Load management: incrementLoad()/decrementLoad() with automatic BUSY/ACTIVE transitions. |
| `SchedulerService` | **Facade Pattern** -- single entry point for the distributed task scheduler. Orchestrates TaskService, WorkerService, ExecutionService, SchedulerEngine, TaskAssignmentStrategy, and SchedulingStrategy into a unified API. `scheduleAndDispatch()` is the main dispatch loop. |
| `TaskAssignmentStrategy` | **Strategy Pattern** -- determines which worker gets a task. RoundRobinAssignmentStrategy cycles via AtomicInteger counter. LeastLoadedAssignmentStrategy picks min(currentLoad), tie-breaks by registeredAt. Both filter unavailable workers first. |
| `RetryStrategy` | **Strategy Pattern** -- determines retry behavior. ExponentialBackoffRetryStrategy: delay = min(initial * multiplier^(attempt-1), maxDelay) +/-10% jitter. FixedIntervalRetryStrategy: constant delay. `shouldRetry()` checks attempt <= maxRetries. |
| `LeaderElectionService` | Bully algorithm: highest-priority alive node wins. Registers nodes, runs election on demand, handles node failure (re-election if leader dies). Heartbeat-based liveness with configurable timeout. |
| `FailoverService` | Detects dead workers (heartbeat staleness > timeout), marks them DEAD, and reassigns their RUNNING tasks to healthy workers via the assignment strategy. Full cycle: detect -> reassign -> report. |
| `MonitoringService` | Observability dashboard. Computes task counts by status, worker utilization (load/capacity), average execution time, failure rate, retry rate, and throughput. Purely read-only -- no mutations. |
| `AppConfig` | **Factory Pattern + Composition Root** -- single wiring point for all dependencies. Lazily creates and wires repositories, engine, strategies, services, controller, and display. Strategy setters allow runtime swapping (clear dependents, re-create on next access). No DI framework. |

---

## Key Tradeoffs

| Decision | Option A | Option B | This Design |
|----------|----------|----------|-------------|
| Task queue implementation | Database-backed queue (poll with SELECT FOR UPDATE) | In-memory PriorityQueue | **In-memory PriorityQueue** -- O(log n) enqueue/dequeue with zero I/O overhead. At 10K tasks/sec, database polling adds 1-5ms per dequeue and creates hot-row contention under SELECT FOR UPDATE. In-memory is orders of magnitude faster. Durability is handled by persisting tasks to the repository before enqueuing; on restart, rebuild the queue from the task table. Production alternative: Redis sorted sets (ZADD/ZPOPMIN) for distributed priority queue with persistence. |
| Dependency resolution algorithm | Simple polling (check deps on each tick, O(n*m)) | Kahn's algorithm (topological sort, O(V+E)) | **Kahn's algorithm** -- computes the complete execution order in one pass. Combined with `getReadyTasks()` that checks if all deps are in the completed set, this gives O(V+E) dependency resolution vs O(n*m) naive polling. DFS three-coloring catches cycles at registration time (fail-fast) rather than at execution time (too late). For DAGs with 1000+ nodes (ML pipelines, ETL workflows), Kahn's is the only viable approach. |
| Leader election protocol | Raft consensus (partition-tolerant, log replication) | Bully algorithm (simple, highest-priority wins) | **Bully algorithm** for demo simplicity; **Raft in production**. Bully is O(n^2) messages and does not handle network partitions (split-brain risk). Raft guarantees safety under partitions via majority quorum and log replication. For interview: explain Bully to show you understand leader election mechanics, then say "in production I'd use etcd/Consul which implement Raft." |
| Worker assignment strategy | Round-robin (simple, even distribution) | Least-loaded (capacity-aware, respects heterogeneity) | **Both, via Strategy pattern**. Round-robin is the default for homogeneous clusters (all workers have same capacity). Switch to least-loaded when workers have different capacities (4-core vs 32-core machines). Strategy pattern allows runtime swapping without changing any service code. In production, add consistent hashing for stateful tasks and tag-based affinity for specialized hardware. |
| Retry mechanism | Fixed interval (constant delay between retries) | Exponential backoff with jitter | **Exponential backoff with jitter** -- prevents thundering herd when many tasks fail simultaneously (e.g., downstream service outage). 1000 tasks all retrying at exactly 5s would spike load. Jitter (+/-10%) spreads retries across a 20% window. Formula: `min(1000ms * 2^(attempt-1), 30000ms) * (0.9 + random * 0.2)`. Fixed interval is available as an alternative strategy for time-sensitive retry scenarios. |
| Exactly-once semantics | At-most-once (fire and forget, may lose tasks) | At-least-once + idempotent processing | **At-least-once + idempotent processing** -- in distributed systems, true exactly-once is impossible (Two Generals Problem). We achieve effective exactly-once via three layers: (1) idempotency key deduplication, (2) terminal state guards, (3) fencing tokens. The scheduler guarantees at-least-once delivery; the worker guarantees idempotent processing. This is how Kafka (idempotent producer + exactly-once semantics via transactions) and Temporal (workflow IDs + deterministic replay) achieve it. |
| Scheduler coordination | Single scheduler node (simple, single point of failure) | Multi-node with leader election | **Multi-node with leader election** -- single scheduler is a SPOF. With 3+ scheduler nodes and Bully/Raft election, the leader coordinates all dispatch while followers are hot standby. If the leader dies, re-election completes in < 5s and the new leader resumes dispatch from the persisted task queue. Tradeoff: more operational complexity (consensus protocol, heartbeat tuning, split-brain prevention). |

---

## Design Patterns

| Pattern | Where | Why |
|---------|-------|-----|
| **Strategy** | `TaskAssignmentStrategy`: RoundRobin vs LeastLoaded | Swap worker assignment algorithm without changing SchedulerService. Add ConsistentHash strategy for stateful tasks. |
| **Strategy** | `RetryStrategy`: ExponentialBackoff vs FixedInterval | Swap retry behavior per task type. CPU-bound tasks may use fixed interval; network tasks use exponential backoff. |
| **Strategy** | `SchedulingStrategy`: Immediate vs Cron vs Delayed | Swap scheduling trigger logic. Immediate dispatches now; Cron checks fire time; Delayed waits for delayMillis. |
| **Builder** | `Task.Builder` with fluent API | Task has 14+ fields. Builder avoids telescoping constructors, enforces required fields (name), defaults optional ones (priority=MEDIUM, maxRetries=3). |
| **Factory** | `AppConfig` as Composition Root | Lazily creates and wires all dependencies. No DI framework needed. Strategy setters clear dependents for re-creation. Single entry point for demo and test. |
| **Repository** | `TaskRepository`, `WorkerRepository`, `ExecutionRepository`, `SchedulerNodeRepository` (4 interfaces + 4 InMemory impls) | Abstract data access. Swap InMemory for JPA/DynamoDB implementations without touching service logic. Each repo has findById, findAll, save, delete, plus domain-specific queries. |
| **Facade** | `SchedulerService` orchestrates Task, Worker, Execution services + Engine + Strategies | Single unified API for the scheduler. Callers (Controller) interact with one class instead of 6. Hides internal coordination complexity. |
| **State** | `TaskStatus` enum: PENDING -> QUEUED -> ASSIGNED -> RUNNING -> COMPLETED/FAILED/RETRYING/CANCELLED/TIMED_OUT | Enforces valid state transitions. `isTerminal()` and `isActive()` predicates enable guard checks. Prevents invalid operations (can't cancel a COMPLETED task). |
| **Observer** | Task completion triggers dependency resolution in SchedulerEngine | When a task completes, `notifyTaskCompletion()` tells the engine to re-check dependent tasks. Decouples execution from dependency management. |
| **Singleton** | `AppConfig` lazy initialization (each getter creates instance once) | Ensures single instance of each service/repository. Lazy creation defers expensive initialization. Thread-unsafe by design (single-threaded demo). |

---

## Real-World Use Cases & Industry Applications

### 1. ETL / Data Pipeline Orchestration (Airflow at Spotify, Uber, Airbnb)
**Problem:** Nightly data pipeline: extract 500M rows from 12 source databases → transform (clean, join, aggregate) → load into Snowflake data warehouse → generate BI dashboards.
**How this system solves it:** DAG dependency resolution ensures Extract runs before Transform before Load. Kahn's algorithm computes optimal execution order. Parallel branches (multiple Extract jobs) run simultaneously. If Transform fails, exponential backoff retries 3x before alerting the on-call data engineer.
**Production examples:** Spotify runs 30,000+ Airflow DAGs daily for music recommendation pipelines. Uber's data platform processes 100+ PB with scheduled ETL jobs. Airbnb's Airflow instance manages search ranking model training pipelines.

### 2. Scheduled Email & Notification Campaigns (Braze, Mailchimp, Twilio)
**Problem:** Marketing platform needs to schedule "send 5M promotional emails at 9 AM EST Tuesday, throttled at 50K/min to avoid ESP rate limits."
**How this system solves it:** Task priority queue ensures time-critical campaigns (CRITICAL) execute before routine newsletters (LOW). Worker pool with least-loaded assignment distributes email rendering across 20 workers. Cron scheduling triggers the campaign at the exact time. Exactly-once execution via idempotency keys prevents duplicate sends (double-emailing a customer is a trust violation).
**Production examples:** Braze processes 2.5B+ messages/day using task scheduling for campaign orchestration. Mailchimp schedules millions of campaigns with time-zone-aware cron triggers.

### 3. ML Pipeline & Hyperparameter Tuning (Kubeflow, MLflow, Metaflow)
**Problem:** Train 100 model variants with different hyperparameters: each variant has DAG: preprocess data → train model → evaluate → register best model.
**How this system solves it:** Task groups with parallel execution dispatch all 100 training jobs simultaneously. DAG dependencies ensure preprocessing completes before training starts. Worker affinity tags route GPU-intensive training jobs to GPU workers. Failover reassigns failed training jobs (OOM on a worker) to healthier nodes.
**Production examples:** Netflix's Metaflow orchestrates ML workflows for recommendation models. Google's Vertex AI Pipelines schedules training jobs with DAG dependencies. Spotify uses Luigi/Airflow for audio feature extraction pipelines.

### 4. Financial Batch Processing (Stripe, Square, Banks)
**Problem:** End-of-day settlement: reconcile 10M transactions, compute merchant payouts, generate compliance reports — must complete before market open.
**How this system solves it:** Priority queue ensures compliance-critical jobs (regulatory reports) execute first. DAG ensures reconciliation completes before payout calculation starts. Exactly-once execution prevents double-crediting merchants. Leader election ensures single coordinator avoids split-brain during settlement window.
**Production examples:** Stripe processes billions in daily settlements using scheduled batch jobs. Banks run end-of-day batch processes (ACH, wire transfers, regulatory reporting) with strict ordering requirements.

### 5. Infrastructure Automation & Cron Jobs (Kubernetes CronJobs, Rundeck)
**Problem:** Run 500 periodic maintenance tasks: log rotation (hourly), database vacuuming (daily), SSL certificate renewal (monthly), temp file cleanup (every 6 hours).
**How this system solves it:** Cron scheduling with expression parsing ("0 * * * *" for hourly). Worker pool distributes maintenance across cluster nodes. Dead worker detection ensures no task is silently dropped if a node dies mid-execution.
**Production examples:** Kubernetes CronJobs manage periodic workloads across clusters. HashiCorp Nomad schedules batch and periodic jobs. Every SaaS company runs hundreds of cron-like background jobs.

### 6. Event-Driven Workflow Orchestration (Temporal at Netflix, Snap, Uber)
**Problem:** Order fulfillment saga: reserve inventory → charge payment → ship order → send confirmation — with compensation (refund) if any step fails.
**How this system solves it:** DAG models the saga steps. Task status tracking (RUNNING → COMPLETED/FAILED) determines whether to proceed or compensate. Retry with backoff handles transient payment gateway failures. Exactly-once prevents double-charging.
**Production examples:** Uber uses Cadence/Temporal for trip lifecycle management (matching → pricing → payment → receipt). Netflix uses Temporal for content encoding workflows. Snap uses Temporal for ad delivery pipelines.

---

## How to Run

```bash
cd /Users/kanojik/Documents/Karan/Autodesk_AI_Development/systemDesign
./gradlew :17-distributed-task-scheduler:run
```

---

## Demo Output Preview

```
======================================================================
   DISTRIBUTED TASK SCHEDULER -- System Design Demo
   Staff Engineer Interview Prep: Consensus, DAG, Exactly-Once
======================================================================

[SETUP] Registered 3 workers: alpha(cap=4), beta(cap=3), gamma(cap=5)

======================================================================
  DEMO 1: Submit and Execute Simple Tasks
======================================================================
[CONTROLLER] POST /tasks -- submitting task: Generate daily report
[TASK SERVICE] Created task: Task{id='...', name='Generate daily report', type=ONE_TIME, priority=MEDIUM, status=PENDING}
[SCHEDULER] Submitted task: Generate daily report
[CONTROLLER] POST /tasks -- submitting task: Send welcome emails
[SCHEDULER] Submitted task: Send welcome emails
[CONTROLLER] POST /tasks -- submitting task: Cleanup temp files
[SCHEDULER] Submitted task: Cleanup temp files
[CONTROLLER] POST /scheduler/dispatch -- running scheduling cycle
[SCHEDULER] Starting dispatch round...
[SCHEDULER] Dispatching 3 task(s) to 3 available worker(s)
[SCHEDULER] Dispatching 'Send welcome emails' -> node-alpha.cluster.local
[EXECUTION] Task 'Send welcome emails' started on worker node-alpha (attempt #1)
[EXECUTION] Task 'Send welcome emails' completed successfully
[SCHEDULER] Dispatching 'Generate daily report' -> node-beta.cluster.local
[SCHEDULER] Dispatching 'Cleanup temp files' -> node-gamma.cluster.local

  KEY INSIGHT: Tasks are queued by priority (HIGH > MEDIUM > LOW),
  then dispatched to workers via the assignment strategy.

======================================================================
  DEMO 3: Task Dependency DAG (Topological Sort)
======================================================================
[DEMO] DAG Structure:
  Extract --> Transform --> Load
     |                       ^
     +----> Validate --------+

[DEMO] Topological order (Kahn's algorithm):
  Step 1: Extract raw data
  Step 2: Transform data
  Step 3: Validate data quality
  Step 4: Load to warehouse

[DEMO] After completing 'Extract', ready tasks:
  -> Transform data
  -> Validate data quality

  KEY INSIGHT: Kahn's algorithm resolves execution order. Tasks only
  become 'ready' when ALL upstream dependencies are complete.

======================================================================
  DEMO 8: Leader Election (Bully Algorithm)
======================================================================
[DEMO] Registered 3 scheduler nodes:
  scheduler-1 (priority=10), scheduler-2 (priority=20), scheduler-3 (priority=30)

[DEMO] Running Bully Algorithm election...
[LEADER ELECTION] Starting bully algorithm election...
[LEADER ELECTION] Node scheduler-1 (priority=10) challenged by 2 higher-priority nodes
[LEADER ELECTION] Node scheduler-2 (priority=20) challenged by 1 higher-priority nodes
[LEADER ELECTION] Node scheduler-3 (priority=30) has no challengers
[LEADER ELECTION] Elected leader: scheduler-3

[DEMO] Simulating leader failure (scheduler-3 goes down)...
[LEADER ELECTION] Failed node was the leader -- triggering re-election
[LEADER ELECTION] Elected leader: scheduler-2
[DEMO] New leader: scheduler-2 (priority=20)

  KEY INSIGHT: Bully algorithm -- highest-priority alive node wins.
  O(n^2) messages in worst case. In production, use Raft/ZooKeeper/etcd.

======================================================================
  DEMO 10: Task Priority Queue
======================================================================
[DEMO] Submitted 4 tasks in order: LOW, CRITICAL, MEDIUM, HIGH

[DEMO] Dequeue order (priority queue):
  1. [CRITICAL] Critical: security patch
  2. [HIGH] High: process payments
  3. [MEDIUM] Medium: generate report
  4. [LOW] Low: cleanup logs

  KEY INSIGHT: PriorityQueue with Comparator: CRITICAL > HIGH > MEDIUM > LOW.
  Ties broken by createdAt (FIFO within same priority).

========================================
       SCHEDULER MONITORING DASHBOARD
========================================

  Task Status Breakdown:
    COMPLETED    : 8
    FAILED       : 1
    RETRYING     : 1
    QUEUED       : 3
    PENDING      : 2

  Worker Utilization:
    worker-001   : 25.0%
    worker-002   : 66.7%
    worker-003   : 20.0%

  Execution Metrics:
    Avg Execution Time : 12ms
    Failure Rate       : 8.3%
    Retry Rate         : 12.5%
    Throughput         : 8 completed tasks

======================================================================
  End of Distributed Task Scheduler Demo
======================================================================
```

---

## Quick Reference

```
Priority Queue:      PriorityQueue + Comparator: CRITICAL(3) > HIGH(2) > MEDIUM(1) > LOW(0). FIFO within priority. O(log n).
DAG Resolution:      Kahn's algorithm: in-degree map, seed zero-deg queue, BFS peel. Cycle detection: DFS three-coloring (GRAY->GRAY = back edge).
Leader Election:     Bully algorithm: highest-priority alive node wins. Re-election on leader failure. O(n^2) messages.
Worker Assignment:   Round-robin (AtomicInteger mod N) for homogeneous. Least-loaded (min currentLoad) for heterogeneous. Strategy pattern.
Retry:               Exponential backoff: min(1s * 2^(attempt-1), 30s) +/-10% jitter. Prevents thundering herd. maxRetries guard.
Exactly-Once:        Idempotency key in payload + terminal state guard + fencing token. At-least-once delivery + idempotent processing.
Cron Scheduling:     5-field cron parser (min hour dom month dow). Tick() checks fire time. CronSchedule model for evaluation.
Worker Failover:     Heartbeat-based liveness. Dead worker detection -> reassign RUNNING tasks to healthy workers. Gossip in production.
Task Lifecycle:      PENDING -> QUEUED -> ASSIGNED -> RUNNING -> COMPLETED | FAILED | RETRYING | CANCELLED | TIMED_OUT
Task Groups:         Parallel (all tasks dispatch simultaneously) or Sequential (implicit chain dependencies). GroupId for tracking.
Monitoring:          Task counts by status, worker utilization, avg execution time, failure rate, retry rate, throughput.
CAP:                 CP for task state (zero tolerance for lost/duplicate tasks). AP for monitoring (stale metrics OK for 30s).
```

---

## What to Improve Later

- [ ] Distributed priority queue via Redis sorted sets (ZADD/ZPOPMIN) replacing in-memory PriorityQueue
- [ ] Raft consensus replacing Bully algorithm for partition-tolerant leader election
- [ ] Consistent hashing strategy for stateful task assignment (cache locality)
- [ ] Dead-letter queue for permanently failed tasks with alerting
- [ ] Task timeout enforcement with a separate watchdog timer thread
- [ ] Rate limiting on task submission (per-user and global throttling)
- [ ] Task versioning and schema evolution for payload compatibility
- [ ] Multi-tenant isolation with namespace-based task partitioning
- [ ] Workflow DSL for expressing complex DAG pipelines declaratively
- [ ] Backpressure mechanism when queue depth exceeds threshold
- [ ] Distributed tracing (OpenTelemetry) for cross-service task tracking
- [ ] Blue-green deployment support for zero-downtime scheduler upgrades
