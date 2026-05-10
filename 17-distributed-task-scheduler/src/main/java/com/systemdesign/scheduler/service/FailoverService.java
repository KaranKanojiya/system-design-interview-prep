package com.systemdesign.scheduler.service;

import com.systemdesign.scheduler.model.Task;
import com.systemdesign.scheduler.model.TaskExecution;
import com.systemdesign.scheduler.model.TaskStatus;
import com.systemdesign.scheduler.model.Worker;
import com.systemdesign.scheduler.model.WorkerStatus;
import com.systemdesign.scheduler.repository.ExecutionRepository;
import com.systemdesign.scheduler.strategy.assignment.TaskAssignmentStrategy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// Wiring: Failover service that detects dead workers and reassigns their tasks.
// Coordinates WorkerService (liveness), TaskService (status transitions),
// ExecutionRepository (find in-flight tasks), and TaskAssignmentStrategy (reassignment).
public class FailoverService {

    private final WorkerService workerService;                // worker liveness checks
    private final TaskService taskService;                    // task status management
    private final ExecutionRepository execRepo;               // find running executions
    private final TaskAssignmentStrategy assignmentStrategy;  // reassign tasks to healthy workers

    public FailoverService(WorkerService workerService, TaskService taskService,
                           ExecutionRepository execRepo,
                           TaskAssignmentStrategy assignmentStrategy) {
        this.workerService = workerService;
        this.taskService = taskService;
        this.execRepo = execRepo;
        this.assignmentStrategy = assignmentStrategy;
    }

    // 1. Detects workers whose heartbeat is older than the timeout and marks them DEAD
    public List<Worker> detectDeadWorkers(Duration heartbeatTimeout) {
        List<Worker> deadWorkers = new ArrayList<>();

        for (Worker worker : workerService.getAllWorkers()) {
            Duration sinceLastHeartbeat = Duration.between(worker.getLastHeartbeat(), Instant.now());
            if (sinceLastHeartbeat.compareTo(heartbeatTimeout) > 0
                    && worker.getStatus() != WorkerStatus.DEAD
                    && worker.getStatus() != WorkerStatus.OFFLINE) {
                workerService.markWorkerDead(worker.getId());
                deadWorkers.add(worker);
                System.out.println("[FAILOVER] Detected dead worker: " + worker.getHostname()
                        + " (last heartbeat: " + sinceLastHeartbeat.toSeconds() + "s ago)");
            }
        }

        return deadWorkers;
    }

    // 2. Reassigns RUNNING tasks from dead workers to available healthy workers
    public int reassignTasks(List<Worker> deadWorkers) {
        int reassignedCount = 0;

        for (Worker deadWorker : deadWorkers) {
            // Find all executions that were RUNNING on the dead worker
            List<TaskExecution> runningExecutions = execRepo.findByWorkerId(deadWorker.getId())
                    .stream()
                    .filter(exec -> exec.getStatus() == TaskStatus.RUNNING)
                    .toList();

            for (TaskExecution execution : runningExecutions) {
                // Reset task to QUEUED for reassignment
                taskService.updateTaskStatus(execution.getTaskId(), TaskStatus.QUEUED);

                // Find available workers and assign via strategy
                List<Worker> availableWorkers = workerService.getAvailableWorkers();
                if (!availableWorkers.isEmpty()) {
                    Task task = taskService.getTask(execution.getTaskId()).orElse(null);
                    if (task != null) {
                        Optional<Worker> newWorker = assignmentStrategy.assignTask(task, availableWorkers);
                        if (newWorker.isPresent()) {
                            taskService.updateTaskStatus(execution.getTaskId(), TaskStatus.ASSIGNED);
                            reassignedCount++;
                            System.out.println("[FAILOVER] Reassigned task " + execution.getTaskId()
                                    + " from " + deadWorker.getHostname()
                                    + " to " + newWorker.get().getHostname());
                        }
                    }
                } else {
                    System.out.println("[FAILOVER] No available workers for task "
                            + execution.getTaskId() + " — remains QUEUED");
                }
            }
        }

        return reassignedCount;
    }

    // 3. Full failover cycle: detect dead workers, then reassign their tasks
    public void performFailover(Duration timeout) {
        System.out.println("[FAILOVER] Starting failover detection (timeout=" + timeout.toSeconds() + "s)...");

        List<Worker> deadWorkers = detectDeadWorkers(timeout);
        if (deadWorkers.isEmpty()) {
            System.out.println("[FAILOVER] No dead workers detected — cluster healthy");
            return;
        }

        System.out.println("[FAILOVER] Found " + deadWorkers.size() + " dead worker(s) — reassigning tasks...");
        int reassigned = reassignTasks(deadWorkers);
        System.out.println("[FAILOVER] Failover complete — " + reassigned + " task(s) reassigned");
    }
}
