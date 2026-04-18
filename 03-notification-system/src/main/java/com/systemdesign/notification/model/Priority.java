package com.systemdesign.notification.model;

/**
 * Notification priority levels. Lower value = higher priority.
 * Used to order notifications in a priority queue.
 */
public enum Priority implements Comparable<Priority> {
    CRITICAL(0),
    HIGH(1),
    MEDIUM(2),
    LOW(3);

    private final int value;

    Priority(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    /**
     * Compare by priority value so CRITICAL (0) comes before LOW (3).
     */
    public int compareByValue(Priority other) {
        return Integer.compare(this.value, other.value);
    }
}
