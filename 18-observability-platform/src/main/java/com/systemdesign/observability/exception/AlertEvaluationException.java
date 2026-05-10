package com.systemdesign.observability.exception;

// Wiring: Thrown when alert rule evaluation fails (missing metric data, invalid condition).
// Caught by AlertService -> logged and surfaced to the caller.

/**
 * Thrown when an alert rule cannot be evaluated against current metric data.
 */
public class AlertEvaluationException extends ObservabilityException {

    private final String ruleName;

    public AlertEvaluationException(String ruleName, String message) {
        super("Alert evaluation failed for rule '" + ruleName + "': " + message);
        this.ruleName = ruleName;
    }

    public String getRuleName() {
        return ruleName;
    }
}
