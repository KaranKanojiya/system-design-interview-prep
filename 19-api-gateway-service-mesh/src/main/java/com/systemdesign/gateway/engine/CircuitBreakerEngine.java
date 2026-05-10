package com.systemdesign.gateway.engine;

// Wiring: CircuitBreakerEngine manages per-service circuit breakers.
// Used by GatewayService -> before forwarding to upstream -> checks if circuit is open.
// State machine: CLOSED -> OPEN (on failure threshold) -> HALF_OPEN (on timeout) -> CLOSED (on success threshold).

import com.systemdesign.gateway.model.CircuitBreakerState;
import com.systemdesign.gateway.model.CircuitState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages per-service circuit breakers using the standard three-state model:
 * CLOSED (healthy), OPEN (failing), HALF_OPEN (testing recovery).
 */
public class CircuitBreakerEngine {

    // Per-service circuit breaker state
    private final Map<String, CircuitBreakerState> breakers = new ConcurrentHashMap<>();

    // Defaults for new breakers
    private static final int DEFAULT_FAILURE_THRESHOLD = 5;
    private static final int DEFAULT_SUCCESS_THRESHOLD = 3;
    private static final long DEFAULT_OPEN_DURATION_MS = 30_000;

    /** Lazily creates a CircuitBreakerState with defaults for the given service. */
    public CircuitBreakerState getOrCreate(String serviceName) {
        return breakers.computeIfAbsent(serviceName, name ->
                new CircuitBreakerState(name, DEFAULT_FAILURE_THRESHOLD,
                        DEFAULT_SUCCESS_THRESHOLD, DEFAULT_OPEN_DURATION_MS));
    }

    /**
     * Checks if a request to the given service should be allowed.
     * CLOSED: always allow.
     * OPEN: if cooldown elapsed, transition to HALF_OPEN and allow; else deny.
     * HALF_OPEN: allow (testing recovery).
     */
    public boolean allowRequest(String serviceName) {
        CircuitBreakerState breaker = getOrCreate(serviceName);
        CircuitState state = breaker.getState();

        switch (state) {
            case CLOSED -> {
                return true;
            }
            case OPEN -> {
                if (breaker.shouldAttemptReset()) {
                    breaker.halfOpen();
                    System.out.println("[CIRCUIT BREAKER] " + serviceName
                            + ": OPEN -> HALF_OPEN (attempting reset)");
                    return true;
                }
                return false;
            }
            case HALF_OPEN -> {
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Records a successful call to the service.
     * If HALF_OPEN and success count reaches threshold, transitions to CLOSED.
     */
    public void recordSuccess(String serviceName) {
        CircuitBreakerState breaker = getOrCreate(serviceName);
        CircuitState prevState = breaker.getState();
        breaker.recordSuccess();

        if (prevState == CircuitState.HALF_OPEN && breaker.getState() == CircuitState.CLOSED) {
            System.out.println("[CIRCUIT BREAKER] " + serviceName
                    + ": HALF_OPEN -> CLOSED (recovery confirmed)");
        }
    }

    /**
     * Records a failed call to the service.
     * If failure count reaches threshold, trips to OPEN.
     * If HALF_OPEN, trips back to OPEN immediately.
     */
    public void recordFailure(String serviceName) {
        CircuitBreakerState breaker = getOrCreate(serviceName);
        CircuitState prevState = breaker.getState();
        breaker.recordFailure();

        if (prevState == CircuitState.HALF_OPEN && breaker.getState() == CircuitState.OPEN) {
            System.out.println("[CIRCUIT BREAKER] " + serviceName
                    + ": HALF_OPEN -> OPEN (recovery failed)");
        } else if (prevState == CircuitState.CLOSED && breaker.getState() == CircuitState.OPEN) {
            System.out.println("[CIRCUIT BREAKER] " + serviceName
                    + ": CLOSED -> OPEN (failure threshold reached: "
                    + breaker.getFailureCount() + ")");
        }
    }

    /** Returns the current circuit state for a service. */
    public CircuitState getState(String serviceName) {
        return getOrCreate(serviceName).getState();
    }

    /** Returns a snapshot of all circuit breaker states. */
    public Map<String, CircuitBreakerState> getAllBreakers() {
        return Map.copyOf(breakers);
    }
}
