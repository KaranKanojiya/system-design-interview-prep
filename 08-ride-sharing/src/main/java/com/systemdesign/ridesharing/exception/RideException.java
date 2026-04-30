package com.systemdesign.ridesharing.exception;

/**
 * RideException — Base exception for all ride-sharing domain errors.
 *
 * WHY a hierarchy:
 *   Different errors need different handling:
 *   - NoDriverAvailableException: retry with expanded radius, show "checking nearby areas"
 *   - InvalidRideStateException: programming bug, log and alert
 *   - PaymentFailedException: retry payment, fall back to cash
 *
 *   Having a common base class lets the controller catch all domain errors
 *   in one block while still allowing specific handling when needed.
 */
public class RideException extends RuntimeException {

    public RideException(String message) {
        super(message);
    }

    public RideException(String message, Throwable cause) {
        super(message, cause);
    }
}
