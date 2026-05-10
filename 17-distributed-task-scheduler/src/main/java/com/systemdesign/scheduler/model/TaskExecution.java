package com.systemdesign.scheduler.model;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

// Wiring: Tracks a single execution attempt of a Task on a specific Worker.
// Multiple TaskExecution records may exist per Task (one per retry attempt).
// Referenced by TaskExecutor and stored in ExecutionHistory.
public class TaskExecution {

    private final String id;
    private final String taskId;
    private final String workerId;
    private final int attemptNumber;
    private TaskStatus status;
    private Instant startTime;
    private Instant endTime;
    private TaskResult result;
    private String errorMessage;

    public TaskExecution(String taskId, String workerId, int attemptNumber) {
        this.id = UUID.randomUUID().toString();
        this.taskId = taskId;
        this.workerId = workerId;
        this.attemptNumber = attemptNumber;
        this.status = TaskStatus.PENDING;
    }

    // --- Lifecycle methods ---

    public void markStarted() {
        this.status = TaskStatus.RUNNING;
        this.startTime = Instant.now();
    }

    public void markCompleted(TaskResult result) {
        this.status = TaskStatus.COMPLETED;
        this.endTime = Instant.now();
        this.result = result;
    }

    public void markFailed(String errorMessage) {
        this.status = TaskStatus.FAILED;
        this.endTime = Instant.now();
        this.errorMessage = errorMessage;
        this.result = TaskResult.failure(errorMessage);
    }

    /**
     * Returns the wall-clock duration of this execution, or Duration.ZERO
     * if the execution has not started yet.
     */
    public Duration getDuration() {
        if (startTime == null) {
            return Duration.ZERO;
        }
        Instant end = (endTime != null) ? endTime : Instant.now();
        return Duration.between(startTime, end);
    }

    // --- Getters ---

    public String getId() { return id; }
    public String getTaskId() { return taskId; }
    public String getWorkerId() { return workerId; }
    public int getAttemptNumber() { return attemptNumber; }
    public TaskStatus getStatus() { return status; }
    public Instant getStartTime() { return startTime; }
    public Instant getEndTime() { return endTime; }
    public TaskResult getResult() { return result; }
    public String getErrorMessage() { return errorMessage; }

    @Override
    public String toString() {
        return "TaskExecution{id='" + id + "', taskId='" + taskId
                + "', worker='" + workerId + "', attempt=" + attemptNumber
                + ", status=" + status + "}";
    }
}
