package com.systemdesign.ridesharing.exception;

/**
 * NoDriverAvailableException — Thrown when no driver can be matched to a ride request.
 *
 * This happens when:
 *   - No drivers are in the search radius
 *   - All nearby drivers are already on rides (busy)
 *   - No drivers match the requested vehicle type
 *   - All matched drivers timed out (didn't accept within 15 seconds)
 *
 * In production Uber:
 *   This doesn't immediately fail the request. Instead:
 *   1. Expand the search radius (5km -> 8km -> 12km)
 *   2. Wait and retry (drivers may become available)
 *   3. Suggest alternative vehicle types ("No SUVs, try Sedan?")
 *   4. After N retries, show "No drivers available, try again later"
 */
public class NoDriverAvailableException extends RideException {

    public NoDriverAvailableException(String message) {
        super(message);
    }

    public NoDriverAvailableException(String riderId, String reason) {
        super(String.format("No driver available for rider '%s': %s", riderId, reason));
    }
}
