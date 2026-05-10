package com.systemdesign.gateway.service;

import com.systemdesign.gateway.engine.CircuitBreakerEngine;
import com.systemdesign.gateway.model.CircuitState;

import com.systemdesign.gateway.model.CircuitBreakerState;

import java.util.Map;
import java.util.Objects;

/**
 * Circuit breaker management — protects upstream services from cascading failures.
 *
 * Flow: request → allowRequest() checks circuit state → on response → recordSuccess/recordFailure
 *
 * State machine: CLOSED → (failures exceed threshold) → OPEN → (timeout) → HALF_OPEN → CLOSED or OPEN
 */
public class CircuitBreakerService {

    private final CircuitBreakerEngine engine; // wiring: circuit breaker state machine engine

    public CircuitBreakerService(CircuitBreakerEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
    }

    /**
     * Checks whether a request to the specified service should be allowed.
     * Returns false if the circuit is OPEN (tripped).
     *
     * @param serviceName the target service name
     * @return true if the request is allowed, false if the circuit is open
     */
    public boolean allowRequest(String serviceName) {
        boolean allowed = engine.allowRequest(serviceName);
        CircuitState state = engine.getState(serviceName);
        System.out.println("[CIRCUIT BREAKER] Service '%s' — state=%s, allowed=%s".formatted(
                serviceName, state, allowed));
        return allowed;
    }

    /**
     * Records a successful response from the service, potentially closing a half-open circuit.
     *
     * @param serviceName the service that responded successfully
     */
    public void recordSuccess(String serviceName) {
        engine.recordSuccess(serviceName);
        System.out.println("[CIRCUIT BREAKER] Service '%s' — recorded SUCCESS (state=%s)".formatted(
                serviceName, engine.getState(serviceName)));
    }

    /**
     * Records a failed response from the service, potentially tripping the circuit.
     *
     * @param serviceName the service that failed
     */
    public void recordFailure(String serviceName) {
        engine.recordFailure(serviceName);
        System.out.println("[CIRCUIT BREAKER] Service '%s' — recorded FAILURE (state=%s)".formatted(
                serviceName, engine.getState(serviceName)));
    }

    /**
     * Returns the current circuit state for a service.
     *
     * @param serviceName the service to check
     * @return the current circuit state
     */
    public CircuitState getState(String serviceName) {
        return engine.getState(serviceName);
    }

    /**
     * Returns a summary of all circuit breaker states.
     *
     * @return map of service name to circuit state
     */
    public Map<String, CircuitState> getCircuitSummary() {
        Map<String, CircuitState> summary = new java.util.HashMap<>();
        engine.getAllBreakers().forEach((name, breaker) -> summary.put(name, breaker.getState()));
        System.out.println("[CIRCUIT BREAKER] Summary: %d services tracked".formatted(summary.size()));
        summary.forEach((service, state) ->
                System.out.println("[CIRCUIT BREAKER]   %s → %s".formatted(service, state)));
        return summary;
    }

    /**
     * Returns a snapshot of all circuit breaker states (full objects).
     *
     * @return map of service name to CircuitBreakerState
     */
    public Map<String, CircuitBreakerState> getAllCircuitBreakerStates() {
        return engine.getAllBreakers();
    }

    /**
     * Manually trips the circuit breaker for a service (force OPEN).
     *
     * @param serviceName the service to trip
     */
    public void forceOpen(String serviceName) {
        engine.getOrCreate(serviceName).trip();
        System.out.println("[CIRCUIT BREAKER] Service '%s' — manually OPENED (tripped)".formatted(serviceName));
    }

    /**
     * Manually resets the circuit breaker for a service (force CLOSED).
     *
     * @param serviceName the service to reset
     */
    public void forceClose(String serviceName) {
        engine.getOrCreate(serviceName).reset();
        System.out.println("[CIRCUIT BREAKER] Service '%s' — manually CLOSED (reset)".formatted(serviceName));
    }
}
