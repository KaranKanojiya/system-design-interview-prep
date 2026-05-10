package com.systemdesign.scheduler.exception;

// Wiring: Thrown by TaskAssignmentStrategy when no worker is available
// to accept a task. SchedulerEngine catches this to re-enqueue the task
// and retry assignment on the next scheduling cycle.
public class WorkerUnavailableException extends SchedulerException {

    public WorkerUnavailableException(String message) {
        super(message);
    }
}
