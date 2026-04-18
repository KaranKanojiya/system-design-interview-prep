package com.systemdesign.parking.exception;

/**
 * Thrown when no compatible spot is available for the vehicle type.
 */
public class SpotNotAvailableException extends ParkingException {

    public SpotNotAvailableException(String message) {
        super(message);
    }
}
