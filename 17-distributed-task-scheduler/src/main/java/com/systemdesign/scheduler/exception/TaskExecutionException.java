package com.systemdesign.scheduler.exception;

// Wiring: Thrown by TaskExecutor when a task fails during execution.
// Carries the taskId so callers can identify which task caused the failure
// and trigger retry logic in SchedulerEngine.
public class TaskExecutionException extends SchedulerException {

    private final String taskId;

    public TaskExecutionException(String taskId, String message) {
        super(message);
        this.taskId = taskId;
    }

    public TaskExecutionException(String taskId, String message, Throwable cause) {
        super(message, cause);
        this.taskId = taskId;
    }

    public String getTaskId() {
        return taskId;
    }
}
