package com.systemdesign.observability.model;

/**
 * Status of a distributed tracing span.
 */
public enum SpanStatus {
    OK,
    ERROR,
    TIMEOUT,
    CANCELLED
}
