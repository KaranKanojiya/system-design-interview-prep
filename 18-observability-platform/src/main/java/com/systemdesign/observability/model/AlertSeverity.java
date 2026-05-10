package com.systemdesign.observability.model;

/**
 * Severity levels for alert rules, ordered from informational to page-worthy.
 */
public enum AlertSeverity {
    INFO(0),
    WARNING(1),
    CRITICAL(2),
    PAGE(3);

    private final int level;

    AlertSeverity(int level) {
        this.level = level;
    }

    public int getLevel() {
        return level;
    }
}
