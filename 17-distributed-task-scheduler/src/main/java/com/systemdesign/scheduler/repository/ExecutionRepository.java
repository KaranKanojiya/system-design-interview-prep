package com.systemdesign.scheduler.repository;

import com.systemdesign.scheduler.model.TaskExecution;

import java.util.List;
import java.util.Optional;

// Wiring: Storage abstraction for TaskExecution records (each run attempt of a task).
// Used by the retry strategy to count previous attempts and by the dashboard to show history.
public interface ExecutionRepository {

    void save(TaskExecution execution);

    Optional<TaskExecution> findById(String id);

    List<TaskExecution> findByTaskId(String taskId);

    List<TaskExecution> findByWorkerId(String workerId);

    // Returns the most recent execution for a task (by startTime), useful for retry checks
    Optional<TaskExecution> findLatestByTaskId(String taskId);

    List<TaskExecution> findAll();
}
