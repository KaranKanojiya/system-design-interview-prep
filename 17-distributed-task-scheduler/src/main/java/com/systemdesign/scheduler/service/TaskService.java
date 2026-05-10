package com.systemdesign.scheduler.service;

import com.systemdesign.scheduler.exception.SchedulerException;
import com.systemdesign.scheduler.model.Task;
import com.systemdesign.scheduler.model.TaskExecution;
import com.systemdesign.scheduler.model.TaskStatus;
import com.systemdesign.scheduler.repository.ExecutionRepository;
import com.systemdesign.scheduler.repository.TaskRepository;

import java.util.List;
import java.util.Optional;

// Wiring: Task lifecycle management service.
// Delegates persistence to TaskRepository and ExecutionRepository.
// Validates business rules before state transitions.
public class TaskService {

    private final TaskRepository taskRepo;       // task CRUD storage
    private final ExecutionRepository execRepo;  // execution history lookup

    public TaskService(TaskRepository taskRepo, ExecutionRepository execRepo) {
        this.taskRepo = taskRepo;
        this.execRepo = execRepo;
    }

    // 1. Validates and persists a new task
    public Task createTask(Task task) {
        if (task.getName() == null || task.getName().isBlank()) {
            throw new SchedulerException("Task name must not be null or empty");
        }
        if (task.getTaskType() == null) {
            throw new SchedulerException("Task type must not be null");
        }
        taskRepo.save(task);
        System.out.println("[TASK SERVICE] Created task: " + task);
        return task;
    }

    // 2. Fetches a task by ID
    public Optional<Task> getTask(String id) {
        return taskRepo.findById(id);
    }

    // 3. Transitions a task to a new status and persists the change
    public void updateTaskStatus(String taskId, TaskStatus newStatus) {
        Task task = taskRepo.findById(taskId)
                .orElseThrow(() -> new SchedulerException("Task not found: " + taskId));
        task.updateStatus(newStatus);
        taskRepo.save(task);
        System.out.println("[TASK SERVICE] Task " + taskId + " status -> " + newStatus);
    }

    // 4. Cancels a task unless it is already in a terminal completed/failed state
    public void cancelTask(String taskId) {
        Task task = taskRepo.findById(taskId)
                .orElseThrow(() -> new SchedulerException("Task not found: " + taskId));

        if (task.getStatus() == TaskStatus.COMPLETED || task.getStatus() == TaskStatus.FAILED) {
            System.out.println("[TASK SERVICE] Cannot cancel task " + taskId
                    + " — already " + task.getStatus());
            return;
        }
        task.updateStatus(TaskStatus.CANCELLED);
        taskRepo.save(task);
        System.out.println("[TASK SERVICE] Cancelled task: " + taskId);
    }

    // 5. Returns all tasks matching the given status
    public List<Task> getTasksByStatus(TaskStatus status) {
        return taskRepo.findByStatus(status);
    }

    // 6. Returns all tasks belonging to a specific group
    public List<Task> getTasksByGroup(String groupId) {
        return taskRepo.findByGroupId(groupId);
    }

    // 7. Returns the full execution history for a task (one record per attempt)
    public List<TaskExecution> getTaskExecutionHistory(String taskId) {
        return execRepo.findByTaskId(taskId);
    }

    // 8. Returns all tasks in the system
    public List<Task> getAllTasks() {
        return taskRepo.findAll();
    }
}
