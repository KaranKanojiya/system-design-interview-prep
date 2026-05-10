package com.systemdesign.scheduler.model;

/**
 * Health states of a worker node in the scheduler cluster.
 */
public enum WorkerStatus {
    ACTIVE,
    BUSY,
    DRAINING,
    OFFLINE,
    DEAD
}
