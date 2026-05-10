package com.systemdesign.scheduler;

import com.systemdesign.scheduler.config.AppConfig;
import com.systemdesign.scheduler.controller.SchedulerController;
import com.systemdesign.scheduler.display.SchedulerStatsDisplay;
import com.systemdesign.scheduler.engine.DependencyResolver;
import com.systemdesign.scheduler.engine.SchedulerEngine;
import com.systemdesign.scheduler.model.*;
import com.systemdesign.scheduler.service.*;
import com.systemdesign.scheduler.strategy.assignment.LeastLoadedAssignmentStrategy;
import com.systemdesign.scheduler.strategy.assignment.RoundRobinAssignmentStrategy;
import com.systemdesign.scheduler.strategy.retry.ExponentialBackoffRetryStrategy;
import com.systemdesign.scheduler.strategy.retry.FixedIntervalRetryStrategy;
import com.systemdesign.scheduler.strategy.scheduling.CronSchedulingStrategy;
import com.systemdesign.scheduler.strategy.scheduling.DelayedSchedulingStrategy;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Distributed Task Scheduler — System Design Demo
 *
 * Demonstrates: Leader election (Bully), DAG dependency resolution (Kahn's),
 * priority queuing, cron scheduling, retry with exponential backoff,
 * worker assignment strategies, failover, and exactly-once semantics.
 *
 * 12 demos covering all major components.
 */
public class DistributedTaskSchedulerApp {

    private static final String SEPARATOR = "=".repeat(70);

    public static void main(String[] args) {
        System.out.println(SEPARATOR);
        System.out.println("   DISTRIBUTED TASK SCHEDULER — System Design Demo");
        System.out.println("   Staff Engineer Interview Prep: Consensus, DAG, Exactly-Once");
        System.out.println(SEPARATOR);
        System.out.println();

        // --- System initialization via AppConfig (Composition Root) ---
        AppConfig config = new AppConfig();

        // Register workers before running demos
        registerWorkerPool(config);

        // Execute 12 sequential demos
        demo1_SubmitAndExecuteTasks(config);
        demo2_CronScheduling(config);
        demo3_TaskDependencyDAG(config);
        demo4_RoundRobinAssignment(config);
        demo5_LeastLoadedAssignment(config);
        demo6_ExponentialBackoffRetry(config);
        demo7_ExactlyOnceExecution(config);
        demo8_LeaderElection(config);
        demo9_WorkerFailoverAndReassignment(config);
        demo10_TaskPriorityQueue(config);
        demo11_TaskGroupExecution(config);
        demo12_MonitoringDashboard(config);

        // Final summary
        printDesignSummary();
    }

    // ─────────────────────────────────────────────────────────────────
    // Worker Pool Setup
    // ─────────────────────────────────────────────────────────────────
    private static void registerWorkerPool(AppConfig config) {
        WorkerService workerService = config.getWorkerService();

        Worker w1 = new Worker("worker-001", "node-alpha.cluster.local", 8080, 4);
        w1.getTags().add("gpu");
        w1.getTags().add("high-memory");

        Worker w2 = new Worker("worker-002", "node-beta.cluster.local", 8080, 3);
        w2.getTags().add("general");

        Worker w3 = new Worker("worker-003", "node-gamma.cluster.local", 8080, 5);
        w3.getTags().add("general");
        w3.getTags().add("high-cpu");

        workerService.registerWorker(w1);
        workerService.registerWorker(w2);
        workerService.registerWorker(w3);

        System.out.println("[SETUP] Registered 3 workers: alpha(cap=4), beta(cap=3), gamma(cap=5)");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 1: Submit and Execute Simple Tasks
    // ─────────────────────────────────────────────────────────────────
    private static void demo1_SubmitAndExecuteTasks(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 1: Submit and Execute Simple Tasks");
        System.out.println(SEPARATOR);

        SchedulerController controller = config.getSchedulerController();

        // Submit 3 simple tasks
        Task t1 = new Task.Builder("Generate daily report")
                .description("Aggregate metrics and produce PDF report")
                .taskType(TaskType.ONE_TIME)
                .priority(TaskPriority.MEDIUM)
                .payload(Map.of("format", "pdf", "recipients", "team@company.com"))
                .build();

        Task t2 = new Task.Builder("Send welcome emails")
                .description("Batch send onboarding emails to new users")
                .taskType(TaskType.ONE_TIME)
                .priority(TaskPriority.HIGH)
                .payload(Map.of("batch_size", "100", "template", "welcome_v2"))
                .build();

        Task t3 = new Task.Builder("Cleanup temp files")
                .description("Remove files older than 7 days from /tmp")
                .taskType(TaskType.ONE_TIME)
                .priority(TaskPriority.LOW)
                .payload(Map.of("path", "/tmp", "max_age_days", "7"))
                .build();

        controller.submitTask(t1);
        controller.submitTask(t2);
        controller.submitTask(t3);

        // Run scheduling cycle to dispatch
        controller.runSchedulingCycle();

        // Show results
        config.getStatsDisplay().printTaskSummary();
        System.out.println();
        System.out.println("  KEY INSIGHT: Tasks are queued by priority (HIGH > MEDIUM > LOW),");
        System.out.println("  then dispatched to workers via the assignment strategy.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 2: Cron Scheduling (Recurring Tasks)
    // ─────────────────────────────────────────────────────────────────
    private static void demo2_CronScheduling(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 2: Cron Scheduling (Recurring Tasks)");
        System.out.println(SEPARATOR);

        SchedulerService scheduler = config.getSchedulerService();

        // Create a cron-scheduled task: every hour at minute 0
        Task cronTask = new Task.Builder("Hourly metrics aggregation")
                .description("Aggregate system metrics every hour")
                .taskType(TaskType.CRON)
                .priority(TaskPriority.MEDIUM)
                .cronExpression("0 * * * *")
                .build();

        scheduler.submitTask(cronTask);
        System.out.println("[DEMO] Submitted cron task: '0 * * * *' (every hour at minute 0)");

        // Create a delayed task: execute after 5 seconds
        Task delayedTask = new Task.Builder("Delayed notification")
                .description("Send notification after a delay")
                .taskType(TaskType.DELAYED)
                .priority(TaskPriority.LOW)
                .delayMillis(5000)
                .build();

        scheduler.submitTask(delayedTask);
        System.out.println("[DEMO] Submitted delayed task: execute after 5000ms");

        // Simulate a scheduling tick
        System.out.println();
        System.out.println("[DEMO] Cron evaluation:");
        System.out.println("  - Expression: 0 * * * * (minute=0, hour=*, day=*, month=*, dow=*)");
        System.out.println("  - Current minute: " + java.time.LocalTime.now().getMinute());
        System.out.println("  - Will fire next at minute 0 of the next hour");
        System.out.println();
        System.out.println("  KEY INSIGHT: Cron tasks are evaluated on each tick(). The scheduler");
        System.out.println("  checks if the current time matches the cron expression and enqueues");
        System.out.println("  the task if it does. In production: use Quartz or db-backed cron.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 3: Task Dependency DAG
    // ─────────────────────────────────────────────────────────────────
    private static void demo3_TaskDependencyDAG(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 3: Task Dependency DAG (Topological Sort)");
        System.out.println(SEPARATOR);

        SchedulerService scheduler = config.getSchedulerService();
        SchedulerStatsDisplay display = config.getStatsDisplay();

        // Create a DAG:  Extract -> Transform -> Load (ETL pipeline)
        //                 Extract -> Validate ──┘
        Task extract = new Task.Builder("Extract raw data")
                .description("Pull data from source systems")
                .priority(TaskPriority.HIGH)
                .build();

        Task transform = new Task.Builder("Transform data")
                .description("Apply business rules and transformations")
                .priority(TaskPriority.HIGH)
                .build();

        Task validate = new Task.Builder("Validate data quality")
                .description("Run data quality checks")
                .priority(TaskPriority.MEDIUM)
                .build();

        Task load = new Task.Builder("Load to warehouse")
                .description("Write transformed data to data warehouse")
                .priority(TaskPriority.HIGH)
                .build();

        // Submit with dependencies
        scheduler.submitTask(extract);  // No deps — root task
        scheduler.submitTaskWithDependencies(transform, List.of(extract.getId()));
        scheduler.submitTaskWithDependencies(validate, List.of(extract.getId()));
        scheduler.submitTaskWithDependencies(load, List.of(transform.getId(), validate.getId()));

        System.out.println();
        System.out.println("[DEMO] DAG Structure:");
        System.out.println("  Extract ──→ Transform ──→ Load");
        System.out.println("     │                       ↑");
        System.out.println("     └────→ Validate ────────┘");
        System.out.println();

        // Show topological order
        DependencyResolver resolver = config.getSchedulerEngine().getDependencyResolver();
        System.out.println("[DEMO] Topological order (Kahn's algorithm):");
        try {
            List<String> order = resolver.getTopologicalOrder();
            int step = 1;
            for (String taskId : order) {
                Optional<Task> task = scheduler.getTask(taskId);
                String name = task.map(Task::getName).orElse(taskId.substring(0, 8) + "...");
                System.out.println("  Step " + step++ + ": " + name);
            }
        } catch (Exception e) {
            System.out.println("  (No dependencies registered in resolver for these tasks)");
        }

        // Simulate execution: complete Extract, see what becomes ready
        System.out.println();
        System.out.println("[DEMO] After completing 'Extract', ready tasks:");
        Set<String> completed = new HashSet<>();
        completed.add(extract.getId());
        Set<String> ready = resolver.getReadyTasks(completed);
        for (String taskId : ready) {
            Optional<Task> task = scheduler.getTask(taskId);
            System.out.println("  → " + task.map(Task::getName).orElse(taskId));
        }

        System.out.println();
        System.out.println("  KEY INSIGHT: Kahn's algorithm resolves execution order. Tasks only");
        System.out.println("  become 'ready' when ALL upstream dependencies are complete.");
        System.out.println("  Cycle detection prevents deadlocks in the dependency graph.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 4: Round-Robin Worker Assignment
    // ─────────────────────────────────────────────────────────────────
    private static void demo4_RoundRobinAssignment(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 4: Round-Robin Worker Assignment");
        System.out.println(SEPARATOR);

        config.setAssignmentStrategy(new RoundRobinAssignmentStrategy());
        SchedulerService scheduler = config.getSchedulerService();
        WorkerService workerService = config.getWorkerService();

        // Reset worker loads
        for (Worker w : workerService.getAllWorkers()) {
            while (w.getCurrentLoad() > 0) w.decrementLoad();
        }

        // Submit 6 tasks
        for (int i = 1; i <= 6; i++) {
            Task task = new Task.Builder("RR-Task-" + i)
                    .priority(TaskPriority.MEDIUM)
                    .build();
            scheduler.submitTask(task);
        }

        // Dispatch — should round-robin across 3 workers
        scheduler.scheduleAndDispatch();

        // Show distribution
        System.out.println();
        System.out.println("[DEMO] Task distribution (Round-Robin):");
        for (Worker w : workerService.getAllWorkers()) {
            System.out.printf("  %s (%s): load = %d / %d%n",
                    w.getId(), w.getHostname(), w.getCurrentLoad(), w.getCapacity());
        }

        System.out.println();
        System.out.println("  KEY INSIGHT: Round-robin distributes evenly but ignores worker");
        System.out.println("  capacity and current load. Good for homogeneous clusters.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 5: Least-Loaded Worker Assignment
    // ─────────────────────────────────────────────────────────────────
    private static void demo5_LeastLoadedAssignment(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 5: Least-Loaded Worker Assignment");
        System.out.println(SEPARATOR);

        config.setAssignmentStrategy(new LeastLoadedAssignmentStrategy());
        SchedulerService scheduler = config.getSchedulerService();
        WorkerService workerService = config.getWorkerService();

        // Reset worker loads but give worker-002 a head start (pre-loaded)
        for (Worker w : workerService.getAllWorkers()) {
            while (w.getCurrentLoad() > 0) w.decrementLoad();
        }
        // Pre-load worker-002 with 2 tasks
        Worker w2 = workerService.getWorker("worker-002").orElse(null);
        if (w2 != null) {
            w2.incrementLoad();
            w2.incrementLoad();
        }

        System.out.println("[DEMO] Initial loads: alpha=0, beta=2 (pre-loaded), gamma=0");

        // Submit 4 tasks
        for (int i = 1; i <= 4; i++) {
            Task task = new Task.Builder("LL-Task-" + i)
                    .priority(TaskPriority.MEDIUM)
                    .build();
            scheduler.submitTask(task);
        }

        scheduler.scheduleAndDispatch();

        System.out.println();
        System.out.println("[DEMO] Task distribution (Least-Loaded):");
        for (Worker w : workerService.getAllWorkers()) {
            System.out.printf("  %s (%s): load = %d / %d%n",
                    w.getId(), w.getHostname(), w.getCurrentLoad(), w.getCapacity());
        }

        System.out.println();
        System.out.println("  KEY INSIGHT: Least-loaded respects current load and capacity.");
        System.out.println("  Prefers idle workers, avoids overloaded ones. Better for");
        System.out.println("  heterogeneous clusters with different worker capacities.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 6: Task Failure & Exponential Backoff Retry
    // ─────────────────────────────────────────────────────────────────
    private static void demo6_ExponentialBackoffRetry(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 6: Task Failure & Exponential Backoff Retry");
        System.out.println(SEPARATOR);

        // Use exponential backoff with clear parameters
        ExponentialBackoffRetryStrategy retryStrategy =
                new ExponentialBackoffRetryStrategy(1000, 30000, 2.0);
        config.setRetryStrategy(retryStrategy);

        SchedulerService scheduler = config.getSchedulerService();

        // Submit a task that will fail (name contains "fail")
        Task failingTask = new Task.Builder("Process data (will fail)")
                .description("This task simulates failure for retry demo")
                .priority(TaskPriority.HIGH)
                .maxRetries(4)
                .build();

        scheduler.submitTask(failingTask);

        System.out.println("[DEMO] Submitted failing task with maxRetries=4");
        System.out.println();

        // Show retry delay schedule
        System.out.println("[DEMO] Exponential backoff schedule:");
        for (int attempt = 1; attempt <= 4; attempt++) {
            long delay = retryStrategy.getRetryDelayMillis(attempt);
            System.out.printf("  Attempt %d: delay = %,d ms (%.1f seconds)%n",
                    attempt, delay, delay / 1000.0);
        }

        // Dispatch — task will fail and get retried
        scheduler.scheduleAndDispatch();

        System.out.println();
        System.out.println("  KEY INSIGHT: Exponential backoff with jitter prevents thundering");
        System.out.println("  herd. Formula: min(initial * 2^(attempt-1), maxDelay) ± 10% jitter.");
        System.out.println("  After maxRetries exhausted, task moves to FAILED permanently.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 7: Exactly-Once Execution (Idempotency)
    // ─────────────────────────────────────────────────────────────────
    private static void demo7_ExactlyOnceExecution(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 7: Exactly-Once Execution (Idempotency)");
        System.out.println(SEPARATOR);

        SchedulerService scheduler = config.getSchedulerService();
        TaskService taskService = config.getTaskService();

        // Submit a task
        Task task = new Task.Builder("Charge customer payment")
                .description("Process payment — must not double-charge!")
                .priority(TaskPriority.CRITICAL)
                .payload(Map.of("idempotency_key", UUID.randomUUID().toString(),
                        "amount", "99.99", "currency", "USD"))
                .build();

        scheduler.submitTask(task);

        System.out.println("[DEMO] Task submitted with idempotency_key in payload");
        System.out.println("  Idempotency key: " + task.getPayload().get("idempotency_key"));

        // Simulate: try to execute the same task twice
        System.out.println();
        System.out.println("[DEMO] Attempt 1: Execute task...");
        scheduler.scheduleAndDispatch();

        // Try to re-submit the same task (simulate duplicate)
        System.out.println("[DEMO] Attempt 2: Try to re-execute (duplicate)...");
        Optional<Task> existing = taskService.getTask(task.getId());
        if (existing.isPresent() && existing.get().getStatus().isTerminal()) {
            System.out.println("  ✓ BLOCKED: Task already in terminal state ("
                    + existing.get().getStatus() + ")");
            System.out.println("  → Duplicate execution prevented by status check");
        } else {
            System.out.println("  Task not yet in terminal state, would need idempotency key check");
        }

        System.out.println();
        System.out.println("[DEMO] Exactly-once execution strategies:");
        System.out.println("  1. Idempotency key: Client sends unique key, server deduplicates");
        System.out.println("  2. Task status check: Only execute if status is QUEUED/ASSIGNED");
        System.out.println("  3. Database constraint: Unique index on (task_id, attempt_number)");
        System.out.println("  4. Fencing token: Monotonic token prevents stale worker execution");
        System.out.println();
        System.out.println("  KEY INSIGHT: In distributed systems, exactly-once = at-least-once");
        System.out.println("  delivery + idempotent processing. The consumer must handle duplicates.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 8: Leader Election (Bully Algorithm)
    // ─────────────────────────────────────────────────────────────────
    private static void demo8_LeaderElection(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 8: Leader Election (Bully Algorithm)");
        System.out.println(SEPARATOR);

        LeaderElectionService leaderService = config.getLeaderElectionService();

        // Register 3 scheduler nodes with different priorities
        SchedulerNode node1 = new SchedulerNode("scheduler-1", "sched-alpha.cluster", 10);
        SchedulerNode node2 = new SchedulerNode("scheduler-2", "sched-beta.cluster", 20);
        SchedulerNode node3 = new SchedulerNode("scheduler-3", "sched-gamma.cluster", 30);

        leaderService.registerNode(node1);
        leaderService.registerNode(node2);
        leaderService.registerNode(node3);

        System.out.println("[DEMO] Registered 3 scheduler nodes:");
        System.out.println("  scheduler-1 (priority=10), scheduler-2 (priority=20), scheduler-3 (priority=30)");
        System.out.println();

        // Run election
        System.out.println("[DEMO] Running Bully Algorithm election...");
        SchedulerNode leader = leaderService.electLeader();
        if (leader != null) {
            System.out.println("[DEMO] ★ Elected leader: " + leader.getNodeId()
                    + " (priority=" + leader.getPriority() + ")");
        }

        // Simulate leader failure
        System.out.println();
        System.out.println("[DEMO] Simulating leader failure (scheduler-3 goes down)...");
        leaderService.handleNodeFailure("scheduler-3");

        System.out.println("[DEMO] After failover:");
        Optional<SchedulerNode> newLeader = leaderService.getCurrentLeader();
        newLeader.ifPresent(l ->
                System.out.println("[DEMO] ★ New leader: " + l.getNodeId()
                        + " (priority=" + l.getPriority() + ")"));

        System.out.println();
        System.out.println("  KEY INSIGHT: Bully algorithm — highest-priority alive node wins.");
        System.out.println("  O(n²) messages in worst case. In production, use Raft/ZooKeeper/etcd");
        System.out.println("  for consensus. Bully is simple but doesn't handle network partitions.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 9: Worker Failure & Task Reassignment
    // ─────────────────────────────────────────────────────────────────
    private static void demo9_WorkerFailoverAndReassignment(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 9: Worker Failure & Task Reassignment");
        System.out.println(SEPARATOR);

        WorkerService workerService = config.getWorkerService();
        FailoverService failoverService = config.getFailoverService();
        SchedulerStatsDisplay display = config.getStatsDisplay();

        // Show current worker pool
        System.out.println("[DEMO] Current worker pool:");
        display.printWorkerPool();

        // Simulate worker-002 going down (stop heartbeat, then mark as having old heartbeat)
        System.out.println("[DEMO] Simulating worker-002 failure (stale heartbeat)...");
        Worker w2 = workerService.getWorker("worker-002").orElse(null);
        if (w2 != null) {
            // Manually set heartbeat to 2 minutes ago to simulate staleness
            try {
                var field = Worker.class.getDeclaredField("lastHeartbeat");
                field.setAccessible(true);
                field.set(w2, Instant.now().minus(Duration.ofMinutes(2)));
            } catch (Exception e) {
                // Fallback: just mark dead directly
                workerService.markWorkerDead("worker-002");
            }
        }

        // Run failover detection
        failoverService.performFailover(Duration.ofSeconds(30));

        System.out.println();
        System.out.println("[DEMO] Worker pool after failover:");
        display.printWorkerPool();

        System.out.println();
        System.out.println("  KEY INSIGHT: Heartbeat-based failure detection with configurable");
        System.out.println("  timeout. Dead worker's tasks are reassigned to healthy workers.");
        System.out.println("  In production: use gossip protocol for faster failure detection.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 10: Task Priority Queue
    // ─────────────────────────────────────────────────────────────────
    private static void demo10_TaskPriorityQueue(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 10: Task Priority Queue");
        System.out.println(SEPARATOR);

        SchedulerEngine engine = config.getSchedulerEngine();

        // Submit tasks in random priority order
        Task low = new Task.Builder("Low priority: cleanup logs")
                .priority(TaskPriority.LOW).build();
        Task critical = new Task.Builder("Critical: security patch")
                .priority(TaskPriority.CRITICAL).build();
        Task medium = new Task.Builder("Medium: generate report")
                .priority(TaskPriority.MEDIUM).build();
        Task high = new Task.Builder("High: process payments")
                .priority(TaskPriority.HIGH).build();

        // Submit in mixed order
        engine.submitTask(low);
        engine.submitTask(critical);
        engine.submitTask(medium);
        engine.submitTask(high);

        System.out.println("[DEMO] Submitted 4 tasks in order: LOW, CRITICAL, MEDIUM, HIGH");
        System.out.println();
        System.out.println("[DEMO] Dequeue order (priority queue):");

        int order = 1;
        List<Task> batch = engine.getNextTasks(4);
        for (Task t : batch) {
            System.out.printf("  %d. [%s] %s%n", order++, t.getPriority(), t.getName());
        }

        System.out.println();
        System.out.println("  KEY INSIGHT: PriorityQueue with Comparator: CRITICAL > HIGH > MEDIUM > LOW.");
        System.out.println("  Ties broken by createdAt (FIFO within same priority).");
        System.out.println("  In production: use Redis sorted sets or Kafka topic partitions.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 11: Task Group Execution (Parallel Tasks)
    // ─────────────────────────────────────────────────────────────────
    private static void demo11_TaskGroupExecution(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 11: Task Group Execution");
        System.out.println(SEPARATOR);

        TaskService taskService = config.getTaskService();

        // Create a parallel task group: image processing pipeline
        TaskGroup group = new TaskGroup("Image Processing Pipeline", true);  // parallel=true

        Task resize = new Task.Builder("Resize images")
                .description("Resize to multiple dimensions")
                .priority(TaskPriority.MEDIUM)
                .payload(Map.of("group_id", group.getId()))
                .build();
        resize = new Task.Builder(resize.getName())
                .description(resize.getDescription())
                .priority(resize.getPriority())
                .groupId(group.getId())
                .build();

        Task watermark = new Task.Builder("Apply watermarks")
                .description("Add company watermark overlay")
                .priority(TaskPriority.MEDIUM)
                .groupId(group.getId())
                .build();

        Task compress = new Task.Builder("Compress images")
                .description("Optimize file size with lossy compression")
                .priority(TaskPriority.MEDIUM)
                .groupId(group.getId())
                .build();

        taskService.createTask(resize);
        taskService.createTask(watermark);
        taskService.createTask(compress);
        group.addTask(resize.getId());
        group.addTask(watermark.getId());
        group.addTask(compress.getId());

        System.out.println("[DEMO] Created task group: " + group.getName());
        System.out.println("  Mode: " + (group.isParallel() ? "PARALLEL" : "SEQUENTIAL"));
        System.out.println("  Tasks: " + group.size());
        System.out.println();

        System.out.println("[DEMO] Group tasks:");
        for (Task t : taskService.getTasksByGroup(group.getId())) {
            System.out.println("  → " + t.getName() + " [" + t.getStatus() + "]");
        }

        System.out.println();
        System.out.println("  KEY INSIGHT: Task groups enable batch operations. Parallel groups");
        System.out.println("  dispatch all tasks simultaneously. Sequential groups use implicit");
        System.out.println("  dependencies (task N+1 depends on task N). Groups are tracked by");
        System.out.println("  groupId for monitoring and cancellation.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 12: Monitoring & Stats Dashboard
    // ─────────────────────────────────────────────────────────────────
    private static void demo12_MonitoringDashboard(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 12: Monitoring & Stats Dashboard");
        System.out.println(SEPARATOR);

        MonitoringService monitoring = config.getMonitoringService();
        SchedulerStatsDisplay display = config.getStatsDisplay();

        monitoring.printDashboard();

        System.out.println();
        display.printStats();

        System.out.println();
        System.out.println("  KEY INSIGHT: Observability is critical for distributed schedulers.");
        System.out.println("  Track: task throughput, failure/retry rates, worker utilization,");
        System.out.println("  queue depth, and P99 execution latency. Alert on anomalies.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Design Summary
    // ─────────────────────────────────────────────────────────────────
    private static void printDesignSummary() {
        System.out.println(SEPARATOR);
        System.out.println("  DESIGN SUMMARY — Distributed Task Scheduler");
        System.out.println(SEPARATOR);
        System.out.println();
        System.out.println("  Core Data Structures:");
        System.out.println("    • PriorityQueue — O(log n) enqueue/dequeue by priority");
        System.out.println("    • DAG (adjacency list) — task dependency graph");
        System.out.println("    • Topological Sort (Kahn's) — resolve execution order");
        System.out.println("    • ConcurrentHashMap — in-memory task/worker storage");
        System.out.println();
        System.out.println("  Distributed Algorithms:");
        System.out.println("    • Bully Algorithm — leader election (highest priority wins)");
        System.out.println("    • Heartbeat — failure detection with configurable timeout");
        System.out.println("    • Exponential Backoff — retry with jitter (±10%)");
        System.out.println("    • Idempotency — exactly-once via keys + status checks");
        System.out.println();
        System.out.println("  Design Patterns (GoF):");
        System.out.println("    • Strategy — scheduling, retry, and assignment algorithms");
        System.out.println("    • Builder — Task construction with fluent API");
        System.out.println("    • Factory — AppConfig as composition root");
        System.out.println("    • Repository — data access abstraction (6 repos)");
        System.out.println("    • Facade — SchedulerService orchestrates all services");
        System.out.println("    • State — task lifecycle (PENDING→QUEUED→RUNNING→COMPLETED)");
        System.out.println("    • Observer — task completion triggers dependency resolution");
        System.out.println("    • Chain of Responsibility — validation pipeline");
        System.out.println("    • Command — task as executable unit");
        System.out.println("    • Singleton — AppConfig lazy initialization");
        System.out.println();
        System.out.println("  Staff-Level Topics Covered:");
        System.out.println("    • Leader election & consensus");
        System.out.println("    • Exactly-once semantics");
        System.out.println("    • DAG-based workflow orchestration");
        System.out.println("    • Worker failover & task reassignment");
        System.out.println("    • Priority scheduling with fairness");
        System.out.println("    • Cron scheduling & delayed execution");
        System.out.println("    • Monitoring & observability");
        System.out.println();
        System.out.println(SEPARATOR);
        System.out.println("  End of Distributed Task Scheduler Demo");
        System.out.println(SEPARATOR);
    }
}
