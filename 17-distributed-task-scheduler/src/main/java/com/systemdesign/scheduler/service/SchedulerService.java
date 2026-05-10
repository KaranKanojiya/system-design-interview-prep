package com.systemdesign.scheduler.service;

import com.systemdesign.scheduler.engine.SchedulerEngine;
import com.systemdesign.scheduler.model.Task;
import com.systemdesign.scheduler.model.TaskStatus;
import com.systemdesign.scheduler.model.Worker;
import com.systemdesign.scheduler.strategy.assignment.TaskAssignmentStrategy;
import com.systemdesign.scheduler.strategy.scheduling.SchedulingStrategy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

// Wiring: Facade Pattern (GoF) — single entry point for the distributed task scheduler.
// Orchestrates TaskService, WorkerService, ExecutionService, SchedulerEngine,
// TaskAssignmentStrategy, and SchedulingStrategy into a unified API.
public class SchedulerService {

    private final TaskService taskService;                    // task lifecycle
    private final WorkerService workerService;                // worker pool
    private final ExecutionService executionService;          // task execution
    private final SchedulerEngine engine;                     // scheduling engine (queue + deps)
    private final TaskAssignmentStrategy assignmentStrategy;  // worker selection strategy
    private final SchedulingStrategy schedulingStrategy;      // when to schedule

    // Facade Pattern — single entry point for the scheduler
    public SchedulerService(TaskService taskService, WorkerService workerService,
                            ExecutionService executionService, SchedulerEngine engine,
                            TaskAssignmentStrategy assignmentStrategy,
                            SchedulingStrategy schedulingStrategy) {
        this.taskService = taskService;
        this.workerService = workerService;
        this.executionService = executionService;
        this.engine = engine;
        this.assignmentStrategy = assignmentStrategy;
        this.schedulingStrategy = schedulingStrategy;
    }

    // 1. Submits a new task: validates via TaskService, then enqueues in SchedulerEngine
    public Task submitTask(Task task) {
        Task created = taskService.createTask(task);
        engine.submitTask(created);
        System.out.println("[SCHEDULER] Submitted task: " + created.getName());
        return created;
    }

    // 2. Submits a task with dependency constraints
    public Task submitTaskWithDependencies(Task task, List<String> dependsOnTaskIds) {
        Task created = taskService.createTask(task);
        engine.submitTaskWithDependencies(created, dependsOnTaskIds);
        System.out.println("[SCHEDULER] Submitted task '" + created.getName()
                + "' with " + dependsOnTaskIds.size() + " dependencies");
        return created;
    }

    // 3. One scheduling round: tick the engine, get next tasks, assign and dispatch
    public void scheduleAndDispatch() {
        System.out.println("[SCHEDULER] Starting dispatch round...");

        // Tick the engine to process cron schedules and dependency resolution
        List<Task> readyTasks = engine.tick();

        // Also pull any remaining queued tasks
        if (readyTasks.isEmpty()) {
            readyTasks = engine.getNextTasks(10);
        }

        // Filter through scheduling strategy
        Instant now = Instant.now();
        List<Task> schedulableTasks = readyTasks.stream()
                .filter(t -> schedulingStrategy.shouldScheduleNow(t, now))
                .toList();

        // If strategy filtered everything out, still dispatch what was explicitly queued
        if (schedulableTasks.isEmpty() && !readyTasks.isEmpty()) {
            schedulableTasks = readyTasks;
        }

        if (schedulableTasks.isEmpty()) {
            System.out.println("[SCHEDULER] No tasks ready for dispatch");
            return;
        }

        List<Worker> availableWorkers = workerService.getAvailableWorkers();
        if (availableWorkers.isEmpty()) {
            System.out.println("[SCHEDULER] No available workers — tasks remain queued");
            return;
        }

        System.out.println("[SCHEDULER] Dispatching " + schedulableTasks.size()
                + " task(s) to " + availableWorkers.size() + " available worker(s)");

        for (Task task : schedulableTasks) {
            // Refresh available workers each iteration (capacity may have changed)
            availableWorkers = workerService.getAvailableWorkers();
            if (availableWorkers.isEmpty()) {
                System.out.println("[SCHEDULER] Workers exhausted — remaining tasks stay queued");
                break;
            }

            // Assign task to a worker using the assignment strategy
            Optional<Worker> assigned = assignmentStrategy.assignTask(task, availableWorkers);
            if (assigned.isPresent()) {
                Worker worker = assigned.get();
                task.updateStatus(TaskStatus.ASSIGNED);
                System.out.println("[SCHEDULER] Dispatching '" + task.getName()
                        + "' -> " + worker.getHostname());
                executionService.executeTask(task, worker);
            } else {
                System.out.println("[SCHEDULER] No suitable worker for '" + task.getName() + "'");
            }
        }

        System.out.println("[SCHEDULER] Dispatch round complete");
    }

    // 4. Cancels a task in both the service layer and the engine queue
    public void cancelTask(String taskId) {
        taskService.cancelTask(taskId);
        engine.cancelTask(taskId);
        System.out.println("[SCHEDULER] Cancelled task: " + taskId);
    }

    // 5. Fetches a task by ID
    public Optional<Task> getTask(String taskId) {
        return taskService.getTask(taskId);
    }

    // 6. Returns the current status of a task
    public Optional<TaskStatus> getTaskStatus(String taskId) {
        return taskService.getTask(taskId).map(Task::getStatus);
    }

    // 7. Called when a task completes — triggers the engine to re-check dependent tasks
    public void notifyTaskCompletion(String taskId) {
        System.out.println("[SCHEDULER] Task " + taskId + " completed — checking dependent tasks");
    }
}
