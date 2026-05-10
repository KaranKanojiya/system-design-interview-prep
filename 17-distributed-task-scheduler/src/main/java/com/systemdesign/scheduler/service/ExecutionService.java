package com.systemdesign.scheduler.service;

import com.systemdesign.scheduler.model.Task;
import com.systemdesign.scheduler.model.TaskExecution;
import com.systemdesign.scheduler.model.TaskResult;
import com.systemdesign.scheduler.model.TaskStatus;
import com.systemdesign.scheduler.model.Worker;
import com.systemdesign.scheduler.repository.ExecutionRepository;
import com.systemdesign.scheduler.repository.TaskRepository;
import com.systemdesign.scheduler.repository.WorkerRepository;
import com.systemdesign.scheduler.strategy.retry.RetryStrategy;

import java.util.List;
import java.util.Optional;

// Wiring: Executes tasks on workers and records execution results.
// Coordinates TaskRepository (status updates), ExecutionRepository (history),
// WorkerRepository (load tracking), and RetryStrategy (failure handling).
public class ExecutionService {

    private final TaskRepository taskRepo;        // task status updates
    private final ExecutionRepository execRepo;   // execution record persistence
    private final WorkerRepository workerRepo;    // worker load tracking
    private final RetryStrategy retryStrategy;    // decides whether to retry on failure

    public ExecutionService(TaskRepository taskRepo, ExecutionRepository execRepo,
                            WorkerRepository workerRepo, RetryStrategy retryStrategy) {
        this.taskRepo = taskRepo;
        this.execRepo = execRepo;
        this.workerRepo = workerRepo;
        this.retryStrategy = retryStrategy;
    }

    // 1. Executes a task on a worker: creates execution record, simulates work, records result
    public TaskExecution executeTask(Task task, Worker worker) {
        // Determine attempt number from previous executions
        List<TaskExecution> previousExecutions = execRepo.findByTaskId(task.getId());
        int attemptNumber = previousExecutions.size() + 1;

        // Create execution record
        TaskExecution execution = new TaskExecution(task.getId(), worker.getId(), attemptNumber);
        execution.markStarted();
        execRepo.save(execution);

        // Mark task as RUNNING and increment worker load
        task.updateStatus(TaskStatus.RUNNING);
        taskRepo.save(task);
        worker.incrementLoad();
        workerRepo.save(worker);

        System.out.println("[EXECUTION] Task '" + task.getName() + "' started on worker "
                + worker.getHostname() + " (attempt #" + attemptNumber + ")");

        // Simulate execution
        TaskResult result = simulateExecution(task);

        if (result.isSuccess()) {
            execution.markCompleted(result);
            execRepo.save(execution);
            handleTaskCompletion(execution);
            System.out.println("[EXECUTION] Task '" + task.getName() + "' completed successfully");
        } else {
            execution.markFailed(result.getOutput());
            execRepo.save(execution);
            handleTaskFailure(execution, result.getOutput());
            System.out.println("[EXECUTION] Task '" + task.getName() + "' failed: " + result.getOutput());
        }

        return execution;
    }

    // 2. Handles successful task completion: updates task status, decrements worker load
    public void handleTaskCompletion(TaskExecution execution) {
        taskRepo.findById(execution.getTaskId()).ifPresent(task -> {
            task.updateStatus(TaskStatus.COMPLETED);
            taskRepo.save(task);
        });

        workerRepo.findById(execution.getWorkerId()).ifPresent(worker -> {
            worker.decrementLoad();
            workerRepo.save(worker);
        });
    }

    // 3. Handles task failure: checks retry strategy, either retries or marks as failed
    public void handleTaskFailure(TaskExecution execution, String error) {
        Task task = taskRepo.findById(execution.getTaskId()).orElse(null);
        if (task == null) {
            return;
        }

        // Decrement worker load
        workerRepo.findById(execution.getWorkerId()).ifPresent(worker -> {
            worker.decrementLoad();
            workerRepo.save(worker);
        });

        // Check retry strategy
        if (retryStrategy.shouldRetry(task, execution.getAttemptNumber(), error)) {
            task.updateStatus(TaskStatus.RETRYING);
            taskRepo.save(task);
            System.out.println("[EXECUTION] Task '" + task.getName()
                    + "' scheduled for retry (attempt #" + execution.getAttemptNumber() + ")");
        } else {
            task.updateStatus(TaskStatus.FAILED);
            taskRepo.save(task);
            System.out.println("[EXECUTION] Task '" + task.getName()
                    + "' permanently failed after " + execution.getAttemptNumber() + " attempts");
        }
    }

    // 4. Fetches an execution record by ID
    public Optional<TaskExecution> getExecution(String executionId) {
        return execRepo.findById(executionId);
    }

    // 5. Returns all execution records for a task
    public List<TaskExecution> getExecutionsForTask(String taskId) {
        return execRepo.findByTaskId(taskId);
    }

    // 6. Simulates task execution: tasks with "fail" in the name return failure,
    //    payload "duration" controls sleep time (capped at 100ms for demo)
    public TaskResult simulateExecution(Task task) {
        // Simulate work duration from payload
        long sleepMs = 10; // default
        if (task.getPayload().containsKey("duration")) {
            try {
                sleepMs = Math.min(Long.parseLong(task.getPayload().get("duration")), 100);
            } catch (NumberFormatException ignored) {
                // use default
            }
        }

        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TaskResult.failure("Execution interrupted");
        }

        // Tasks with "fail" in the name simulate failures for demo purposes
        if (task.getName().toLowerCase().contains("fail")) {
            return TaskResult.failure("Simulated failure for task: " + task.getName());
        }

        return TaskResult.success("Task '" + task.getName() + "' executed successfully in " + sleepMs + "ms");
    }
}
