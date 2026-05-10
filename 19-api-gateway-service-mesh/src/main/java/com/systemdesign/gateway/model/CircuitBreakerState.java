package com.systemdesign.gateway.model;

import java.time.Duration;
import java.time.Instant;

/**
 * Mutable state for a single circuit breaker protecting calls to one service.
 *
 * State machine:
 *   1. CLOSED  — requests pass, failures are counted
 *   2. OPEN    — requests are rejected; after openDurationMs, transitions to HALF_OPEN
 *   3. HALF_OPEN — a limited number of probe requests pass; successes → CLOSED, failures → OPEN
 */
public class CircuitBreakerState {

    private final String serviceName;           // service this breaker protects
    private CircuitState state;                 // current circuit state
    private int failureCount;                   // consecutive failures in CLOSED state
    private int successCount;                   // consecutive successes in HALF_OPEN state
    private Instant lastFailureTime;            // timestamp of the most recent failure
    private Instant lastStateChange;            // timestamp of the last state transition
    private final int failureThreshold;         // failures needed to trip (CLOSED → OPEN)
    private final int successThreshold;         // successes needed to recover (HALF_OPEN → CLOSED)
    private final long openDurationMs;          // how long to stay OPEN before probing

    public CircuitBreakerState(String serviceName) {
        this(serviceName, 5, 3, 30_000L);
    }

    public CircuitBreakerState(String serviceName, int failureThreshold, int successThreshold, long openDurationMs) {
        this.serviceName = serviceName;
        this.state = CircuitState.CLOSED;
        this.failureCount = 0;
        this.successCount = 0;
        this.lastFailureTime = null;
        this.lastStateChange = Instant.now();
        this.failureThreshold = failureThreshold;
        this.successThreshold = successThreshold;
        this.openDurationMs = openDurationMs;
    }

    // ── Getters ──

    public String getServiceName() { return serviceName; }
    public CircuitState getState() { return state; }
    public int getFailureCount() { return failureCount; }
    public int getSuccessCount() { return successCount; }
    public Instant getLastFailureTime() { return lastFailureTime; }
    public Instant getLastStateChange() { return lastStateChange; }
    public int getFailureThreshold() { return failureThreshold; }
    public int getSuccessThreshold() { return successThreshold; }
    public long getOpenDurationMs() { return openDurationMs; }

    // ── State transition methods ──

    /**
     * Records a successful request.
     * In HALF_OPEN: increments successCount; if threshold met → CLOSED.
     * In CLOSED: resets failure count.
     */
    public void recordSuccess() {
        switch (state) {
            case HALF_OPEN -> {
                successCount++;
                if (successCount >= successThreshold) {
                    reset();
                }
            }
            case CLOSED -> failureCount = 0;
            case OPEN -> { /* ignored while open */ }
        }
    }

    /**
     * Records a failed request.
     * In CLOSED: increments failureCount; if threshold met → trip().
     * In HALF_OPEN: immediately trips back to OPEN.
     */
    public void recordFailure() {
        lastFailureTime = Instant.now();
        switch (state) {
            case CLOSED -> {
                failureCount++;
                if (shouldTrip()) {
                    trip();
                }
            }
            case HALF_OPEN -> trip();
            case OPEN -> { /* already open */ }
        }
    }

    /** Returns true if failure count has reached the threshold. */
    public boolean shouldTrip() {
        return failureCount >= failureThreshold;
    }

    /**
     * Returns true if the circuit is OPEN and enough time has elapsed to attempt a probe.
     * This signals the breaker should transition to HALF_OPEN.
     */
    public boolean shouldAttemptReset() {
        if (state != CircuitState.OPEN) {
            return false;
        }
        long elapsed = Duration.between(lastStateChange, Instant.now()).toMillis();
        return elapsed > openDurationMs;
    }

    /** Resets the breaker to CLOSED — normal operation. */
    public void reset() {
        this.state = CircuitState.CLOSED;
        this.failureCount = 0;
        this.successCount = 0;
        this.lastStateChange = Instant.now();
    }

    /** Trips the breaker to OPEN — all requests will be rejected. */
    public void trip() {
        this.state = CircuitState.OPEN;
        this.successCount = 0;
        this.lastStateChange = Instant.now();
    }

    /** Transitions from OPEN to HALF_OPEN for probe requests. */
    public void halfOpen() {
        this.state = CircuitState.HALF_OPEN;
        this.successCount = 0;
        this.failureCount = 0;
        this.lastStateChange = Instant.now();
    }

    @Override
    public String toString() {
        return "CircuitBreakerState{service='%s', state=%s, failures=%d, successes=%d}".formatted(
                serviceName, state, failureCount, successCount);
    }
}
