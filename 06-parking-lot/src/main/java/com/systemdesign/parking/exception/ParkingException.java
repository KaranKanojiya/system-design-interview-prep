package com.systemdesign.parking.exception;

/**
 * Base exception for all parking system errors.
 * Concrete exception subclasses represent specific failure scenarios.
 *
 * Demonstrates:
 * - Exception hierarchy mirroring the domain model
 * - Open/Closed Principle: new exception types extend without modifying base
 */
public class ParkingException extends RuntimeException {

    public ParkingException(String message) {
        super(message);
    }

    public ParkingException(String message, Throwable cause) {
        super(message, cause);
    }
}
