package com.systemdesign.observability.model;

// Wiring: Alert is a concrete instance fired by an AlertRule when the threshold is breached.
// Created by AlertEvaluationEngine -> persisted in AlertRepository -> routed by NotificationService.

import java.time.Instant;
import java.util.UUID;

/**
 * A fired alert instance linked to its originating {@link AlertRule}.
 */
public class Alert {

    private final String id;
    private final AlertRule rule;
    private AlertStatus status;
    private final Instant triggeredAt;
    private Instant resolvedAt;           // null until resolved
    private final double currentValue;
    private final String message;

    public Alert(AlertRule rule, double currentValue, String message) {
        this.id = UUID.randomUUID().toString();
        this.rule = rule;
        this.status = AlertStatus.FIRING;
        this.triggeredAt = Instant.now();
        this.currentValue = currentValue;
        this.message = message;
    }

    // ---- lifecycle ----

    /** Resolves the alert — sets status to RESOLVED and stamps the resolution time. */
    public void resolve() {
        this.status = AlertStatus.RESOLVED;
        this.resolvedAt = Instant.now();
    }

    /** Acknowledges the alert — an operator has seen it but it is not yet resolved. */
    public void acknowledge() {
        this.status = AlertStatus.ACKNOWLEDGED;
    }

    // ---- getters ----

    public String getId() { return id; }
    public AlertRule getRule() { return rule; }
    public AlertStatus getStatus() { return status; }
    public Instant getTriggeredAt() { return triggeredAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public double getCurrentValue() { return currentValue; }
    public String getMessage() { return message; }

    @Override
    public String toString() {
        return "Alert{id='" + id + "', rule='" + rule.getName()
                + "', status=" + status + ", value=" + currentValue + "}";
    }
}
