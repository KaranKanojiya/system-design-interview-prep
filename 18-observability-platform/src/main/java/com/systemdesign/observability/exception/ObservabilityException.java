package com.systemdesign.observability.exception;

// Wiring: Base exception for all observability platform errors.
// Subclassed by MetricIngestionException, TraceAssemblyException, AlertEvaluationException.

/**
 * Base unchecked exception for the observability platform.
 * All domain-specific exceptions extend this class.
 */
public class ObservabilityException extends RuntimeException {

    public ObservabilityException(String message) {
        super(message);
    }

    public ObservabilityException(String message, Throwable cause) {
        super(message, cause);
    }
}
