package com.systemdesign.ridesharing.model;

/**
 * RideStatus — State machine states for a ride's lifecycle.
 *
 * Valid transitions (enforced in Ride.java):
 *   REQUESTED  --> MATCHED         (driver found)
 *   REQUESTED  --> CANCELLED       (rider cancels before match)
 *   MATCHED    --> DRIVER_EN_ROUTE (driver confirmed, heading to pickup)
 *   MATCHED    --> CANCELLED       (rider or driver cancels)
 *   DRIVER_EN_ROUTE --> IN_PROGRESS (rider picked up, ride starts)
 *   DRIVER_EN_ROUTE --> CANCELLED   (cancel before pickup)
 *   IN_PROGRESS     --> COMPLETED   (ride finished)
 *   IN_PROGRESS     --> CANCELLED   (emergency cancel — rare)
 *
 * WHY a state machine matters:
 *   Without guards, you could "complete" a ride that was never started,
 *   or "start" a ride that was already cancelled. In production, invalid
 *   state transitions cause billing errors, stuck rides, and angry users.
 */
public enum RideStatus {
    REQUESTED,
    MATCHED,
    DRIVER_EN_ROUTE,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED
}
