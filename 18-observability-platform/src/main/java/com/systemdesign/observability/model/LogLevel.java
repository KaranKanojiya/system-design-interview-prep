package com.systemdesign.observability.model;

/**
 * Log severity levels (0 = least severe, 5 = most severe).
 */
public enum LogLevel {
    TRACE(0),
    DEBUG(1),
    INFO(2),
    WARN(3),
    ERROR(4),
    FATAL(5);

    private final int severity;

    LogLevel(int severity) {
        this.severity = severity;
    }

    public int getSeverity() {
        return severity;
    }

    /** Returns true if this level is at least as severe as {@code other}. */
    public boolean isAtLeast(LogLevel other) {
        return this.severity >= other.severity;
    }
}
