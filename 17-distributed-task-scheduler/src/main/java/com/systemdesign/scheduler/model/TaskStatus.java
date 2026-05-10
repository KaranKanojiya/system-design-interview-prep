package com.systemdesign.scheduler.model;

/**
 * Lifecycle states of a task in the distributed scheduler.
 */
public enum TaskStatus {
    PENDING,
    QUEUED,
    ASSIGNED,
    RUNNING,
    COMPLETED,
    FAILED,
    RETRYING,
    CANCELLED,
    TIMED_OUT;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == TIMED_OUT;
    }

    public boolean isActive() {
        return this == RUNNING || this == ASSIGNED || this == RETRYING;
    }
}
