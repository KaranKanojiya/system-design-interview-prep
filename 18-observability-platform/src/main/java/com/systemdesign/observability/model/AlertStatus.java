package com.systemdesign.observability.model;

/**
 * Lifecycle states of an alert instance.
 */
public enum AlertStatus {
    PENDING,
    FIRING,
    ACKNOWLEDGED,
    RESOLVED
}
