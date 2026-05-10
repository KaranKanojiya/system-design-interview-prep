# Design Patterns: Distributed Task Scheduler

> Quick interview reference. 10 GoF patterns with anti-pattern contrasts,
> numbered call chains, ASCII class diagrams, and interview soundbites.

---

## Table of Contents

1. [Strategy -- Scheduling](#1-strategy-pattern-scheduling)
2. [Strategy -- Retry](#2-strategy-pattern-retry)
3. [Strategy -- Assignment](#3-strategy-pattern-assignment)
4. [Builder -- Task](#4-builder-pattern-task)
5. [Factory -- AppConfig](#5-factory-pattern-appconfig)
6. [Repository -- Data Access](#6-repository-pattern-data-access)
7. [Facade -- SchedulerService](#7-facade-pattern-schedulerservice)
8. [State -- Task Lifecycle](#8-state-pattern-task-lifecycle)
9. [Observer -- Dependency Resolution](#9-observer-pattern-dependency-resolution)
10. [Command -- Task as Executable Unit](#10-command-pattern-task-as-executable-unit)

---

## 1. Strategy Pattern (Scheduling)

**GoF Category:** Behavioral

**Pattern:** Define a family of algorithms, encapsulate each one, and make them
interchangeable. Strategy lets the algorithm vary independently from clients that use it.

### Anti-Pattern (Before)

```java
// ANTI-PATTERN: if/else chain in the scheduler -- violates OCP
public class SchedulerEngine {
    public boolean shouldScheduleNow(Task task) {
        if (task.getTaskType() == TaskType.ONE_TIME) {
            return task.getStatus() == TaskStatus.PENDING;
        } else if (task.getTaskType() == TaskType.CRON) {
            CronSchedule schedule = new CronSchedule(task.getCronExpression());
            return schedule.matches(Instant.now());
        } else if (task.getTaskType() == TaskType.DELAYED) {
            Instant fireTime = task.getCreatedAt().plusMillis(task.getDelayMillis());
            return !Instant.now().isBefore(fireTime);
        }
        // Every new scheduling mode requires modifying this method
        return false;
    }
}
```

**Problems:** Adding a new scheduling mode (e.g., rate-limited) forces modification of
the `shouldScheduleNow()` method. Violates Open/Closed Principle. Untestable in
isolation.

### Clean Solution (After)

```java
// CLEAN: Strategy interface -- each algorithm is its own class
public interface SchedulingStrategy {
    boolean shouldScheduleNow(Task task, Instant currentTime);
    Optional<Instant> getNextScheduleTime(Task task, Instant currentTime);
    String getStrategyName();
}

// Concrete strategies -- each in its own file, testable in isolation
public class ImmediateSchedulingStrategy implements SchedulingStrategy { ... }
public class CronSchedulingStrategy implements SchedulingStrategy { ... }
public class DelayedSchedulingStrategy implements SchedulingStrategy { ... }

// Client (SchedulerService) depends on the interface, not implementations
public class SchedulerService {
    private final SchedulingStrategy schedulingStrategy;

    public void scheduleAndDispatch() {
        List<Task> schedulableTasks = readyTasks.stream()
            .filter(t -> schedulingStrategy.shouldScheduleNow(t, now))
            .toList();
    }
}
```

### Numbered Call Chain

```
1. SchedulerService.scheduleAndDispatch() is called
2. engine.tick() returns ready tasks from the priority queue
3. For each ready task:
   3a. schedulingStrategy.shouldScheduleNow(task, Instant.now())
   3b. ImmediateSchedulingStrategy: checks taskType==ONE_TIME && status in {PENDING,QUEUED}
       OR CronSchedulingStrategy: parses cron expression, checks if current time matches
       OR DelayedSchedulingStrategy: computes fireTime, checks if currentTime >= fireTime
4. Only tasks that pass the strategy filter proceed to worker assignment
5. Strategy is injected via AppConfig.setSchedulingStrategy() -- swappable at runtime
```

### ASCII Class Diagram

```
          <<interface>>
       SchedulingStrategy
    ┌────────────────────────┐
    │ shouldScheduleNow()    │
    │ getNextScheduleTime()  │
    │ getStrategyName()      │
    └───────────┬────────────┘
                │
     ┌──────────┼──────────┐
     │          │          │
     v          v          v
┌──────────┐ ┌─────────┐ ┌──────────┐
│Immediate │ │  Cron   │ │ Delayed  │
│Scheduling│ │Scheduling│ │Scheduling│
│Strategy  │ │Strategy │ │Strategy  │
├──────────┤ ├─────────┤ ├──────────┤
│ONE_TIME  │ │CronParser│ │fireTime  │
│+ PENDING │ │ parse() │ │= created │
│= fire    │ │ match() │ │+ delay   │
└──────────┘ └─────────┘ └──────────┘

     SchedulerService (client)
    ┌──────────────────────────┐
    │ - schedulingStrategy     │────> depends on interface only
    │ scheduleAndDispatch()    │
    └──────────────────────────┘
```

### Interview Soundbite

> "We use the Strategy pattern for scheduling so that adding a new scheduling mode --
> like rate-limited or priority-boosted -- is a one-file change with zero modifications
> to the SchedulerService. The strategy is injected via the composition root and can
> be swapped at runtime."

---

## 2. Strategy Pattern (Retry)

**GoF Category:** Behavioral

### Anti-Pattern (Before)

```java
// ANTI-PATTERN: retry logic hardcoded inside ExecutionService
public class ExecutionService {
    public void handleTaskFailure(TaskExecution exec, String error) {
        Task task = taskRepo.findById(exec.getTaskId()).get();
        if (exec.getAttemptNumber() <= task.getMaxRetries()) {
            // Hardcoded exponential backoff -- what if we want fixed interval?
            long delay = (long)(1000 * Math.pow(2.0, exec.getAttemptNumber() - 1));
            delay = Math.min(delay, 30000);
            task.updateStatus(TaskStatus.RETRYING);
        } else {
            task.updateStatus(TaskStatus.FAILED);
        }
    }
}
```

**Problems:** Changing retry behavior requires modifying ExecutionService. Cannot A/B
test different retry policies. The delay formula is buried in business logic.

### Clean Solution (After)

```java
public interface RetryStrategy {
    boolean shouldRetry(Task task, int attemptNumber, String errorMessage);
    long getRetryDelayMillis(int attemptNumber);
    String getStrategyName();
}

public class ExponentialBackoffRetryStrategy implements RetryStrategy {
    private final long initialDelayMs, maxDelayMs;
    private final double multiplier;

    @Override
    public long getRetryDelayMillis(int attemptNumber) {
        double baseDelay = initialDelayMs * Math.pow(multiplier, attemptNumber - 1);
        long cappedDelay = (long) Math.min(baseDelay, maxDelayMs);
        double jitterFactor = 0.9 + ThreadLocalRandom.current().nextDouble() * 0.2;
        return (long) (cappedDelay * jitterFactor);
    }
}

public class FixedIntervalRetryStrategy implements RetryStrategy {
    private final long fixedDelayMs;

    @Override
    public long getRetryDelayMillis(int attemptNumber) {
        return fixedDelayMs;  // constant regardless of attempt
    }
}
```

### Numbered Call Chain

```
1. ExecutionService.executeTask(task, worker) calls simulateExecution(task)
2. simulateExecution() returns TaskResult.failure(error)
3. execution.markFailed(error) -- records failure in execution log
4. handleTaskFailure(execution, error):
   4a. worker.decrementLoad() -- free up capacity
   4b. retryStrategy.shouldRetry(task, attemptNumber, error)
       - ExponentialBackoff: checks attemptNumber <= task.getMaxRetries()
       - FixedInterval: same check, different delay
   4c. If YES: task.updateStatus(RETRYING)
       Delay = retryStrategy.getRetryDelayMillis(attemptNumber)
       - ExponentialBackoff: 1000 * 2^(attempt-1) +/- 10% jitter, capped at 30s
       - FixedInterval: constant delay (e.g., 5000ms)
   4d. If NO: task.updateStatus(FAILED) -- permanently failed
5. On next dispatch cycle, RETRYING tasks get a new attempt number
```

### ASCII Class Diagram

```
          <<interface>>
         RetryStrategy
    ┌────────────────────────┐
    │ shouldRetry()          │
    │ getRetryDelayMillis()  │
    │ getStrategyName()      │
    └───────────┬────────────┘
                │
         ┌──────┴──────┐
         │             │
         v             v
┌─────────────────┐ ┌───────────────────┐
│ Exponential     │ │ FixedInterval     │
│ Backoff         │ │ RetryStrategy     │
│ RetryStrategy   │ │                   │
├─────────────────┤ ├───────────────────┤
│ initialDelayMs  │ │ fixedDelayMs      │
│ maxDelayMs      │ │                   │
│ multiplier      │ │ delay = constant  │
│                 │ │                   │
│ delay = init *  │ └───────────────────┘
│   mult^(n-1)    │
│ + jitter(+/-10%)│
└─────────────────┘

     ExecutionService (client)
    ┌──────────────────────────┐
    │ - retryStrategy          │────> depends on interface only
    │ handleTaskFailure()      │
    └──────────────────────────┘
```

### Interview Soundbite

> "The retry strategy is decoupled from execution logic. Exponential backoff with
> plus-or-minus 10% jitter prevents thundering herd when many tasks fail at once.
> Swapping to fixed-interval for transient errors is a one-line config change."

---

## 3. Strategy Pattern (Assignment)

**GoF Category:** Behavioral

### Anti-Pattern (Before)

```java
// ANTI-PATTERN: assignment logic hardcoded with a boolean flag
public class SchedulerService {
    private boolean useRoundRobin = true;
    private int rrCounter = 0;

    public Worker assignWorker(Task task, List<Worker> workers) {
        if (useRoundRobin) {
            return workers.get(rrCounter++ % workers.size());
        } else {
            return workers.stream()
                .min(Comparator.comparingInt(Worker::getCurrentLoad))
                .orElseThrow();
        }
        // Adding a third mode means another branch here
    }
}
```

### Clean Solution (After)

```java
public interface TaskAssignmentStrategy {
    Optional<Worker> assignTask(Task task, List<Worker> availableWorkers);
    String getStrategyName();
}

public class RoundRobinAssignmentStrategy implements TaskAssignmentStrategy {
    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public Optional<Worker> assignTask(Task task, List<Worker> availableWorkers) {
        List<Worker> ready = availableWorkers.stream()
            .filter(Worker::isAvailable).toList();
        if (ready.isEmpty()) return Optional.empty();
        int index = Math.abs(counter.getAndIncrement() % ready.size());
        return Optional.of(ready.get(index));
    }
}

public class LeastLoadedAssignmentStrategy implements TaskAssignmentStrategy {
    @Override
    public Optional<Worker> assignTask(Task task, List<Worker> availableWorkers) {
        return availableWorkers.stream()
            .filter(Worker::isAvailable)
            .min(Comparator.comparingInt(Worker::getCurrentLoad)
                .thenComparing(Worker::getRegisteredAt));
    }
}
```

### Numbered Call Chain

```
1. SchedulerService.scheduleAndDispatch() filters tasks through scheduling strategy
2. For each schedulable task:
   2a. workerService.getAvailableWorkers() -> List<Worker>
   2b. assignmentStrategy.assignTask(task, availableWorkers)
       - RoundRobin: filter available, counter++ % size, return workers[index]
       - LeastLoaded: filter available, min by currentLoad, tiebreak by registeredAt
   2c. If Optional.empty() -> no worker available, task stays queued
   2d. If worker found -> task.updateStatus(ASSIGNED) -> executeTask(task, worker)
3. Strategy swapped at runtime via AppConfig.setAssignmentStrategy()
```

### ASCII Class Diagram

```
            <<interface>>
       TaskAssignmentStrategy
    ┌────────────────────────────┐
    │ assignTask(Task, Workers)  │
    │ getStrategyName()          │
    └───────────┬────────────────┘
                │
         ┌──────┴──────┐
         │             │
         v             v
┌─────────────────┐ ┌───────────────────┐
│ RoundRobin      │ │ LeastLoaded       │
│ Assignment      │ │ Assignment        │
│ Strategy        │ │ Strategy          │
├─────────────────┤ ├───────────────────┤
│ AtomicInteger   │ │ Comparator:       │
│ counter         │ │  currentLoad ASC  │
│                 │ │  registeredAt ASC │
│ index = |cnt++| │ │  (tiebreaker)     │
│  % workers.size │ │                   │
└─────────────────┘ └───────────────────┘
         ^                   ^
         │                   │
    SchedulerService ────────┘
    FailoverService  ────────── (also uses for reassignment)
```

### Interview Soundbite

> "Round-robin uses an AtomicInteger for lock-free cycling -- great for homogeneous
> clusters. Least-loaded picks the worker with the lowest currentLoad, with registeredAt
> as a tiebreaker favoring stable nodes. Both are O(W) and swappable via one setter."

---

## 4. Builder Pattern (Task)

**GoF Category:** Creational

### Anti-Pattern (Before)

```java
// ANTI-PATTERN: telescoping constructor with 14 parameters
Task task = new Task(
    UUID.randomUUID().toString(),  // id
    "Generate report",             // name
    "Aggregate metrics",           // description
    TaskType.ONE_TIME,             // type
    TaskPriority.HIGH,             // priority
    TaskStatus.PENDING,            // status
    Map.of("format", "pdf"),       // payload
    null,                          // cronExpression
    0,                             // delayMillis
    3,                             // maxRetries
    60000,                         // timeoutMillis
    Instant.now(),                 // createdAt
    Instant.now(),                 // updatedAt
    null,                          // scheduledAt
    null                           // groupId
);
// Unreadable. Easy to swap parameter positions. No defaults.
```

### Clean Solution (After)

```java
// CLEAN: Builder with fluent API and sensible defaults
Task task = Task.builder("Generate report")
    .description("Aggregate metrics")
    .taskType(TaskType.ONE_TIME)
    .priority(TaskPriority.HIGH)
    .addPayload("format", "pdf")
    .maxRetries(3)
    .build();

// Only 'name' is required. All other fields have defaults:
//   id = UUID, taskType = ONE_TIME, priority = MEDIUM, status = PENDING,
//   maxRetries = 3, timeoutMillis = 60000, createdAt = now()
```

### Numbered Call Chain

```
1. Task.builder("Generate report") creates a new Builder instance
   1a. Builder constructor sets name (required) and all defaults
2. .description("...") returns this (Builder), enabling chaining
3. .taskType(TaskType.ONE_TIME) returns this
4. .priority(TaskPriority.HIGH) returns this
5. .addPayload("format", "pdf") adds to internal HashMap, returns this
6. .maxRetries(3) returns this
7. .build() calls private Task(Builder) constructor:
   7a. Copies all fields from Builder to Task
   7b. Wraps payload in Collections.unmodifiableMap(new HashMap<>(payload))
   7c. Returns immutable Task instance
8. After build(), the Task's payload is unmodifiable (defensive copy)
```

### ASCII Class Diagram

```
┌──────────────────────────────────────────────────────┐
│                        Task                           │
├──────────────────────────────────────────────────────┤
│ - id: String (final)                                 │
│ - name: String (final)                               │
│ - description: String (final)                        │
│ - taskType: TaskType (final)                         │
│ - priority: TaskPriority (final)                     │
│ - status: TaskStatus (mutable -- state transitions)  │
│ - payload: Map<String,String> (final, unmodifiable)  │
│ - cronExpression: String (final)                     │
│ - delayMillis: long (final)                          │
│ - maxRetries: int (final)                            │
│ - timeoutMillis: long (final)                        │
│ - createdAt: Instant (final)                         │
│ - updatedAt: Instant (mutable)                       │
│ - scheduledAt: Instant (final)                       │
│ - groupId: String (final)                            │
├──────────────────────────────────────────────────────┤
│ - Task(Builder)              // private constructor  │
│ + builder(String name): Builder  // static factory   │
│ + updateStatus(TaskStatus): void                     │
│ + getters...                                         │
├──────────────────────────────────────────────────────┤
│  <<static inner class>>                              │
│  Builder                                             │
│  ┌──────────────────────────────────────────────┐    │
│  │ - id = UUID.randomUUID()                     │    │
│  │ - name: String (required, final)             │    │
│  │ - description = ""                           │    │
│  │ - taskType = ONE_TIME                        │    │
│  │ - priority = MEDIUM                          │    │
│  │ - status = PENDING                           │    │
│  │ - payload = new HashMap<>()                  │    │
│  │ - maxRetries = 3                             │    │
│  │ - timeoutMillis = 60_000                     │    │
│  │ - createdAt = Instant.now()                  │    │
│  ├──────────────────────────────────────────────┤    │
│  │ + Builder(String name)                       │    │
│  │ + description(String): Builder               │    │
│  │ + taskType(TaskType): Builder                │    │
│  │ + priority(TaskPriority): Builder            │    │
│  │ + addPayload(String,String): Builder         │    │
│  │ + cronExpression(String): Builder            │    │
│  │ + maxRetries(int): Builder                   │    │
│  │ + build(): Task                              │    │
│  └──────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────┘
```

### Interview Soundbite

> "Task has 14 fields but only name is required, so a telescoping constructor would be
> unmaintainable. The Builder gives us fluent construction with sensible defaults, a
> private constructor to enforce the build path, and an unmodifiable defensive copy of
> the payload map."

---

## 5. Factory Pattern (AppConfig)

**GoF Category:** Creational

### Anti-Pattern (Before)

```java
// ANTI-PATTERN: every class creates its own dependencies
public class SchedulerService {
    private final TaskService taskService;
    private final ExecutionService executionService;

    public SchedulerService() {
        TaskRepository taskRepo = new InMemoryTaskRepository();
        ExecutionRepository execRepo = new InMemoryExecutionRepository();
        WorkerRepository workerRepo = new InMemoryWorkerRepository();
        RetryStrategy retry = new ExponentialBackoffRetryStrategy(1000, 30000, 2.0);

        this.taskService = new TaskService(taskRepo, execRepo);
        this.executionService = new ExecutionService(taskRepo, execRepo, workerRepo, retry);
        // Problem: each service creates its own repo instances
        // taskService.taskRepo != executionService.taskRepo -- data is siloed!
    }
}
```

**Problems:** Each service creates its own repository instances -- data is not shared.
Cannot swap implementations. Cannot test with mocks. Tight coupling everywhere.

### Clean Solution (After)

```java
// CLEAN: Composition Root -- single wiring point
public class AppConfig {

    // Lazy-initialized singletons
    private TaskRepository taskRepository;
    private SchedulerService schedulerService;

    public TaskRepository getTaskRepository() {
        if (taskRepository == null) {
            taskRepository = new InMemoryTaskRepository();  // swap here for JDBC
        }
        return taskRepository;
    }

    public SchedulerService getSchedulerService() {
        if (schedulerService == null) {
            schedulerService = new SchedulerService(
                getTaskService(),           // shared TaskRepository
                getWorkerService(),
                getExecutionService(),      // same TaskRepository instance
                getSchedulerEngine(),
                getAssignmentStrategy(),
                getSchedulingStrategy()
            );
        }
        return schedulerService;
    }

    // Strategies are swappable -- setters clear dependent services
    public void setAssignmentStrategy(TaskAssignmentStrategy strategy) {
        this.assignmentStrategy = strategy;
        this.schedulerService = null;   // force re-creation with new strategy
        this.failoverService = null;
    }
}
```

### Numbered Call Chain

```
1. main() creates AppConfig config = new AppConfig()
2. config.getSchedulerController() -- first call triggers lazy creation:
   2a. getSchedulerController() -> needs SchedulerService and MonitoringService
   2b. getSchedulerService() -> needs TaskService, WorkerService, ExecutionService,
       SchedulerEngine, TaskAssignmentStrategy, SchedulingStrategy
   2c. getTaskService() -> needs TaskRepository and ExecutionRepository
   2d. getTaskRepository() -> creates InMemoryTaskRepository (singleton for this config)
   2e. All services share the SAME TaskRepository instance
3. Later: config.setAssignmentStrategy(new LeastLoadedAssignmentStrategy())
   3a. Stores new strategy
   3b. Nulls out schedulerService, failoverService, schedulerController
   3c. Next getSchedulerService() call creates a fresh service with the new strategy
4. All other dependencies (repos, engine) are preserved -- only strategy-dependent
   services are recreated
```

### ASCII Class Diagram

```
┌──────────────────────────────────────────────────────────┐
│                        AppConfig                          │
│                 (Factory + Composition Root)               │
├──────────────────────────────────────────────────────────┤
│ - taskRepository: TaskRepository                         │
│ - workerRepository: WorkerRepository                     │
│ - executionRepository: ExecutionRepository               │
│ - schedulerNodeRepository: SchedulerNodeRepository       │
│ - cronParser: CronParser                                 │
│ - taskQueue: TaskQueue                                   │
│ - dependencyResolver: DependencyResolver                 │
│ - schedulerEngine: SchedulerEngine                       │
│ - assignmentStrategy: TaskAssignmentStrategy (swappable) │
│ - retryStrategy: RetryStrategy (swappable)               │
│ - schedulingStrategy: SchedulingStrategy (swappable)     │
│ - taskService, workerService, executionService, ...      │
│ - schedulerService, schedulerController, statsDisplay    │
├──────────────────────────────────────────────────────────┤
│ + getTaskRepository(): TaskRepository    // lazy init    │
│ + getWorkerRepository(): WorkerRepository                │
│ + getSchedulerEngine(): SchedulerEngine                  │
│ + getSchedulerService(): SchedulerService                │
│ + getSchedulerController(): SchedulerController          │
│ + setAssignmentStrategy(s)  // clears dependents         │
│ + setRetryStrategy(s)       // clears dependents         │
│ + setSchedulingStrategy(s)  // clears dependents         │
└──────────────────────────────────────────────────────────┘
         │ creates (lazy)
         │
    ┌────┴────────────────────────────────────────┐
    │         │          │          │              │
    v         v          v          v              v
  Repos    Engine    Strategies  Services    Controller
```

### Interview Soundbite

> "AppConfig is our composition root -- a manual DI container. Every getter lazily
> creates and wires its dependency graph. Strategy setters null out dependent services
> so they get recreated with the new algorithm. No DI framework needed -- total
> transparency of the object graph."

---

## 6. Repository Pattern (Data Access)

**GoF Category:** Structural (DDD pattern, not classic GoF but widely recognized)

### Anti-Pattern (Before)

```java
// ANTI-PATTERN: data access mixed into business logic
public class TaskService {
    private final Map<String, Task> taskStore = new ConcurrentHashMap<>();

    public Task createTask(Task task) {
        taskStore.put(task.getId(), task);  // storage coupled to service
        return task;
    }

    public List<Task> getTasksByStatus(TaskStatus status) {
        return taskStore.values().stream()
            .filter(t -> t.getStatus() == status)
            .toList();   // filtering logic duplicated everywhere
    }
}
```

**Problems:** Storage mechanism is baked into every service. Cannot swap to a database
without rewriting business logic. Query logic is duplicated across services.

### Clean Solution (After)

```java
// CLEAN: Interface defines the contract
public interface TaskRepository {
    void save(Task task);
    Optional<Task> findById(String id);
    List<Task> findByStatus(TaskStatus status);
    List<Task> findByGroupId(String groupId);
    List<Task> findAll();
    void deleteById(String id);
    boolean existsById(String id);
}

// InMemory implementation -- swap for JDBC/DynamoDB/Redis
public class InMemoryTaskRepository implements TaskRepository {
    private final Map<String, Task> store = new ConcurrentHashMap<>();

    @Override
    public void save(Task task) { store.put(task.getId(), task); }

    @Override
    public Optional<Task> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Task> findByStatus(TaskStatus status) {
        return store.values().stream()
            .filter(task -> task.getStatus() == status)
            .toList();
    }
}

// Service depends on interface, not implementation
public class TaskService {
    private final TaskRepository taskRepo;

    public TaskService(TaskRepository taskRepo, ExecutionRepository execRepo) {
        this.taskRepo = taskRepo;
    }
}
```

### Numbered Call Chain

```
1. TaskService.createTask(task) validates business rules
2. taskRepo.save(task) -- delegates to repository interface
3. InMemoryTaskRepository.save(): store.put(task.getId(), task)
   (ConcurrentHashMap for thread safety)
4. Later: taskRepo.findByStatus(TaskStatus.RUNNING)
5. InMemoryTaskRepository: stream().filter().toList()
6. To swap to JDBC: implement TaskRepository with PreparedStatement queries
   and wire in AppConfig -- TaskService code unchanged
```

### ASCII Class Diagram

```
  <<interface>>              <<interface>>
  TaskRepository             WorkerRepository
┌──────────────────┐      ┌──────────────────────┐
│ save(Task)       │      │ save(Worker)          │
│ findById(id)     │      │ findById(id)          │
│ findByStatus()   │      │ findByStatus()        │
│ findByGroupId()  │      │ findAvailable()       │
│ findAll()        │      │ findAll()             │
│ deleteById(id)   │      │ deleteById(id)        │
│ existsById(id)   │      └──────────┬────────────┘
└──────────┬───────┘                 │
           │                         │
           v                         v
┌──────────────────┐      ┌──────────────────────┐
│ InMemoryTask     │      │ InMemoryWorker       │
│ Repository       │      │ Repository           │
│ CHM<id, Task>    │      │ CHM<id, Worker>      │
└──────────────────┘      └──────────────────────┘

  <<interface>>              <<interface>>
  ExecutionRepository        SchedulerNodeRepository
┌──────────────────┐      ┌──────────────────────┐
│ save(Execution)  │      │ save(SchedulerNode)  │
│ findById(id)     │      │ findById(id)         │
│ findByTaskId()   │      │ findLeader()         │
│ findByWorkerId() │      │ findAlive(timeout)   │
│ findLatestBy     │      │ findAll()            │
│   TaskId()       │      │ deleteById(id)       │
│ findAll()        │      └──────────┬───────────┘
└──────────┬───────┘                 │
           │                         │
           v                         v
┌──────────────────┐      ┌──────────────────────┐
│ InMemoryExec     │      │ InMemoryScheduler    │
│ Repository       │      │ NodeRepository       │
│ CHM<id, Exec>    │      │ CHM<id, Node>        │
└──────────────────┘      └──────────────────────┘
```

### Interview Soundbite

> "Four repository interfaces abstract all data access behind findByX methods. The
> InMemory implementations use ConcurrentHashMap for thread-safe reads. Swapping to
> JDBC or DynamoDB is a single class per entity -- zero service-layer changes."

---

## 7. Facade Pattern (SchedulerService)

**GoF Category:** Structural

### Anti-Pattern (Before)

```java
// ANTI-PATTERN: client must know and orchestrate 6 subsystems
public class SchedulerController {
    public void submitAndDispatch(Task task) {
        // Client must know the exact sequence across 6 services
        TaskService taskService = ...;
        SchedulerEngine engine = ...;
        WorkerService workerService = ...;
        TaskAssignmentStrategy strategy = ...;
        ExecutionService executionService = ...;
        SchedulingStrategy schedulingStrategy = ...;

        Task created = taskService.createTask(task);
        engine.submitTask(created);
        List<Task> ready = engine.tick();
        ready = ready.stream()
            .filter(t -> schedulingStrategy.shouldScheduleNow(t, Instant.now()))
            .toList();
        List<Worker> workers = workerService.getAvailableWorkers();
        for (Task t : ready) {
            Optional<Worker> w = strategy.assignTask(t, workers);
            w.ifPresent(worker -> executionService.executeTask(t, worker));
        }
        // Duplicate this 20-line sequence everywhere you need to dispatch
    }
}
```

### Clean Solution (After)

```java
// CLEAN: Facade hides the complexity behind 2-3 method calls
public class SchedulerService {
    private final TaskService taskService;
    private final WorkerService workerService;
    private final ExecutionService executionService;
    private final SchedulerEngine engine;
    private final TaskAssignmentStrategy assignmentStrategy;
    private final SchedulingStrategy schedulingStrategy;

    public Task submitTask(Task task) {
        Task created = taskService.createTask(task);
        engine.submitTask(created);
        return created;
    }

    public void scheduleAndDispatch() {
        // All 20 lines of orchestration hidden behind one method
    }
}

// Client is now simple:
public class SchedulerController {
    public void submitAndDispatch(Task task) {
        schedulerService.submitTask(task);
        schedulerService.scheduleAndDispatch();
    }
}
```

### Numbered Call Chain

```
1. SchedulerController.submitTask(task)
2. -> SchedulerService.submitTask(task)           [Facade entry point]
   2a. -> TaskService.createTask(task)            [validates + persists]
   2b. -> SchedulerEngine.submitTask(task)        [enqueues in priority queue]
3. SchedulerController.runSchedulingCycle()
4. -> SchedulerService.scheduleAndDispatch()      [Facade orchestrates]
   4a. -> engine.tick()                           [cron + deps + queue]
   4b. -> schedulingStrategy.shouldScheduleNow()  [filter by timing]
   4c. -> workerService.getAvailableWorkers()     [get worker pool]
   4d. -> assignmentStrategy.assignTask()         [pick worker]
   4e. -> executionService.executeTask()          [run the task]
5. Client only sees: submitTask() + scheduleAndDispatch()
   -- all 6 subsystems are hidden
```

### ASCII Class Diagram

```
     SchedulerController
    ┌───────────────────────┐
    │ submitTask(task)      │──────┐
    │ runSchedulingCycle()  │      │
    │ getDashboard()        │      │
    └───────────────────────┘      │
                                   v
                        ┌─────────────────────┐
                        │  SchedulerService   │ <-- FACADE
                        │  (6 dependencies)   │
                        ├─────────────────────┤
                        │ submitTask()        │
                        │ scheduleAndDispatch│
                        │ cancelTask()       │
                        │ getTask()          │
                        │ getTaskStatus()    │
                        │ notifyCompletion() │
                        └──┬──┬──┬──┬──┬──┬──┘
                           │  │  │  │  │  │
              ┌────────────┘  │  │  │  │  └────────────┐
              v               v  │  v  v               v
    ┌──────────────┐  ┌────────┐│┌─────────┐  ┌──────────────┐
    │ TaskService  │  │Worker  │││Execution │  │SchedulingStr.│
    └──────────────┘  │Service │││Service   │  └──────────────┘
                      └────────┘│└─────────┘
                                v
                     ┌────────────────────┐  ┌─────────────────┐
                     │ SchedulerEngine    │  │ AssignmentStr.  │
                     └────────────────────┘  └─────────────────┘
```

### Interview Soundbite

> "SchedulerService is a Facade that orchestrates six subsystems -- TaskService,
> WorkerService, ExecutionService, SchedulerEngine, and two strategy interfaces --
> behind a simple submit/dispatch API. The controller never touches the subsystems
> directly, which keeps the dependency surface minimal."

---

## 8. State Pattern (Task Lifecycle)

**GoF Category:** Behavioral

### Anti-Pattern (Before)

```java
// ANTI-PATTERN: scattered if/else checks for status throughout codebase
public class ExecutionService {
    public void executeTask(Task task, Worker worker) {
        if (task.status == "PENDING" || task.status == "QUEUED") {
            task.status = "RUNNING";
            // ... execute ...
            if (success) {
                task.status = "COMPLETED";
            } else {
                if (retries < maxRetries) {
                    task.status = "RETRYING";
                } else {
                    task.status = "FAILED";
                }
            }
        }
        // String-based status -- typos compile fine, invalid transitions unchecked
    }
}
```

### Clean Solution (After)

```java
// CLEAN: Enum with semantic methods for querying state
public enum TaskStatus {
    PENDING,      // just created
    QUEUED,       // in priority queue
    ASSIGNED,     // worker selected
    RUNNING,      // executing
    COMPLETED,    // success (terminal)
    FAILED,       // permanently failed (terminal)
    RETRYING,     // awaiting retry
    CANCELLED,    // user cancelled (terminal)
    TIMED_OUT;    // execution exceeded timeout (terminal)

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED
            || this == CANCELLED || this == TIMED_OUT;
    }

    public boolean isActive() {
        return this == RUNNING || this == ASSIGNED || this == RETRYING;
    }
}

// Explicit transitions via dedicated methods:
public class TaskExecution {
    public void markStarted()              { status = RUNNING; startTime = now(); }
    public void markCompleted(TaskResult r) { status = COMPLETED; endTime = now(); result = r; }
    public void markFailed(String error)    { status = FAILED; endTime = now(); }
}
```

### Numbered Call Chain

```
Task lifecycle through status transitions:

1. Task.builder("...").build()
   -> status = PENDING (default in Builder)

2. SchedulerEngine.submitTask(task)
   -> task.updateStatus(QUEUED)   // PENDING -> QUEUED

3. SchedulerService.scheduleAndDispatch()
   -> task.updateStatus(ASSIGNED)  // QUEUED -> ASSIGNED

4. ExecutionService.executeTask(task, worker)
   -> execution.markStarted()      // PENDING -> RUNNING
   -> task.updateStatus(RUNNING)   // ASSIGNED -> RUNNING

5a. Success path:
    -> execution.markCompleted(result)  // RUNNING -> COMPLETED
    -> task.updateStatus(COMPLETED)     // RUNNING -> COMPLETED

5b. Failure path (retry available):
    -> execution.markFailed(error)      // RUNNING -> FAILED
    -> task.updateStatus(RETRYING)      // RUNNING -> RETRYING
    -> (next cycle) task.updateStatus(ASSIGNED) -> RUNNING -> ...

5c. Failure path (retries exhausted):
    -> execution.markFailed(error)      // RUNNING -> FAILED
    -> task.updateStatus(FAILED)        // RUNNING -> FAILED (terminal)

6. Cancel path:
   -> TaskService.cancelTask(taskId)
   -> task.updateStatus(CANCELLED)      // any non-terminal -> CANCELLED
   -> Blocked if already COMPLETED or FAILED
```

### ASCII Class Diagram

```
                        ┌──────────────┐
                        │   PENDING    │  initial state
                        └──────┬───────┘
                               │ submitTask()
                               v
                        ┌──────────────┐
              ┌────────>│   QUEUED     │  in priority queue
              │         └──────┬───────┘
              │                │ assignTask()
              │                v
              │         ┌──────────────┐
              │    ┌───>│  ASSIGNED    │  worker selected
              │    │    └──────┬───────┘
              │    │           │ markStarted()
              │    │           v
              │    │    ┌──────────────┐
              │    │    │   RUNNING    │  executing on worker
              │    │    └──┬───────┬───┘
              │    │       │       │
              │    │  success   failure
              │    │       │       │
              │    │       v       v
              │    │  ┌────────┐ ┌────────────┐
              │    │  │COMPLETE│ │  retry?    │
              │    │  │(term.) │ │            │
              │    │  └────────┘ └──┬──────┬──┘
              │    │                │ yes  │ no
              │    │                v      v
              │    │         ┌────────┐ ┌────────┐
              │    └─────────│RETRYING│ │ FAILED │
              │              └────────┘ │(term.) │
              │                         └────────┘
              │
              │  reassignTasks()    ┌──────────┐  ┌──────────┐
              └────────────────────>│CANCELLED │  │TIMED_OUT │
                                   │(terminal)│  │(terminal)│
                                   └──────────┘  └──────────┘

    TaskStatus.isTerminal() = COMPLETED | FAILED | CANCELLED | TIMED_OUT
    TaskStatus.isActive()   = RUNNING | ASSIGNED | RETRYING
```

### Interview Soundbite

> "TaskStatus is an enum with 9 states and two helper methods -- isTerminal() and
> isActive(). State transitions are enforced by dedicated methods like markStarted()
> and markCompleted() on TaskExecution, not raw setters. The cancel path explicitly
> blocks cancellation of terminal tasks."

---

## 9. Observer Pattern (Dependency Resolution)

**GoF Category:** Behavioral

### Anti-Pattern (Before)

```java
// ANTI-PATTERN: completion handler manually checks all downstream tasks
public class ExecutionService {
    private final List<Task> allTasks;

    public void handleTaskCompletion(TaskExecution exec) {
        task.status = COMPLETED;
        // Manually scan ALL tasks to find dependents -- tightly coupled
        for (Task other : allTasks) {
            if (other.getDependencies().contains(exec.getTaskId())) {
                boolean allDepsComplete = other.getDependencies().stream()
                    .allMatch(depId -> getTask(depId).getStatus() == COMPLETED);
                if (allDepsComplete) {
                    enqueue(other);  // move to queue
                }
            }
        }
        // Dependency checking is tangled with execution logic
    }
}
```

### Clean Solution (After)

```java
// CLEAN: Completion event notifies DependencyResolver, which resolves readiness
public class SchedulerService {
    public void notifyTaskCompletion(String taskId) {
        System.out.println("[SCHEDULER] Task " + taskId
            + " completed -- checking dependent tasks");
        // Observer: completion event triggers dependency re-evaluation
    }
}

// DependencyResolver is the observer -- it reacts to the "completed" set growing
public class DependencyResolver {
    public Set<String> getReadyTasks(Set<String> completedTaskIds) {
        Set<String> ready = new HashSet<>();
        for (var entry : dependencies.entrySet()) {
            String taskId = entry.getKey();
            if (completedTaskIds.contains(taskId)) continue;
            if (completedTaskIds.containsAll(entry.getValue())) {
                ready.add(taskId);  // all deps satisfied
            }
        }
        return ready;
    }
}

// SchedulerEngine.tick() uses this on every heartbeat:
//   1. Check which tasks have completed
//   2. getReadyTasks(completedIds) -> unblock waiting tasks
//   3. Move newly-ready tasks from waitingOnDeps to TaskQueue
```

### Numbered Call Chain

```
1. ExecutionService completes a task -> task.updateStatus(COMPLETED)
2. SchedulerService.notifyTaskCompletion(taskId) is called
   (Observer notification -- the "event")
3. On next engine.tick():
   3a. Build completedIds set from TaskRepository.findByStatus(COMPLETED)
   3b. dependencyResolver.getReadyTasks(completedIds)
   3c. For each ready taskId:
       - Remove from waitingOnDeps map
       - task.updateStatus(QUEUED)
       - taskQueue.enqueue(task)
4. Newly-unblocked tasks are dispatched in the same tick cycle

Example:
  - Extract completes -> completedIds = {extract}
  - getReadyTasks({extract}):
    transform: deps={extract} -- all complete -> READY
    validate:  deps={extract} -- all complete -> READY
    load:      deps={transform,validate} -- NOT all complete -> WAIT
  - transform and validate move to queue
  - Later: both complete -> load becomes ready
```

### ASCII Class Diagram

```
    ExecutionService                SchedulerService
    (event source)                 (mediator)
   ┌──────────────────┐          ┌────────────────────┐
   │ handleCompletion()│────────>│notifyTaskCompletion│
   │ task -> COMPLETED │          │      (taskId)      │
   └──────────────────┘          └─────────┬──────────┘
                                           │
                                           v
                                 SchedulerEngine.tick()
                                ┌──────────────────────┐
                                │ 1. get completedIds  │
                                │ 2. getReadyTasks()   │
                                │ 3. move to queue     │
                                └─────────┬────────────┘
                                          │
                                          v
                                 DependencyResolver
                                 (the "observer")
                                ┌──────────────────────┐
                                │ dependencies:        │
                                │   T -> {deps}        │
                                │                      │
                                │ getReadyTasks(done): │
                                │   if done.containsAll│
                                │     (deps) -> READY  │
                                └──────────────────────┘
```

### Interview Soundbite

> "Task completion is an event that cascades through the dependency graph. When a task
> completes, DependencyResolver checks which downstream tasks now have all their
> dependencies satisfied. This Observer-style decoupling means ExecutionService doesn't
> need to know about the DAG -- it just fires the completion event."

---

## 10. Command Pattern (Task as Executable Unit)

**GoF Category:** Behavioral

### Anti-Pattern (Before)

```java
// ANTI-PATTERN: execution logic is mixed with scheduling logic
public class SchedulerService {
    public void processTask(String name, Map<String, String> payload, int retries) {
        // Scheduling code and execution code in the same method
        Worker worker = pickWorker();
        try {
            // Inline execution logic
            String format = payload.get("format");
            if ("pdf".equals(format)) {
                generatePdf(payload);
            } else if ("csv".equals(format)) {
                generateCsv(payload);
            }
        } catch (Exception e) {
            if (retries > 0) {
                processTask(name, payload, retries - 1);  // retry inline
            }
        }
    }
    // Task definition, scheduling, and execution are all coupled
}
```

### Clean Solution (After)

```java
// CLEAN: Task encapsulates all execution data as a command object
public class Task {
    private final String name;
    private final Map<String, String> payload;  // execution parameters
    private final int maxRetries;
    private final long timeoutMillis;
    private final TaskType taskType;
    private final TaskPriority priority;
    // ... Task is a self-contained command: "what to do + how to retry + when to timeout"
}

// ExecutionService is the Invoker -- it executes the command without knowing its content
public class ExecutionService {
    public TaskExecution executeTask(Task task, Worker worker) {
        // 1. Create execution record (command log)
        TaskExecution execution = new TaskExecution(task.getId(), worker.getId(), attempt);
        execution.markStarted();

        // 2. Execute the command (task carries all needed data in its payload)
        TaskResult result = simulateExecution(task);

        // 3. Record the result
        if (result.isSuccess()) {
            execution.markCompleted(result);
        } else {
            execution.markFailed(result.getOutput());
        }
        return execution;
    }
}

// SchedulerService is the Client -- it creates commands and hands them to the invoker
public class SchedulerService {
    public void scheduleAndDispatch() {
        for (Task task : schedulableTasks) {
            Optional<Worker> worker = assignmentStrategy.assignTask(task, workers);
            worker.ifPresent(w -> executionService.executeTask(task, w));
            // SchedulerService doesn't know what the task DOES -- only that it needs doing
        }
    }
}
```

### Numbered Call Chain

```
1. Client creates the command:
   Task task = Task.builder("Generate report")
       .payload(Map.of("format", "pdf", "recipients", "team@co.com"))
       .maxRetries(3)
       .timeoutMillis(60000)
       .build()
   -- Task is the Command: encapsulates what to do + config

2. Client registers the command:
   schedulerService.submitTask(task)
   -- stored in TaskRepository (command queue/log)
   -- enqueued in SchedulerEngine (command dispatcher)

3. Invoker (SchedulerService) dispatches:
   schedulerService.scheduleAndDispatch()
   -> assignmentStrategy picks a worker (Receiver)
   -> executionService.executeTask(task, worker)

4. Receiver (ExecutionService) executes the command:
   4a. Creates TaskExecution record (command execution log)
   4b. Reads task.getPayload() for execution parameters
   4c. Reads task.getMaxRetries() for retry config
   4d. simulateExecution(task) -> produces TaskResult
   4e. Records result in ExecutionRepository

5. Command (Task) is reusable:
   - Can be queued, dequeued, cancelled, retried
   - Can be persisted and resumed after scheduler restart
   - Can be serialized and sent to a remote worker
```

### ASCII Class Diagram

```
         Client                     Command               Invoker
    ┌──────────────┐          ┌──────────────┐     ┌──────────────────┐
    │ Scheduler    │ creates  │    Task       │     │ ExecutionService │
    │ Service      │────────> │              │     │                  │
    │              │          │ name          │     │ executeTask(     │
    │ submitTask() │          │ payload       │<────│   task, worker)  │
    │ schedule     │          │ maxRetries    │     │                  │
    │ AndDispatch()│          │ timeoutMillis │     │ simulateExec()   │
    └──────────────┘          │ priority      │     │ handleFailure()  │
                              │ cronExpr      │     └──────────────────┘
                              │               │              │
                              │ "What to do"  │              │ uses
                              └──────────────┘              v
                                    │               ┌──────────────┐
                                    │ stored in     │   Worker     │
                                    v               │  (Receiver)  │
                              ┌──────────────┐      │              │
                              │ TaskQueue    │      │ hostname     │
                              │ (queue of    │      │ capacity     │
                              │  commands)   │      │ currentLoad  │
                              └──────────────┘      └──────────────┘
                                    │
                                    │ logged in
                                    v
                              ┌──────────────┐
                              │TaskExecution │
                              │(command log) │
                              │              │
                              │ attemptNumber│
                              │ startTime    │
                              │ endTime      │
                              │ result       │
                              └──────────────┘
```

### Interview Soundbite

> "Task is a Command object -- it encapsulates the execution data (payload), retry
> config (maxRetries), and timeout in a single transferable unit. The scheduler can
> queue, cancel, retry, or reassign a task without knowing what it does. TaskExecution
> is the command log that records every attempt."

---

## Pattern Summary Table

| # | Pattern | GoF Category | Key Interface / Class | Where It Appears | Benefit |
|---|---------|-------------|----------------------|-----------------|---------|
| 1 | Strategy (Scheduling) | Behavioral | `SchedulingStrategy` | `SchedulerService.scheduleAndDispatch()` | Swap timing logic without touching scheduler |
| 2 | Strategy (Retry) | Behavioral | `RetryStrategy` | `ExecutionService.handleTaskFailure()` | Swap backoff vs. fixed interval at runtime |
| 3 | Strategy (Assignment) | Behavioral | `TaskAssignmentStrategy` | `SchedulerService`, `FailoverService` | Swap round-robin vs. least-loaded |
| 4 | Builder | Creational | `Task.Builder` | Task creation throughout | Fluent API for 14-field object |
| 5 | Factory | Creational | `AppConfig` | Composition root | Lazy singleton wiring, strategy swapping |
| 6 | Repository | Structural | 4 interfaces + 4 impls | All services | Decouple storage from logic |
| 7 | Facade | Structural | `SchedulerService` | Controller entry point | Hide 6 subsystem orchestration |
| 8 | State | Behavioral | `TaskStatus` enum | Task lifecycle | 9 states, isTerminal()/isActive() |
| 9 | Observer | Behavioral | `notifyTaskCompletion()` -> `DependencyResolver` | DAG resolution | Completion cascades unblock deps |
| 10 | Command | Behavioral | `Task` as command, `ExecutionService` as invoker | Task queue + execution | Queue, cancel, retry, reassign |

---

## Interview Quick-Fire Answers

**Q: Why Strategy and not just if/else?**
A: Three independent dimensions of variation (scheduling, retry, assignment). Strategy
lets us add a new algorithm as a single new class with zero changes to existing code.
Open/Closed Principle.

**Q: Why Builder instead of a record or constructor?**
A: 14 fields, only 1 required. Telescoping constructor is unreadable. Builder gives
fluent API, sensible defaults, and a private constructor that enforces the build path.

**Q: Why Repository interfaces instead of just ConcurrentHashMap?**
A: Decouples storage from business logic. InMemory for interviews and tests, JDBC for
production. Services code against the interface -- Liskov Substitution applies.

**Q: Why is SchedulerService a Facade and not just a big service?**
A: It owns zero state and zero algorithms. It purely delegates and orchestrates.
The controller calls 2 methods instead of coordinating 6 subsystems.

**Q: How does the Observer pattern work here?**
A: Task completion is an event. DependencyResolver checks which downstream tasks now
have all deps satisfied. The execution layer doesn't know about the DAG -- it just
fires the event.

**Q: Why Command pattern for Task?**
A: Task is a self-contained unit: what to do (payload), how to retry (maxRetries),
when to timeout (timeoutMillis). It can be queued, cancelled, retried, and
reassigned -- all without changing the executor.

---
