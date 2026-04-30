package com.systemdesign.ridesharing.exception;

/**
 * InvalidRideStateException — Thrown when a ride state transition is invalid.
 *
 * Examples:
 *   - Trying to start a ride that hasn't been matched yet
 *   - Trying to complete a ride that's already cancelled
 *   - Trying to match a driver to an already-matched ride
 *
 * This is always a programming bug or a race condition.
 * In production, this would trigger an alert for the on-call engineer.
 */
public class InvalidRideStateException extends RideException {

    public InvalidRideStateException(String message) {
        super(message);
    }
}
