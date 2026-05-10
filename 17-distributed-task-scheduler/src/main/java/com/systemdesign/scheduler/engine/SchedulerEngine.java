package com.systemdesign.scheduler.engine;

import com.systemdesign.scheduler.model.CronSchedule;
import com.systemdesign.scheduler.model.Task;
import com.systemdesign.scheduler.model.TaskStatus;
import com.systemdesign.scheduler.model.TaskType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// Wiring: Central scheduling coordinator — the "brain" of the distributed task scheduler.
// Called by the SchedulerService on each heartbeat tick:
//   1. Checks CRON tasks and enqueues those whose fire time has passed.
//   2. Resolves dependencies and moves ready tasks into the priority queue.
//   3. Exposes getNextTasks() for the dispatcher to pull a batch for worker assignment.
public class SchedulerEngine {

    private final TaskQueue taskQueue;
    private final DependencyResolver dependencyResolver;
    private final CronParser cronParser;

    // Tracks tasks that are waiting on dependencies (taskId -> Task)
    private final Map<String, Task> waitingOnDeps;

    // Tracks CRON tasks and their schedules (taskId -> CronSchedule)
    private final Map<String, CronSchedule> cronTasks;

    // Tracks when each cron task last fired so we know when to fire next
    private final Map<String, Instant> cronLastFired;

    public SchedulerEngine() {
        this.taskQueue = new TaskQueue();
        this.dependencyResolver = new DependencyResolver();
        this.cronParser = new CronParser();
        this.waitingOnDeps = new HashMap<>();
        this.cronTasks = new HashMap<>();
        this.cronLastFired = new HashMap<>();
    }

    // Constructor for injecting specific components (useful for testing)
    public SchedulerEngine(TaskQueue taskQueue, DependencyResolver dependencyResolver, CronParser cronParser) {
        this.taskQueue = taskQueue;
        this.dependencyResolver = dependencyResolver;
        this.cronParser = cronParser;
        this.waitingOnDeps = new HashMap<>();
        this.cronTasks = new HashMap<>();
        this.cronLastFired = new HashMap<>();
    }

    // --- Task submission ---

    // Validates and enqueues a task directly (no dependencies)
    public void submitTask(Task task) {
        if (task == null) {
            throw new IllegalArgumentException("Task must not be null");
        }

        // If it's a CRON task, register its schedule for periodic firing
        if (task.getTaskType() == TaskType.CRON && task.getCronExpression() != null) {
            CronSchedule schedule = cronParser.parse(task.getCronExpression());
            cronTasks.put(task.getId(), schedule);
            cronLastFired.put(task.getId(), Instant.now());
        }

        task.updateStatus(TaskStatus.QUEUED);
        taskQueue.enqueue(task);
    }

    // Adds dependency edges then enqueues if all dependencies are already met
    public void submitTaskWithDependencies(Task task, List<String> dependsOnTaskIds) {
        if (task == null) {
            throw new IllegalArgumentException("Task must not be null");
        }

        // Register each dependency edge in the resolver
        for (String depId : dependsOnTaskIds) {
            dependencyResolver.addDependency(task.getId(), depId);
        }

        // Park in the waiting map — tick() will move it to the queue when ready
        waitingOnDeps.put(task.getId(), task);
    }

    // --- Scheduling cycle ---

    // One tick of the scheduler loop. Returns tasks that are ready to dispatch.
    public List<Task> tick() {
        Instant now = Instant.now();
        List<Task> readyToDispatch = new ArrayList<>();

        // Step 1: Check CRON tasks — if next fire time has passed, re-enqueue a clone
        for (Map.Entry<String, CronSchedule> entry : cronTasks.entrySet()) {
            String taskId = entry.getKey();
            CronSchedule schedule = entry.getValue();
            Instant lastFired = cronLastFired.getOrDefault(taskId, Instant.EPOCH);

            Optional<Instant> nextFire = cronParser.getNextFireTime(schedule, lastFired);
            if (nextFire.isPresent() && !nextFire.get().isAfter(now)) {
                cronLastFired.put(taskId, now);
                // The actual task re-creation is handled by the service layer;
                // we signal readiness by including the taskId in the result
            }
        }

        // Step 2: Check dependency-waiting tasks — move any that are now ready
        Set<String> completedIds = new HashSet<>(); // populated externally via markCompleted()
        // Note: In the full system, completedIds comes from TaskRepository state.
        // Here we do a lightweight check: tasks with no remaining unmet deps.
        Set<String> nowReady = dependencyResolver.getReadyTasks(completedIds);
        for (String readyId : nowReady) {
            Task task = waitingOnDeps.remove(readyId);
            if (task != null) {
                task.updateStatus(TaskStatus.QUEUED);
                taskQueue.enqueue(task);
            }
        }

        // Step 3: Drain the queue into the dispatch batch
        while (!taskQueue.isEmpty()) {
            taskQueue.dequeue().ifPresent(readyToDispatch::add);
        }

        return readyToDispatch;
    }

    // Returns tasks whose all dependencies are satisfied, given a set of completed task IDs
    public Set<String> getReadyTasks(Set<String> completedTaskIds) {
        return dependencyResolver.getReadyTasks(completedTaskIds);
    }

    // Dequeues up to maxBatch tasks from the priority queue
    public List<Task> getNextTasks(int maxBatch) {
        List<Task> batch = new ArrayList<>();
        for (int i = 0; i < maxBatch && !taskQueue.isEmpty(); i++) {
            taskQueue.dequeue().ifPresent(batch::add);
        }
        return batch;
    }

    // Removes a task from the queue (best-effort; no-op if already dispatched)
    public boolean cancelTask(String taskId) {
        // Also remove from dependency waiting map
        waitingOnDeps.remove(taskId);
        cronTasks.remove(taskId);
        cronLastFired.remove(taskId);
        return taskQueue.remove(taskId);
    }

    public int getQueueSize() {
        return taskQueue.size();
    }

    // Returns a read-only snapshot of all currently queued tasks
    public List<Task> getQueuedTasks() {
        return taskQueue.getAllTasks();
    }

    // Exposes the dependency resolver for external DAG queries
    public DependencyResolver getDependencyResolver() {
        return dependencyResolver;
    }

    // Exposes the task queue for display/monitoring
    public TaskQueue getTaskQueue() {
        return taskQueue;
    }
}
