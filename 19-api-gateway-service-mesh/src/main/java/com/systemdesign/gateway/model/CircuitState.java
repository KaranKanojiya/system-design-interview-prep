package com.systemdesign.gateway.model;

/**
 * States of a circuit breaker in the service mesh.
 *
 * State machine: CLOSED → (failures exceed threshold) → OPEN → (timeout expires) → HALF_OPEN
 *                HALF_OPEN → (success) → CLOSED
 *                HALF_OPEN → (failure) → OPEN
 */
public enum CircuitState {

    CLOSED("Normal operation — requests flow through"),
    OPEN("Circuit tripped — requests are rejected immediately"),
    HALF_OPEN("Testing recovery — limited requests allowed through");

    private final String description;

    CircuitState(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
