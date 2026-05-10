package com.systemdesign.scheduler.exception;

// Wiring: Base exception for all scheduler-specific errors.
// Extended by TaskExecutionException, LeaderElectionException,
// DependencyCycleException, and WorkerUnavailableException.
public class SchedulerException extends RuntimeException {

    public SchedulerException(String message) {
        super(message);
    }

    public SchedulerException(String message, Throwable cause) {
        super(message, cause);
    }
}
