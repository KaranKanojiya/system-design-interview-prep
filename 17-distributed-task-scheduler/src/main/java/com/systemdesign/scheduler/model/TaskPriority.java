package com.systemdesign.scheduler.model;

/**
 * Task priority levels used by the scheduler's priority queue.
 * Higher value = higher priority (CRITICAL is scheduled first).
 */
public enum TaskPriority implements Comparable<TaskPriority> {
    LOW(0),
    MEDIUM(1),
    HIGH(2),
    CRITICAL(3);

    private final int value;

    TaskPriority(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    /**
     * Returns true if this priority is higher than the other.
     */
    public boolean isHigherThan(TaskPriority other) {
        return this.value > other.value;
    }
}
