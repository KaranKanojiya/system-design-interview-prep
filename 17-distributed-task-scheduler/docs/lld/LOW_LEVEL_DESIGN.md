# Low-Level Design: Distributed Task Scheduler

> Project 17 -- Staff-level system design implementation in pure Java.
> 50 source files, 10 GoF design patterns, 12 runnable demos.

---

## Table of Contents

1. [Core Modules Overview](#1-core-modules-overview)
2. [Package Structure](#2-package-structure)
3. [Class Diagram](#3-class-diagram)
4. [Entity Design](#4-entity-design)
5. [Interface Contracts](#5-interface-contracts)
6. [Strategy Implementations](#6-strategy-implementations)
7. [Engine Design](#7-engine-design)
8. [Service Layer Design](#8-service-layer-design)
9. [Concurrency Considerations](#9-concurrency-considerations)
10. [SOLID Principles Applied](#10-solid-principles-applied)
11. [Sample Workflows](#11-sample-workflows)
12. [Design Patterns Used](#12-design-patterns-used)
13. [Extensibility Points](#13-extensibility-points)

---

## 1. Core Modules Overview

The scheduler is decomposed into eight packages, each owning a distinct
responsibility. Dependencies flow downward: controller -> service -> engine/repository -> model.

| Module | Responsibility | Key Insight |
|--------|---------------|-------------|
| **model** | Domain entities and value objects | Immutable where possible; `Task` uses Builder |
| **engine** | Scheduling core: priority queue, DAG resolver, cron parser | Pure algorithms -- no I/O, no persistence |
| **repository** | Storage abstraction (interface + in-memory impl) | Swap for JDBC/DynamoDB without touching services |
| **service** | Business logic orchestration | Each service owns one concern (SRP) |
| **strategy** | Pluggable algorithms for scheduling, retry, assignment | Strategy pattern -- swap at runtime via AppConfig |
| **controller** | REST-like facade for demo entry points | Thin delegation layer; no business logic |
| **config** | Composition root / dependency wiring | Factory pattern; lazy singleton initialization |
| **display** | Console output formatting for demos | Read-only; never mutates state |
| **exception** | Typed exception hierarchy | Each exception carries domain context (taskId, etc.) |

---

## 2. Package Structure

```
com.systemdesign.scheduler
├── config/                         # 1 file
│   └── AppConfig.java              # Factory + Composition Root (lazy Singleton)
├── controller/                     # 1 file
│   └── SchedulerController.java    # REST-like facade (POST /tasks, etc.)
├── display/                        # 1 file
│   └── SchedulerStatsDisplay.java  # Console output helper (tables, DAG)
├── engine/                         # 4 files
│   ├── CronParser.java             # 5-field cron expression parser
│   ├── DependencyResolver.java     # DAG + Kahn's topological sort + cycle detection
│   ├── SchedulerEngine.java        # Central coordinator (queue + deps + cron)
│   └── TaskQueue.java              # PriorityQueue wrapper (CRITICAL > HIGH > MEDIUM > LOW)
├── exception/                      # 5 files
│   ├── SchedulerException.java     # Base exception (RuntimeException)
│   ├── DependencyCycleException.java
│   ├── LeaderElectionException.java
│   ├── TaskExecutionException.java
│   └── WorkerUnavailableException.java
├── model/                          # 13 files
│   ├── Task.java                   # Core entity (Builder pattern)
│   ├── TaskExecution.java          # Execution attempt record
│   ├── Worker.java                 # Worker node (capacity, load, tags)
│   ├── SchedulerNode.java          # Cluster node (leader election)
│   ├── TaskDependency.java         # Directed edge in the DAG
│   ├── TaskGroup.java              # Batch of tasks (parallel/sequential)
│   ├── TaskResult.java             # Immutable execution result
│   ├── RetryPolicy.java            # Retry configuration (backoff params)
│   ├── CronSchedule.java           # Parsed cron expression
│   ├── TaskStatus.java             # Lifecycle enum (9 states)
│   ├── TaskPriority.java           # Priority enum (LOW=0..CRITICAL=3)
│   ├── TaskType.java               # ONE_TIME, RECURRING, CRON, DELAYED
│   └── WorkerStatus.java           # ACTIVE, BUSY, DRAINING, OFFLINE, DEAD
├── repository/                     # 8 files (4 interfaces + 4 impls)
│   ├── TaskRepository.java         # Interface: Task CRUD
│   ├── InMemoryTaskRepository.java
│   ├── WorkerRepository.java       # Interface: Worker CRUD + findAvailable()
│   ├── InMemoryWorkerRepository.java
│   ├── ExecutionRepository.java    # Interface: Execution history
│   ├── InMemoryExecutionRepository.java
│   ├── SchedulerNodeRepository.java # Interface: Cluster node registry
│   └── InMemorySchedulerNodeRepository.java
├── service/                        # 7 files
│   ├── SchedulerService.java       # Facade -- single entry point
│   ├── TaskService.java            # Task lifecycle management
│   ├── WorkerService.java          # Worker pool management
│   ├── ExecutionService.java       # Task execution + retry
│   ├── LeaderElectionService.java  # Bully algorithm
│   ├── FailoverService.java        # Heartbeat detection + reassignment
│   └── MonitoringService.java      # Metrics and dashboard
├── strategy/                       # 10 files (3 interfaces + 7 impls)
│   ├── scheduling/
│   │   ├── SchedulingStrategy.java      # Interface
│   │   ├── ImmediateSchedulingStrategy.java
│   │   ├── CronSchedulingStrategy.java
│   │   └── DelayedSchedulingStrategy.java
│   ├── retry/
│   │   ├── RetryStrategy.java           # Interface
│   │   ├── ExponentialBackoffRetryStrategy.java
│   │   └── FixedIntervalRetryStrategy.java
│   └── assignment/
│       ├── TaskAssignmentStrategy.java  # Interface
│       ├── RoundRobinAssignmentStrategy.java
│       └── LeastLoadedAssignmentStrategy.java
└── DistributedTaskSchedulerApp.java # Main -- 12 demos
```

**Total: 50 Java files across 9 packages.**

---

## 3. Class Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                            DISTRIBUTED TASK SCHEDULER                                    │
│                              Class Dependency Overview                                   │
└─────────────────────────────────────────────────────────────────────────────────────────┘

    ┌───────────────────┐
    │DistributedTask    │
    │SchedulerApp       │        ┌──────────────────┐
    │  main()           │───────>│   AppConfig       │ (Factory + Composition Root)
    └───────────────────┘        │  getXxx()         │
                                 │  setStrategy()    │
                                 └──────┬───────────┘
                                        │ creates all objects
                    ┌───────────────────┼──────────────────┐
                    │                   │                   │
                    v                   v                   v
    ┌───────────────────┐  ┌──────────────────┐  ┌─────────────────────┐
    │SchedulerController│  │SchedulerStats    │  │  SchedulerService   │ (Facade)
    │  submitTask()     │  │  Display         │  │  submitTask()       │
    │  runCycle()       │  │  printTasks()    │  │  scheduleAndDispatch│
    │  getDashboard()   │  │  printWorkers()  │  │  cancelTask()      │
    └──────┬──────┬─────┘  └─────────────────┘  └──┬──┬──┬──┬──┬─────┘
           │      │                                 │  │  │  │  │
           │      └─────────────────────────────────┘  │  │  │  │
           │                                           │  │  │  │
           v                                           │  │  │  │
    ┌──────────────────┐       ┌──────────────────┐    │  │  │  │
    │MonitoringService │       │ SchedulerEngine  │<───┘  │  │  │
    │  getTaskCounts() │       │  taskQueue       │       │  │  │
    │  getFailureRate()│       │  depResolver     │       │  │  │
    │  printDashboard()│       │  cronTasks       │       │  │  │
    └──────────────────┘       │  tick()          │       │  │  │
                               │  getNextTasks()  │       │  │  │
                               └──┬──────┬────────┘       │  │  │
                                  │      │                │  │  │
                                  v      v                │  │  │
                    ┌──────────┐  ┌────────────────┐      │  │  │
                    │TaskQueue │  │DependencyRes.  │      │  │  │
                    │ PQ<Task> │  │ adjacencyList  │      │  │  │
                    │ enqueue()│  │ getReadyTasks()│      │  │  │
                    │ dequeue()│  │ getTopoOrder() │      │  │  │
                    └──────────┘  │ hasCycle()     │      │  │  │
                                  └────────────────┘      │  │  │
                                                          │  │  │
        ┌─────────────────────────────────────────────────┘  │  │
        v                                                    │  │
    ┌──────────────────┐    ┌──────────────────────┐         │  │
    │  TaskService     │    │  WorkerService       │<────────┘  │
    │  createTask()    │    │  registerWorker()    │            │
    │  updateStatus()  │    │  getAvailableWorkers │            │
    │  cancelTask()    │    │  markWorkerDead()    │            │
    └──────┬───────────┘    └──────┬───────────────┘            │
           │                       │                            │
           v                       v                            v
    ┌──────────────┐    ┌────────────────┐    ┌──────────────────────┐
    │TaskRepository│    │WorkerRepository│    │  ExecutionService    │
    │ (interface)  │    │ (interface)    │    │  executeTask()       │
    └──────┬───────┘    └──────┬─────────┘    │  handleCompletion()  │
           │                   │              │  handleFailure()     │
           v                   v              └──┬──────────┬───────┘
    ┌──────────────┐    ┌────────────────┐       │          │
    │InMemoryTask  │    │InMemoryWorker  │       v          v
    │  Repository  │    │  Repository    │  ┌──────────┐  ┌────────────┐
    │ CHM<id,Task> │    │ CHM<id,Worker> │  │ExecRepo  │  │RetryStrategy│
    └──────────────┘    └────────────────┘  │(interface)│  │(interface) │
                                            └──────────┘  └────────────┘

    ┌──────────────────────────────────────────────────────────────────┐
    │                     STRATEGY INTERFACES                          │
    │                                                                  │
    │  <<SchedulingStrategy>>     <<RetryStrategy>>                    │
    │  ├─ Immediate               ├─ ExponentialBackoff                │
    │  ├─ Cron                    └─ FixedInterval                     │
    │  └─ Delayed                                                      │
    │                                                                  │
    │  <<TaskAssignmentStrategy>>   <<Repository>>                     │
    │  ├─ RoundRobin               ├─ TaskRepository                   │
    │  └─ LeastLoaded              ├─ WorkerRepository                 │
    │                              ├─ ExecutionRepository              │
    │                              └─ SchedulerNodeRepository          │
    └──────────────────────────────────────────────────────────────────┘

    ┌──────────────────────────────────────────────────────────────────┐
    │                   CLUSTER / DISTRIBUTED                          │
    │                                                                  │
    │  LeaderElectionService ──→ SchedulerNodeRepository               │
    │    electLeader()             findLeader()                        │
    │    handleNodeFailure()       findAlive(timeout)                  │
    │                                                                  │
    │  FailoverService ──→ WorkerService + TaskService + ExecRepo      │
    │    detectDeadWorkers()       + TaskAssignmentStrategy             │
    │    reassignTasks()                                                │
    └──────────────────────────────────────────────────────────────────┘
```

---

## 4. Entity Design

### 4.1 Task (Builder Pattern)

The central domain entity. Created exclusively through the fluent `Builder` API.
The constructor is private -- only `Builder.build()` can instantiate a Task.

```
┌──────────────────────────────────────────────────────────────┐
│                           Task                                │
├──────────────────────────────────────────────────────────────┤
│ - id: String (UUID, final)                                   │
│ - name: String (final, required)                             │
│ - description: String (final, default="")                    │
│ - taskType: TaskType (final, default=ONE_TIME)               │
│ - priority: TaskPriority (final, default=MEDIUM)             │
│ - status: TaskStatus (mutable, default=PENDING)              │
│ - payload: Map<String,String> (final, unmodifiable copy)     │
│ - cronExpression: String (final, nullable)                   │
│ - delayMillis: long (final, default=0)                       │
│ - maxRetries: int (final, default=3)                         │
│ - timeoutMillis: long (final, default=60000)                 │
│ - createdAt: Instant (final, default=now)                    │
│ - updatedAt: Instant (mutable)                               │
│ - scheduledAt: Instant (final, nullable)                     │
│ - groupId: String (final, nullable)                          │
├──────────────────────────────────────────────────────────────┤
│ + getId(): String                                            │
│ + getName(): String                                          │
│ + getStatus(): TaskStatus                                    │
│ + getPriority(): TaskPriority                                │
│ + getPayload(): Map<String,String>                           │
│ + getCronExpression(): String                                │
│ + getDelayMillis(): long                                     │
│ + getMaxRetries(): int                                       │
│ + getTimeoutMillis(): long                                   │
│ + getCreatedAt(): Instant                                    │
│ + getScheduledAt(): Instant                                  │
│ + getGroupId(): String                                       │
│ + updateStatus(TaskStatus): void     // state transition     │
│ + builder(String name): Builder      // static factory       │
├──────────────────────────────────────────────────────────────┤
│  <<static inner>> Builder                                    │
│  + Builder(String name)                                      │
│  + id(String): Builder                                       │
│  + description(String): Builder                              │
│  + taskType(TaskType): Builder                               │
│  + priority(TaskPriority): Builder                           │
│  + status(TaskStatus): Builder                               │
│  + payload(Map): Builder                                     │
│  + addPayload(String,String): Builder                        │
│  + cronExpression(String): Builder                           │
│  + delayMillis(long): Builder                                │
│  + maxRetries(int): Builder                                  │
│  + timeoutMillis(long): Builder                              │
│  + scheduledAt(Instant): Builder                             │
│  + groupId(String): Builder                                  │
│  + build(): Task                                             │
└──────────────────────────────────────────────────────────────┘
```

**Design decisions:**
- `payload` is defensively copied into an unmodifiable map in the constructor.
- `status` and `updatedAt` are the only mutable fields -- state transitions are
  explicit via `updateStatus()`.
- `name` is the only required parameter (enforced by Builder constructor).
- All other fields have sensible defaults.

### 4.2 TaskExecution

Tracks a single execution attempt of a Task on a specific Worker.
Multiple records per task (one per retry attempt).

```
┌──────────────────────────────────────────────────────────────┐
│                       TaskExecution                           │
├──────────────────────────────────────────────────────────────┤
│ - id: String (UUID, final)                                   │
│ - taskId: String (final)                                     │
│ - workerId: String (final)                                   │
│ - attemptNumber: int (final)                                 │
│ - status: TaskStatus (mutable, starts PENDING)               │
│ - startTime: Instant (set on markStarted)                    │
│ - endTime: Instant (set on markCompleted/markFailed)         │
│ - result: TaskResult (set on completion/failure)             │
│ - errorMessage: String (set on failure)                      │
├──────────────────────────────────────────────────────────────┤
│ + markStarted(): void        // PENDING -> RUNNING           │
│ + markCompleted(TaskResult): void  // RUNNING -> COMPLETED   │
│ + markFailed(String): void   // RUNNING -> FAILED            │
│ + getDuration(): Duration    // wall-clock execution time    │
│ + getId(): String                                            │
│ + getTaskId(): String                                        │
│ + getWorkerId(): String                                      │
│ + getAttemptNumber(): int                                    │
│ + getStatus(): TaskStatus                                    │
│ + getResult(): TaskResult                                    │
└──────────────────────────────────────────────────────────────┘
```

**Design decisions:**
- `getDuration()` returns wall-clock time; returns `Duration.ZERO` if not started.
- Status transitions are explicit methods, not a generic setter.

### 4.3 Worker

Represents a worker node that pulls and executes tasks.

```
┌──────────────────────────────────────────────────────────────┐
│                          Worker                               │
├──────────────────────────────────────────────────────────────┤
│ - id: String (final)                                         │
│ - hostname: String (final)                                   │
│ - port: int (final)                                          │
│ - capacity: int (final -- max concurrent tasks)              │
│ - currentLoad: int (mutable)                                 │
│ - status: WorkerStatus (mutable, starts ACTIVE)              │
│ - lastHeartbeat: Instant (mutable)                           │
│ - registeredAt: Instant (final)                              │
│ - tags: Set<String> (mutable -- e.g., "gpu", "high-memory") │
├──────────────────────────────────────────────────────────────┤
│ + incrementLoad(): void      // load++; BUSY if at capacity  │
│ + decrementLoad(): void      // load--; back to ACTIVE       │
│ + isAvailable(): boolean     // ACTIVE && load < capacity    │
│ + updateHeartbeat(): void    // lastHeartbeat = now()        │
│ + addTag(String): void                                       │
│ + hasTag(String): boolean                                    │
│ + getId(): String                                            │
│ + getHostname(): String                                      │
│ + getCapacity(): int                                         │
│ + getCurrentLoad(): int                                      │
│ + getStatus(): WorkerStatus                                  │
│ + setStatus(WorkerStatus): void                              │
│ + getLastHeartbeat(): Instant                                │
│ + getRegisteredAt(): Instant                                 │
│ + getTags(): Set<String>                                     │
└──────────────────────────────────────────────────────────────┘
```

**Design decisions:**
- `incrementLoad()` auto-transitions to BUSY when `currentLoad >= capacity`.
- `decrementLoad()` auto-transitions back to ACTIVE when below capacity.
- Tags support affinity-based routing (e.g., GPU tasks -> GPU workers).

### 4.4 SchedulerNode

Represents a node in the scheduler cluster, participating in leader election.

```
┌──────────────────────────────────────────────────────────────┐
│                       SchedulerNode                           │
├──────────────────────────────────────────────────────────────┤
│ - nodeId: String (final)                                     │
│ - hostname: String (final)                                   │
│ - isLeader: boolean (mutable)                                │
│ - lastHeartbeat: Instant (mutable)                           │
│ - startedAt: Instant (final)                                 │
│ - priority: int (final -- higher wins election)              │
├──────────────────────────────────────────────────────────────┤
│ + updateHeartbeat(): void                                    │
│ + isAlive(Duration timeout): boolean                         │
│ + isLeader(): boolean                                        │
│ + setLeader(boolean): void                                   │
│ + getPriority(): int                                         │
│ + getNodeId(): String                                        │
└──────────────────────────────────────────────────────────────┘
```

### 4.5 Supporting Value Objects

| Class | Type | Fields | Purpose |
|-------|------|--------|---------|
| `TaskStatus` | enum | PENDING, QUEUED, ASSIGNED, RUNNING, COMPLETED, FAILED, RETRYING, CANCELLED, TIMED_OUT | Lifecycle state machine. `isTerminal()` and `isActive()` helpers. |
| `TaskPriority` | enum | LOW(0), MEDIUM(1), HIGH(2), CRITICAL(3) | PriorityQueue ordering. `isHigherThan()` comparator. |
| `TaskType` | enum | ONE_TIME, RECURRING, CRON, DELAYED | Determines scheduling strategy selection. |
| `WorkerStatus` | enum | ACTIVE, BUSY, DRAINING, OFFLINE, DEAD | Worker liveness states. |
| `TaskResult` | class | success, output, metadata | Immutable. Static factories: `success(output)`, `failure(error)`. |
| `RetryPolicy` | class | maxRetries, initialDelayMillis, maxDelayMillis, backoffMultiplier | Static factories: `noRetry()`, `fixedInterval()`, `exponentialBackoff()`. `getDelayForAttempt(int)` computes delay. |
| `CronSchedule` | class | minute, hour, dayOfMonth, month, dayOfWeek, expression | Parsed 5-field cron. `matches(Instant)` checks if current time fits. |
| `TaskDependency` | class | taskId, dependsOnTaskId | Directed edge in the DAG. `equals()`/`hashCode()` on both fields. |
| `TaskGroup` | class | id, name, taskIds, parallel, createdAt | Batch container. `parallel` flag: true = all at once, false = sequential. |

---

## 5. Interface Contracts

### 5.1 Strategy Interfaces

#### SchedulingStrategy

```java
public interface SchedulingStrategy {
    boolean shouldScheduleNow(Task task, Instant currentTime);
    Optional<Instant> getNextScheduleTime(Task task, Instant currentTime);
    String getStrategyName();
}
```

- **Contract:** Given a task and the current time, return whether it should fire now
  and when it should fire next.
- **Implementations:** ImmediateSchedulingStrategy, CronSchedulingStrategy, DelayedSchedulingStrategy.

#### RetryStrategy

```java
public interface RetryStrategy {
    boolean shouldRetry(Task task, int attemptNumber, String errorMessage);
    long getRetryDelayMillis(int attemptNumber);
    String getStrategyName();
}
```

- **Contract:** Given a failed task, attempt number, and error, decide whether to retry
  and how long to wait.
- **Implementations:** ExponentialBackoffRetryStrategy, FixedIntervalRetryStrategy.

#### TaskAssignmentStrategy

```java
public interface TaskAssignmentStrategy {
    Optional<Worker> assignTask(Task task, List<Worker> availableWorkers);
    String getStrategyName();
}
```

- **Contract:** Given a task and a list of available workers, pick the best worker.
  Returns `Optional.empty()` if no worker can handle the task.
- **Implementations:** RoundRobinAssignmentStrategy, LeastLoadedAssignmentStrategy.

### 5.2 Repository Interfaces

#### TaskRepository

```java
public interface TaskRepository {
    void save(Task task);
    Optional<Task> findById(String id);
    List<Task> findByStatus(TaskStatus status);
    List<Task> findByGroupId(String groupId);
    List<Task> findAll();
    void deleteById(String id);
    boolean existsById(String id);
}
```

#### WorkerRepository

```java
public interface WorkerRepository {
    void save(Worker worker);
    Optional<Worker> findById(String id);
    List<Worker> findByStatus(WorkerStatus status);
    List<Worker> findAvailable();       // ACTIVE + spare capacity
    List<Worker> findAll();
    void deleteById(String id);
}
```

#### ExecutionRepository

```java
public interface ExecutionRepository {
    void save(TaskExecution execution);
    Optional<TaskExecution> findById(String id);
    List<TaskExecution> findByTaskId(String taskId);
    List<TaskExecution> findByWorkerId(String workerId);
    Optional<TaskExecution> findLatestByTaskId(String taskId);  // most recent by startTime
    List<TaskExecution> findAll();
}
```

#### SchedulerNodeRepository

```java
public interface SchedulerNodeRepository {
    void save(SchedulerNode node);
    Optional<SchedulerNode> findById(String nodeId);
    Optional<SchedulerNode> findLeader();
    List<SchedulerNode> findAll();
    List<SchedulerNode> findAlive(Duration timeout);   // heartbeat within timeout
    void deleteById(String nodeId);
}
```

---

## 6. Strategy Implementations

### 6.1 Scheduling Strategies

#### ImmediateSchedulingStrategy

```
Trigger condition:  taskType == ONE_TIME  &&  status in {PENDING, QUEUED}
Next schedule time: Instant.now() (always fires immediately)
```

- Default strategy when no cron expression or delay is configured.
- Used for fire-and-forget one-time tasks.

#### CronSchedulingStrategy

```
Trigger condition:  cronExpression != null  &&  CronSchedule.matches(currentTime)
Next schedule time: CronParser.getNextFireTime(schedule, currentTime)
```

- Delegates cron parsing to `CronParser` from the engine layer.
- `CronParser.getNextFireTime()` scans minute-by-minute up to 24 hours ahead.
- Dependency: requires `CronParser` injection via constructor.

#### DelayedSchedulingStrategy

```
Trigger condition:  computeFireTime(task) != null  &&  currentTime >= fireTime
Fire time logic:    task.scheduledAt != null  ? scheduledAt
                                              : task.createdAt + task.delayMillis
Next schedule time: the computed fire time (fires once)
```

- If `scheduledAt` is explicitly set, uses that.
- Otherwise computes `createdAt + delayMillis`.
- Returns null (won't schedule) if `delayMillis <= 0` and no `scheduledAt`.

### 6.2 Retry Strategies

#### ExponentialBackoffRetryStrategy

```
Constructor:    (initialDelayMs, maxDelayMs, multiplier)
                Validates: initialDelayMs > 0, maxDelayMs >= initialDelayMs, multiplier > 1.0

shouldRetry:    attemptNumber <= task.getMaxRetries()

getRetryDelay:  1. baseDelay = initialDelayMs * multiplier^(attempt - 1)
                2. cappedDelay = min(baseDelay, maxDelayMs)
                3. jitter = cappedDelay * (0.9 + random * 0.2)    // +/- 10%
                4. return (long) jitter
```

Concrete example with defaults (1000ms, 30000ms, 2.0x):

| Attempt | Base Delay | Capped | With Jitter Range |
|---------|-----------|--------|-------------------|
| 1 | 1,000ms | 1,000ms | 900 - 1,100ms |
| 2 | 2,000ms | 2,000ms | 1,800 - 2,200ms |
| 3 | 4,000ms | 4,000ms | 3,600 - 4,400ms |
| 4 | 8,000ms | 8,000ms | 7,200 - 8,800ms |
| 5 | 16,000ms | 16,000ms | 14,400 - 17,600ms |

**Jitter prevents thundering herd:** When many tasks fail simultaneously, the random
offset ensures they don't all retry at the exact same instant.

#### FixedIntervalRetryStrategy

```
Constructor:    (fixedDelayMs)   -- validates > 0
shouldRetry:    attemptNumber <= task.getMaxRetries()
getRetryDelay:  fixedDelayMs     -- constant regardless of attempt
```

Simpler than exponential. Good for transient errors with predictable recovery time
(e.g., brief database connection drops).

### 6.3 Assignment Strategies

#### RoundRobinAssignmentStrategy

```
State:          AtomicInteger counter (thread-safe)

assignTask:     1. Filter availableWorkers to those where isAvailable() == true
                2. If empty -> Optional.empty()
                3. index = |counter.getAndIncrement()| % readyWorkers.size()
                4. return readyWorkers.get(index)
```

- **Thread-safety:** `AtomicInteger` ensures concurrent callers don't collide.
- **Limitation:** Ignores current load -- can overload workers with different capacities.
- **Best for:** Homogeneous clusters with equal worker capacity.

#### LeastLoadedAssignmentStrategy

```
assignTask:     1. Filter availableWorkers to those where isAvailable() == true
                2. Sort by currentLoad ascending, then registeredAt ascending (tie-breaker)
                3. Return the first (min) worker
```

- **Tie-breaker:** Prefers older workers (lower `registeredAt`) as they are likely
  more stable.
- **Best for:** Heterogeneous clusters with different worker capacities.
- Uses `Comparator.comparingInt(Worker::getCurrentLoad).thenComparing(Worker::getRegisteredAt)`.

---

## 7. Engine Design

### 7.1 TaskQueue -- PriorityQueue Internals

```
┌─────────────────────────────────────────────────────────┐
│                     TaskQueue                            │
│                                                         │
│  Backing structure: java.util.PriorityQueue<Task>       │
│                                                         │
│  Comparator (two-level):                                │
│    1. priority.getValue() DESCENDING                    │
│       (CRITICAL=3 > HIGH=2 > MEDIUM=1 > LOW=0)         │
│    2. createdAt ASCENDING (FIFO within same priority)   │
│                                                         │
│  Time complexity:                                       │
│    enqueue(Task)   -> O(log n)                          │
│    dequeue()       -> O(log n)                          │
│    peek()          -> O(1)                              │
│    remove(taskId)  -> O(n) linear scan (acceptable)     │
│    size()          -> O(1)                              │
│    getAllTasks()    -> O(n log n) snapshot + sort        │
│                                                         │
│  Thread safety: NOT thread-safe.                        │
│  Consumed only by single-threaded scheduler loop.       │
└─────────────────────────────────────────────────────────┘
```

**Comparator construction:**

```java
Comparator<Task> TASK_COMPARATOR = Comparator
    .comparingInt((Task t) -> t.getPriority().getValue())
    .reversed()                     // descending priority
    .thenComparing(Task::getCreatedAt);  // ascending timestamp
```

**Dequeue order example:**

```
Submit order:  LOW("cleanup"), CRITICAL("security"), MEDIUM("report"), HIGH("payment")

Dequeue order:
  1. CRITICAL  "security"    (priority=3)
  2. HIGH      "payment"     (priority=2)
  3. MEDIUM    "report"      (priority=1)
  4. LOW       "cleanup"     (priority=0)
```

### 7.2 DependencyResolver -- Adjacency List + Kahn's Algorithm

```
┌─────────────────────────────────────────────────────────┐
│                  DependencyResolver                      │
│                                                         │
│  Data structure:                                        │
│    Map<String, Set<String>> dependencies                │
│      key   = taskId                                     │
│      value = set of taskIds it DEPENDS ON               │
│                                                         │
│  Example:                                               │
│    dependencies = {                                     │
│      "transform" -> {"extract"},                        │
│      "validate"  -> {"extract"},                        │
│      "load"      -> {"transform", "validate"},          │
│      "extract"   -> {}                                  │
│    }                                                    │
│                                                         │
│  DAG visualization:                                     │
│    extract ──→ transform ──→ load                       │
│       │                       ↑                         │
│       └────→ validate ────────┘                         │
└─────────────────────────────────────────────────────────┘
```

#### Cycle Detection -- DFS Three-Coloring

```
Colors:
  WHITE = 0  (unvisited)
  GRAY  = 1  (in current DFS stack -- if we revisit, it's a cycle)
  BLACK = 2  (fully processed)

Algorithm:
  1. Initialize all nodes to WHITE
  2. For each WHITE node, run DFS:
     a. Color node GRAY
     b. For each dependency:
        - If GRAY -> back edge found -> CYCLE
        - If WHITE -> recurse
     c. Color node BLACK
  3. If no back edges found -> no cycle

Time complexity: O(V + E)
```

#### Topological Sort -- Kahn's Algorithm

```
Steps:
  1. Guard: call hasCycle() first -- throw DependencyCycleException if cycle

  2. Build in-degree map:
     For each edge (dep -> task):
       inDegree[task]++

  3. Build reverse adjacency list:
     dependents[dep] -> {task}   (dep must run before task)

  4. Seed queue with all nodes where inDegree == 0
     (these have no dependencies -- they are root tasks)

  5. BFS loop:
     while queue not empty:
       node = queue.poll()
       order.add(node)
       for each dependent of node:
         inDegree[dependent]--
         if inDegree[dependent] == 0:
           queue.add(dependent)

  6. Return order

Time complexity: O(V + E)
```

#### getReadyTasks(Set<String> completedTaskIds)

```
For each taskId in dependencies:
  if taskId is already in completedTaskIds -> skip
  if completedTaskIds.containsAll(dependencies[taskId]) -> taskId is ready

Returns: Set<String> of task IDs whose ALL upstream deps are completed.
```

This is called by `SchedulerEngine.tick()` on every heartbeat to check which
dependency-blocked tasks can now be moved to the priority queue.

### 7.3 CronParser

```
┌─────────────────────────────────────────────────────────┐
│                      CronParser                          │
│                                                         │
│  5-field format: minute hour dayOfMonth month dayOfWeek │
│  Examples:                                              │
│    "0 * * * *"   -> every hour at minute 0              │
│    "30 2 * * 1"  -> 2:30 AM every Monday                │
│    "*/15 * * * *" -> (not supported -- simplified)      │
│                                                         │
│  Supported:                                             │
│    "*"           -> matches any value                   │
│    "5"           -> matches exact value                 │
│    "1,15,30"     -> matches any in comma-separated list │
│                                                         │
│  Validation ranges:                                     │
│    minute: 0-59, hour: 0-23, day: 1-31,                │
│    month: 1-12, dow: 0-7 (0 and 7 both = Sunday)       │
│                                                         │
│  getNextFireTime(schedule, after):                      │
│    Scans minute-by-minute from after+1min up to 24h    │
│    Returns Optional<Instant> of next matching minute    │
│    Returns empty if no match within 24h window          │
└─────────────────────────────────────────────────────────┘
```

---

## 8. Service Layer Design

### 8.1 SchedulerService (Facade)

**Role:** Single entry point for the distributed task scheduler. Orchestrates all
other services into a unified API.

```
Dependencies:
  ├── TaskService               (task lifecycle)
  ├── WorkerService             (worker pool)
  ├── ExecutionService          (task execution)
  ├── SchedulerEngine           (queue + deps + cron)
  ├── TaskAssignmentStrategy    (worker selection)
  └── SchedulingStrategy        (when to schedule)

Methods:
  submitTask(Task)                  -> validates via TaskService, enqueues in engine
  submitTaskWithDependencies(Task, List<String>)
  scheduleAndDispatch()             -> tick engine, filter by strategy, assign, execute
  cancelTask(String)                -> cancels in both TaskService and engine
  getTask(String)                   -> delegates to TaskService
  getTaskStatus(String)             -> delegates to TaskService
  notifyTaskCompletion(String)      -> triggers dependency re-check
```

**scheduleAndDispatch() flow (the hot path):**

```
1. engine.tick()                    -> process cron, resolve deps, get ready tasks
2. If no ready tasks: engine.getNextTasks(10)
3. Filter through schedulingStrategy.shouldScheduleNow()
4. If all filtered out: use original list (already explicitly queued)
5. workerService.getAvailableWorkers()
6. For each schedulable task:
   a. Refresh available workers (capacity may have changed)
   b. assignmentStrategy.assignTask(task, workers)
   c. If assigned: task.updateStatus(ASSIGNED) -> executionService.executeTask()
   d. If no worker: log and skip
```

### 8.2 TaskService

**Role:** Task lifecycle management. Validates business rules before state transitions.

```
Dependencies:
  ├── TaskRepository            (task CRUD storage)
  └── ExecutionRepository       (execution history lookup)

Methods:
  createTask(Task)              -> validates name/type not null, saves to repo
  getTask(String)               -> findById
  updateTaskStatus(String, TaskStatus) -> find, transition, save
  cancelTask(String)            -> blocks if already COMPLETED or FAILED
  getTasksByStatus(TaskStatus)
  getTasksByGroup(String)
  getTaskExecutionHistory(String) -> all execution records for a task
  getAllTasks()
```

### 8.3 WorkerService

**Role:** Worker pool management. Tracks registration, heartbeats, and liveness.

```
Dependencies:
  └── WorkerRepository          (worker CRUD storage)

Methods:
  registerWorker(Worker)
  deregisterWorker(String)      -> sets status to OFFLINE
  getAvailableWorkers()         -> delegates to repo.findAvailable()
  updateHeartbeat(String)       -> refreshes lastHeartbeat
  markWorkerDead(String)        -> sets status to DEAD
  getWorker(String)
  getAllWorkers()
  isWorkerAlive(String, Duration) -> checks heartbeat within timeout
```

### 8.4 ExecutionService

**Role:** Executes tasks on workers and records results. Coordinates retry logic.

```
Dependencies:
  ├── TaskRepository            (task status updates)
  ├── ExecutionRepository       (execution record persistence)
  ├── WorkerRepository          (worker load tracking)
  └── RetryStrategy             (decides whether to retry on failure)

Methods:
  executeTask(Task, Worker)     -> creates execution record, simulates, records result
  handleTaskCompletion(exec)    -> task -> COMPLETED, worker.decrementLoad()
  handleTaskFailure(exec, error) -> check retry strategy -> RETRYING or FAILED
  getExecution(String)
  getExecutionsForTask(String)
  simulateExecution(Task)       -> simulates work (tasks with "fail" in name -> failure)
```

**executeTask() flow:**

```
1. Count previous executions -> attemptNumber
2. Create TaskExecution record, markStarted(), save
3. task.updateStatus(RUNNING), worker.incrementLoad()
4. simulateExecution(task) -> TaskResult
5. If success: execution.markCompleted(result) -> handleTaskCompletion()
6. If failure: execution.markFailed(error) -> handleTaskFailure()
```

**handleTaskFailure() flow:**

```
1. worker.decrementLoad()
2. retryStrategy.shouldRetry(task, attemptNumber, error)?
   a. YES: task.updateStatus(RETRYING)
   b. NO:  task.updateStatus(FAILED)   -- permanently failed
```

### 8.5 LeaderElectionService (Bully Algorithm)

**Role:** Leader election using the Bully algorithm. The node with the highest
priority among alive nodes becomes the leader.

```
Dependencies:
  └── SchedulerNodeRepository   (scheduler node storage)

State:
  - currentLeaderId: String     (cached leader ID)
  - ALIVE_TIMEOUT: 30 seconds   (heartbeat freshness threshold)

Methods:
  registerNode(SchedulerNode)
  electLeader()                 -> Bully algorithm (see below)
  getCurrentLeader()            -> cached leader, validated against liveness
  handleNodeFailure(String)     -> mark dead, re-elect if it was the leader
  isLeader(String)
  simulateHeartbeats()          -> update heartbeats for alive nodes
```

**Bully Algorithm flow:**

```
1. Get all nodes from repository
2. Filter to alive nodes (heartbeat within 30s)
3. For each alive node, find all higher-priority challengers
   (higher priority, or same priority with higher nodeId)
4. Winner = node with no challengers (highest priority + highest nodeId)
5. Update all nodes: only winner.isLeader = true
6. Cache currentLeaderId
```

### 8.6 FailoverService

**Role:** Detects dead workers and reassigns their in-flight tasks.

```
Dependencies:
  ├── WorkerService             (worker liveness checks)
  ├── TaskService               (task status management)
  ├── ExecutionRepository       (find in-flight tasks on dead workers)
  └── TaskAssignmentStrategy    (reassign to healthy workers)

Methods:
  detectDeadWorkers(Duration timeout)   -> scan all workers, mark stale ones DEAD
  reassignTasks(List<Worker> dead)      -> find RUNNING executions, reassign
  performFailover(Duration timeout)     -> detectDeadWorkers + reassignTasks
```

**performFailover() flow:**

```
1. For each worker in workerService.getAllWorkers():
   if (now - lastHeartbeat > timeout) AND status not DEAD/OFFLINE:
     markWorkerDead(workerId)
     add to deadWorkers list

2. For each dead worker:
   a. Find all RUNNING executions on that worker
   b. For each execution:
      - Reset task to QUEUED
      - Get available workers
      - Use assignmentStrategy to pick a new worker
      - If assigned: task -> ASSIGNED
      - If no worker: task stays QUEUED
```

### 8.7 MonitoringService

**Role:** Read-only metrics computation. No writes to any repository.

```
Dependencies:
  ├── TaskRepository            (task status counts)
  ├── WorkerRepository          (worker utilization data)
  └── ExecutionRepository       (execution timing and outcome data)

Methods:
  getTaskCountByStatus()        -> Map<TaskStatus, Long>
  getWorkerUtilization()        -> Map<String, Double> (load/capacity ratio)
  getAverageExecutionTime()     -> Duration (avg of completed executions)
  getFailureRate()              -> double (failed / total executions)
  getRetryRate()                -> double (attempts > 1 / total executions)
  getThroughput()               -> long (count of COMPLETED tasks)
  printDashboard()              -> formatted console output of all metrics
```

---

## 9. Concurrency Considerations

### 9.1 Thread-Safe Structures

| Component | Concurrency Mechanism | Why |
|-----------|----------------------|-----|
| `InMemoryTaskRepository` | `ConcurrentHashMap<String, Task>` | Concurrent reads/writes from scheduler and worker threads |
| `InMemoryWorkerRepository` | `ConcurrentHashMap<String, Worker>` | Worker status updates from heartbeat threads + scheduler reads |
| `InMemoryExecutionRepository` | `ConcurrentHashMap<String, TaskExecution>` | Execution records written by workers, read by monitoring |
| `InMemorySchedulerNodeRepository` | `ConcurrentHashMap<String, SchedulerNode>` | Node heartbeats from multiple scheduler instances |
| `RoundRobinAssignmentStrategy` | `AtomicInteger counter` | Lock-free round-robin index increment across concurrent dispatchers |

### 9.2 Not Thread-Safe (By Design)

| Component | Why Not Thread-Safe | Mitigation |
|-----------|-------------------|------------|
| `TaskQueue` | `PriorityQueue` is not thread-safe | Consumed only by single-threaded scheduler loop |
| `DependencyResolver` | `HashMap<String, Set<String>>` | Modified only during task submission (serial) |
| `SchedulerEngine` | Internal maps (waitingOnDeps, cronTasks) | Single scheduler thread owns the engine |

### 9.3 Concurrency Patterns Used

1. **ConcurrentHashMap for repositories:** Lock-free reads, segment-level locks for
   writes. Ideal for read-heavy workloads (status queries, monitoring).

2. **AtomicInteger for round-robin:** `getAndIncrement()` is a single CAS operation.
   The modular arithmetic (`counter % size`) ensures even distribution without locks.

3. **Immutable value objects:** `TaskResult` and `payload` in `Task` are immutable
   (unmodifiable maps). Safe to share across threads without synchronization.

4. **Volatile-like freshness:** Worker heartbeats (`lastHeartbeat`) are updated by one
   thread and read by another. In a production system, this field would be `volatile`
   or protected by a read-write lock. In the demo, single-thread-at-a-time execution
   provides implicit visibility.

---

## 10. SOLID Principles Applied

### S -- Single Responsibility Principle

Each service owns exactly one concern:

| Service | Single Responsibility |
|---------|----------------------|
| `TaskService` | Task lifecycle (create, status transitions, cancel) |
| `WorkerService` | Worker pool management (register, heartbeat, liveness) |
| `ExecutionService` | Task execution and retry logic |
| `LeaderElectionService` | Leader election via Bully algorithm |
| `FailoverService` | Dead worker detection and task reassignment |
| `MonitoringService` | Metrics computation (read-only) |
| `SchedulerService` | Orchestration (Facade -- delegates, never does the work itself) |

### O -- Open/Closed Principle

The system is open for extension through strategy interfaces:

```
Need a new scheduling mode?
  -> Implement SchedulingStrategy, plug into AppConfig.setSchedulingStrategy()

Need a new retry policy?
  -> Implement RetryStrategy, plug into AppConfig.setRetryStrategy()

Need a new assignment algorithm?
  -> Implement TaskAssignmentStrategy, plug into AppConfig.setAssignmentStrategy()

Need persistent storage?
  -> Implement TaskRepository with JDBC, plug into AppConfig
```

No existing code changes required. The strategy interfaces are closed for modification.

### L -- Liskov Substitution Principle

All strategy implementations are fully substitutable:

```
TaskAssignmentStrategy strategy = new RoundRobinAssignmentStrategy();
// Later, swap without changing any caller:
strategy = new LeastLoadedAssignmentStrategy();

// Both satisfy the same contract:
Optional<Worker> worker = strategy.assignTask(task, workers);
```

Repository implementations are substitutable:

```
TaskRepository repo = new InMemoryTaskRepository();     // dev/test
// Swap for production:
TaskRepository repo = new JdbcTaskRepository(dataSource); // production
```

### I -- Interface Segregation Principle

Strategy interfaces are narrow and focused:

```
SchedulingStrategy:     shouldScheduleNow() + getNextScheduleTime()   (2 methods)
RetryStrategy:          shouldRetry() + getRetryDelayMillis()         (2 methods)
TaskAssignmentStrategy: assignTask()                                  (1 method)
```

Repository interfaces expose only the queries their consumers need:

```
WorkerRepository.findAvailable()        -- used by dispatcher
ExecutionRepository.findLatestByTaskId() -- used by retry logic
SchedulerNodeRepository.findAlive()     -- used by leader election
```

### D -- Dependency Inversion Principle

High-level services depend on abstractions (interfaces), not concrete implementations:

```
ExecutionService depends on:
  TaskRepository (interface)       -- not InMemoryTaskRepository
  ExecutionRepository (interface)  -- not InMemoryExecutionRepository
  WorkerRepository (interface)     -- not InMemoryWorkerRepository
  RetryStrategy (interface)        -- not ExponentialBackoffRetryStrategy
```

`AppConfig` is the composition root that wires concrete implementations to interfaces.
Services never instantiate their own dependencies.

---

## 11. Sample Workflows

### 11.1 Task Submission -> Scheduling -> Execution -> Completion

```
1. Client calls SchedulerController.submitTask(task)
2. SchedulerController delegates to SchedulerService.submitTask(task)
3. SchedulerService calls TaskService.createTask(task)
   3a. TaskService validates name and taskType are not null
   3b. TaskService saves task to TaskRepository (status = PENDING)
4. SchedulerService calls SchedulerEngine.submitTask(task)
   4a. If CRON task: register CronSchedule in cronTasks map
   4b. task.updateStatus(QUEUED)
   4c. taskQueue.enqueue(task)         -- O(log n) heap insertion
5. Client (or timer) calls SchedulerController.runSchedulingCycle()
6. SchedulerController delegates to SchedulerService.scheduleAndDispatch()
7. SchedulerService calls engine.tick()
   7a. Check CRON tasks: if next fire time has passed, signal readiness
   7b. Check dependency-waiting tasks: move ready ones to queue
   7c. Drain queue into dispatch batch
8. SchedulerService filters through schedulingStrategy.shouldScheduleNow()
9. SchedulerService calls workerService.getAvailableWorkers()
10. For each schedulable task:
    10a. assignmentStrategy.assignTask(task, availableWorkers) -> Optional<Worker>
    10b. If assigned: task.updateStatus(ASSIGNED)
    10c. executionService.executeTask(task, worker)
11. ExecutionService.executeTask():
    11a. Count previous attempts from execRepo.findByTaskId()
    11b. Create TaskExecution(taskId, workerId, attemptNumber)
    11c. execution.markStarted() -- status -> RUNNING
    11d. task.updateStatus(RUNNING), worker.incrementLoad()
    11e. simulateExecution(task) -> TaskResult
    11f. If success: execution.markCompleted(result)
         -> handleTaskCompletion: task -> COMPLETED, worker.decrementLoad()
    11g. If failure: execution.markFailed(error)
         -> handleTaskFailure: check retryStrategy
```

### 11.2 Task Failure -> Retry -> Recovery

```
1. ExecutionService.simulateExecution(task) returns TaskResult.failure(error)
2. execution.markFailed(error) -- status -> FAILED, endTime = now
3. handleTaskFailure(execution, error):
   3a. worker.decrementLoad()
   3b. retryStrategy.shouldRetry(task, attemptNumber, error)?
       - ExponentialBackoff: attemptNumber <= task.getMaxRetries()
       - If YES:
         task.updateStatus(RETRYING)
         Retry delay = initialDelay * multiplier^(attempt-1) +/- 10% jitter
         (In production: re-enqueue with delay in the priority queue)
       - If NO (maxRetries exhausted):
         task.updateStatus(FAILED) -- permanently failed
4. On next scheduling cycle, RETRYING tasks are re-dispatched:
   4a. New TaskExecution created with attemptNumber = previous + 1
   4b. Same flow as 11.1 from step 11
```

### 11.3 DAG Dependency Resolution

```
1. Client submits tasks with dependencies:
   scheduler.submitTask(extract)                          -- root (no deps)
   scheduler.submitTaskWithDependencies(transform, [extract.id])
   scheduler.submitTaskWithDependencies(validate, [extract.id])
   scheduler.submitTaskWithDependencies(load, [transform.id, validate.id])

2. SchedulerEngine.submitTaskWithDependencies():
   2a. Register edges in DependencyResolver:
       transform -> {extract}
       validate  -> {extract}
       load      -> {transform, validate}
       extract   -> {}
   2b. Park task in waitingOnDeps map

3. On tick():
   3a. extract has no deps -> goes directly to TaskQueue -> dispatched
   3b. extract completes -> completedIds = {extract}
   3c. getReadyTasks({extract}):
       - transform: deps={extract}, all in completed -> READY
       - validate: deps={extract}, all in completed -> READY
       - load: deps={transform,validate}, NOT all completed -> WAIT
   3d. transform and validate are moved from waitingOnDeps to TaskQueue
   3e. Both dispatched and executed

4. After transform and validate complete -> completedIds = {extract, transform, validate}
   4a. getReadyTasks({extract, transform, validate}):
       - load: deps={transform,validate}, all in completed -> READY
   4b. load moved to queue and dispatched
```

### 11.4 Leader Election and Failover

```
1. Three scheduler nodes register with different priorities:
   node-1 (priority=10), node-2 (priority=20), node-3 (priority=30)

2. electLeader():
   2a. Filter alive nodes (heartbeat within 30s) -> all 3
   2b. Bully: node-1 challenged by node-2 and node-3
   2c. Bully: node-2 challenged by node-3
   2d. Bully: node-3 has no challengers -> WINS
   2e. node-3.isLeader = true, all others = false

3. node-3 fails (heartbeat goes stale):
   3a. handleNodeFailure("node-3"):
       - node-3.isLeader = false
       - currentLeaderId was "node-3" -> trigger re-election
   3b. electLeader():
       - Alive nodes: node-1 and node-2
       - node-2 (priority=20) wins
       - node-2.isLeader = true

4. Worker failover (separate from leader election):
   4a. FailoverService.detectDeadWorkers(30s):
       - worker-2 heartbeat is 2 minutes old -> mark DEAD
   4b. reassignTasks([worker-2]):
       - Find RUNNING executions on worker-2
       - Reset each task to QUEUED
       - assignmentStrategy picks a healthy worker
       - Task -> ASSIGNED on new worker
```

---

## 12. Design Patterns Used

| # | Pattern | GoF Category | Classes | Purpose |
|---|---------|-------------|---------|---------|
| 1 | **Strategy** (Scheduling) | Behavioral | `SchedulingStrategy`, `ImmediateSchedulingStrategy`, `CronSchedulingStrategy`, `DelayedSchedulingStrategy` | Swap scheduling algorithms at runtime |
| 2 | **Strategy** (Retry) | Behavioral | `RetryStrategy`, `ExponentialBackoffRetryStrategy`, `FixedIntervalRetryStrategy` | Swap retry policies without changing ExecutionService |
| 3 | **Strategy** (Assignment) | Behavioral | `TaskAssignmentStrategy`, `RoundRobinAssignmentStrategy`, `LeastLoadedAssignmentStrategy` | Swap worker selection algorithms |
| 4 | **Builder** | Creational | `Task.Builder` | Fluent construction of immutable Task objects with 14 optional fields |
| 5 | **Factory** | Creational | `AppConfig` | Composition root -- lazy creation of all 20+ objects with correct wiring |
| 6 | **Repository** | Structural (DDD) | `TaskRepository`, `WorkerRepository`, `ExecutionRepository`, `SchedulerNodeRepository` + 4 InMemory impls | Decouple storage from business logic |
| 7 | **Facade** | Structural | `SchedulerService` | Unifies 6 dependencies behind one submitTask/dispatch API |
| 8 | **State** | Behavioral | `TaskStatus` enum (9 states), `Task.updateStatus()`, `TaskExecution.markStarted/markCompleted/markFailed` | Explicit lifecycle transitions |
| 9 | **Observer** | Behavioral | `SchedulerService.notifyTaskCompletion()` -> triggers `DependencyResolver.getReadyTasks()` | Completion event cascades to unblock dependent tasks |
| 10 | **Command** | Behavioral | `Task` as executable unit, `ExecutionService.executeTask()` as invoker | Task encapsulates all execution data; executor is decoupled from task definition |

### Pattern Interaction Map

```
                    ┌──────────────┐
  Builder ─────────>│     Task     │<────── Command (executable unit)
                    │              │
                    │  TaskStatus  │<────── State (lifecycle transitions)
                    └──────┬───────┘
                           │
                           v
  Factory ─────────>┌──────────────┐
  (AppConfig)       │  Scheduler   │<────── Facade (unified API)
                    │   Service    │
                    └──┬──┬──┬─────┘
                       │  │  │
         ┌─────────────┘  │  └─────────────┐
         v                v                v
  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
  │ Scheduling   │ │ Assignment   │ │ Retry        │
  │ Strategy     │ │ Strategy     │ │ Strategy     │   <── Strategy x3
  └──────────────┘ └──────────────┘ └──────────────┘
         │                │                │
         v                v                v
  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
  │ TaskRepo     │ │ WorkerRepo   │ │ ExecRepo     │   <── Repository x4
  └──────────────┘ └──────────────┘ └──────────────┘

  Observer: task completion -> DependencyResolver.getReadyTasks()
            -> unblocks downstream tasks
```

---

## 13. Extensibility Points

### 13.1 New Scheduling Strategies

To add a new scheduling mode (e.g., rate-limited scheduling):

```java
// 1. Create a new class implementing SchedulingStrategy
public class RateLimitedSchedulingStrategy implements SchedulingStrategy {
    private final int maxPerMinute;
    private final AtomicInteger currentMinuteCount = new AtomicInteger(0);

    @Override
    public boolean shouldScheduleNow(Task task, Instant currentTime) {
        return currentMinuteCount.get() < maxPerMinute;
    }

    @Override
    public Optional<Instant> getNextScheduleTime(Task task, Instant currentTime) {
        return Optional.of(currentTime.plusSeconds(60));
    }

    @Override
    public String getStrategyName() { return "RATE_LIMITED"; }
}

// 2. Plug it in via AppConfig
config.setSchedulingStrategy(new RateLimitedSchedulingStrategy(100));
```

No changes required to SchedulerService, SchedulerEngine, or any other class.

### 13.2 New Retry Strategies

To add a new retry strategy (e.g., circuit breaker):

```java
public class CircuitBreakerRetryStrategy implements RetryStrategy {
    private int consecutiveFailures = 0;
    private static final int TRIP_THRESHOLD = 5;

    @Override
    public boolean shouldRetry(Task task, int attemptNumber, String error) {
        if (consecutiveFailures >= TRIP_THRESHOLD) return false; // circuit open
        return attemptNumber <= task.getMaxRetries();
    }
    // ...
}

config.setRetryStrategy(new CircuitBreakerRetryStrategy());
```

### 13.3 New Assignment Strategies

To add a new assignment strategy (e.g., tag-based affinity):

```java
public class AffinityAssignmentStrategy implements TaskAssignmentStrategy {
    @Override
    public Optional<Worker> assignTask(Task task, List<Worker> availableWorkers) {
        String requiredTag = task.getPayload().get("required_tag");
        return availableWorkers.stream()
            .filter(Worker::isAvailable)
            .filter(w -> requiredTag == null || w.hasTag(requiredTag))
            .min(Comparator.comparingInt(Worker::getCurrentLoad));
    }

    @Override
    public String getStrategyName() { return "AFFINITY"; }
}
```

### 13.4 New Repository Backends

To add persistent storage (e.g., JDBC):

```java
public class JdbcTaskRepository implements TaskRepository {
    private final DataSource dataSource;

    @Override
    public void save(Task task) {
        // INSERT or UPDATE via PreparedStatement
    }

    @Override
    public Optional<Task> findById(String id) {
        // SELECT * FROM tasks WHERE id = ?
    }
    // ... implement all interface methods
}

// Wire in AppConfig or a custom factory:
AppConfig config = new AppConfig();
// Override the repository getter or use a setter
```

### 13.5 New Exception Types

The exception hierarchy is designed for extension:

```
SchedulerException (base, RuntimeException)
├── DependencyCycleException      (carries involvedTaskIds)
├── TaskExecutionException        (carries taskId)
├── LeaderElectionException
├── WorkerUnavailableException
└── [Your new exception here]     (extend SchedulerException)
```

### 13.6 Adding New Services

New services follow the same pattern:

```
1. Create the service class with constructor injection
2. Add a getter method to AppConfig (lazy init)
3. Wire dependencies in the getter
4. Optionally expose through SchedulerService (Facade)
```

Example: Adding a `NotificationService`:

```java
public class NotificationService {
    private final TaskRepository taskRepo;
    private final ExecutionRepository execRepo;

    public NotificationService(TaskRepository taskRepo, ExecutionRepository execRepo) {
        this.taskRepo = taskRepo;
        this.execRepo = execRepo;
    }

    public void notifyOnFailure(String taskId) {
        // Send alert when a task permanently fails
    }
}

// In AppConfig:
public NotificationService getNotificationService() {
    if (notificationService == null) {
        notificationService = new NotificationService(
            getTaskRepository(), getExecutionRepository()
        );
    }
    return notificationService;
}
```

### 13.7 Extension Summary Table

| Extension | Interface/Class to Implement | Where to Wire | Existing Code Changes |
|-----------|------------------------------|--------------|----------------------|
| New scheduling mode | `SchedulingStrategy` | `AppConfig.setSchedulingStrategy()` | None |
| New retry policy | `RetryStrategy` | `AppConfig.setRetryStrategy()` | None |
| New worker selection | `TaskAssignmentStrategy` | `AppConfig.setAssignmentStrategy()` | None |
| New storage backend | `TaskRepository` / `WorkerRepository` / etc. | `AppConfig` getters | None |
| New exception type | Extend `SchedulerException` | Throw from new code | None |
| New service | New class + `AppConfig` getter | `AppConfig` | Add getter only |
| New task type | Add to `TaskType` enum | `SchedulingStrategy` impl handles it | Add enum value |
| New worker status | Add to `WorkerStatus` enum | `WorkerService` handles transitions | Add enum value |
| New priority level | Add to `TaskPriority` enum | Auto-handled by `TaskQueue` comparator | Add enum value |

---

## Appendix A: Complete Dependency Graph (AppConfig Wiring)

```
AppConfig (Composition Root)
│
├── Repositories (all InMemory*, backed by ConcurrentHashMap)
│   ├── TaskRepository            -> InMemoryTaskRepository
│   ├── WorkerRepository          -> InMemoryWorkerRepository
│   ├── ExecutionRepository       -> InMemoryExecutionRepository
│   └── SchedulerNodeRepository   -> InMemorySchedulerNodeRepository
│
├── Engine
│   ├── CronParser                (standalone)
│   ├── TaskQueue                 (standalone)
│   ├── DependencyResolver        (standalone)
│   └── SchedulerEngine           -> TaskQueue + DependencyResolver + CronParser
│
├── Strategies (swappable via setters)
│   ├── SchedulingStrategy        -> ImmediateSchedulingStrategy (default)
│   ├── RetryStrategy             -> ExponentialBackoffRetryStrategy(1000, 30000, 2.0)
│   └── TaskAssignmentStrategy    -> RoundRobinAssignmentStrategy (default)
│
├── Services
│   ├── TaskService               -> TaskRepository + ExecutionRepository
│   ├── WorkerService             -> WorkerRepository
│   ├── ExecutionService          -> TaskRepository + ExecutionRepository
│   │                                + WorkerRepository + RetryStrategy
│   ├── LeaderElectionService     -> SchedulerNodeRepository
│   ├── FailoverService           -> WorkerService + TaskService
│   │                                + ExecutionRepository + TaskAssignmentStrategy
│   ├── MonitoringService         -> TaskRepository + WorkerRepository
│   │                                + ExecutionRepository
│   └── SchedulerService          -> TaskService + WorkerService + ExecutionService
│                                    + SchedulerEngine + TaskAssignmentStrategy
│                                    + SchedulingStrategy
│
├── Controller
│   └── SchedulerController       -> SchedulerService + MonitoringService
│
└── Display
    └── SchedulerStatsDisplay     -> MonitoringService + TaskRepository
                                     + WorkerRepository
```

---

## Appendix B: Task Status State Machine

```
                    ┌────────────┐
                    │  PENDING   │  (initial state -- just created)
                    └─────┬──────┘
                          │ submitTask()
                          v
                    ┌────────────┐
                    │   QUEUED   │  (in priority queue, waiting for dispatch)
                    └─────┬──────┘
                          │ assignTask()
                          v
                    ┌────────────┐
            ┌──────>│  ASSIGNED  │  (worker selected, about to execute)
            │       └─────┬──────┘
            │             │ markStarted()
            │             v
            │       ┌────────────┐
            │       │  RUNNING   │  (executing on a worker)
            │       └──┬──────┬──┘
            │          │      │
            │   success│      │failure
            │          v      v
            │  ┌──────────┐ ┌────────────┐
            │  │COMPLETED │ │  FAILED    │  (permanent -- retries exhausted)
            │  └──────────┘ └────────────┘
            │                     │
            │    retryStrategy    │ shouldRetry() == true
            │    says YES         v
            │              ┌────────────┐
            └──────────────│  RETRYING  │  (waiting for retry delay)
                           └────────────┘

  Additional terminal states:
    CANCELLED  -- user explicitly cancelled
    TIMED_OUT  -- execution exceeded timeoutMillis

  Helper methods on TaskStatus:
    isTerminal() = COMPLETED | FAILED | CANCELLED | TIMED_OUT
    isActive()   = RUNNING | ASSIGNED | RETRYING
```

---

## Appendix C: Key Algorithms Complexity

| Algorithm | Where | Time | Space | Notes |
|-----------|-------|------|-------|-------|
| Priority queue insert | `TaskQueue.enqueue()` | O(log n) | O(n) | Java `PriorityQueue` backed by binary heap |
| Priority queue poll | `TaskQueue.dequeue()` | O(log n) | -- | Heap extraction |
| Topological sort (Kahn's) | `DependencyResolver.getTopologicalOrder()` | O(V + E) | O(V + E) | BFS peeling zero-in-degree nodes |
| Cycle detection (DFS) | `DependencyResolver.hasCycle()` | O(V + E) | O(V) | Three-coloring: WHITE/GRAY/BLACK |
| Ready tasks check | `DependencyResolver.getReadyTasks()` | O(V * D) | O(V) | V = tasks, D = avg deps per task |
| Cron next fire time | `CronParser.getNextFireTime()` | O(1440) | O(1) | Scans up to 24h (1440 minutes) |
| Round-robin assignment | `RoundRobinAssignmentStrategy` | O(W) | O(1) | W = workers, filter + index |
| Least-loaded assignment | `LeastLoadedAssignmentStrategy` | O(W log W) | O(W) | Stream filter + min |
| Leader election (Bully) | `LeaderElectionService.electLeader()` | O(N^2) | O(N) | Each node challenges all higher-priority nodes |
| Dead worker detection | `FailoverService.detectDeadWorkers()` | O(W) | O(W) | Linear scan of all workers |
| ConcurrentHashMap get | Repository `findById()` | O(1) amortized | -- | Hash lookup |

---
