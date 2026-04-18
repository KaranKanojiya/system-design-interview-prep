package com.systemdesign.parking.exception;

/**
 * Thrown when the parking lot has no available spots for the requested vehicle type.
 */
public class ParkingFullException extends ParkingException {

    public ParkingFullException(String message) {
        super(message);
    }
}
