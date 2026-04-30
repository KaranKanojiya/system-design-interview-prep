package com.systemdesign.ridesharing.controller;

import com.systemdesign.ridesharing.exception.RideException;
import com.systemdesign.ridesharing.model.Location;
import com.systemdesign.ridesharing.model.Ride;
import com.systemdesign.ridesharing.model.Vehicle;
import com.systemdesign.ridesharing.service.RideService;

import java.util.Optional;

/**
 * RideController — Simulated REST controller for ride operations.
 *
 * In production, this would be a Spring @RestController or JAX-RS resource
 * with actual HTTP endpoints. Here we simulate the controller layer to
 * show the full architecture (controller -> service -> repository).
 *
 * Endpoints (simulated):
 *   POST /rides              -> handleRequestRide()
 *   POST /rides/{id}/start   -> handleStartRide()
 *   POST /rides/{id}/complete -> handleCompleteRide()
 *   POST /rides/{id}/cancel  -> handleCancelRide()
 *   GET  /rides/{id}         -> handleGetRide()
 *   GET  /surge?lat=X&lng=Y  -> handleGetSurgePrice()
 *
 * Each method wraps the service call in try-catch to simulate HTTP error responses.
 */
public class RideController {

    private final RideService rideService;

    public RideController(RideService rideService) {
        this.rideService = rideService;
    }

    /**
     * POST /rides — Request a new ride.
     *
     * Simulated request:  { riderId, pickup, dropoff, vehicleType }
     * Simulated response: { ride } or { error: "No driver available" }
     */
    public Ride handleRequestRide(String riderId, Location pickup, Location dropoff,
                                  Vehicle.VehicleType vehicleType) {
        System.out.printf("  [Controller] POST /rides (rider=%s, type=%s)%n", riderId, vehicleType);
        try {
            Ride ride = rideService.requestRide(riderId, pickup, dropoff, vehicleType);
            System.out.printf("  [Controller] 201 CREATED - Ride %s%n", ride.getRideId());
            return ride;
        } catch (RideException e) {
            System.out.printf("  [Controller] 400 BAD REQUEST - %s%n", e.getMessage());
            throw e;
        }
    }

    /**
     * POST /rides/{id}/start — Start a ride (rider picked up).
     */
    public Ride handleStartRide(String rideId) {
        System.out.printf("  [Controller] POST /rides/%s/start%n", rideId);
        try {
            Ride ride = rideService.startRide(rideId);
            System.out.printf("  [Controller] 200 OK - Ride started%n");
            return ride;
        } catch (RideException e) {
            System.out.printf("  [Controller] 400 BAD REQUEST - %s%n", e.getMessage());
            throw e;
        }
    }

    /**
     * POST /rides/{id}/complete — Complete a ride.
     */
    public Ride handleCompleteRide(String rideId) {
        System.out.printf("  [Controller] POST /rides/%s/complete%n", rideId);
        try {
            Ride ride = rideService.completeRide(rideId);
            System.out.printf("  [Controller] 200 OK - Ride completed, fare=$%.2f%n", ride.getActualFare());
            return ride;
        } catch (RideException e) {
            System.out.printf("  [Controller] 400 BAD REQUEST - %s%n", e.getMessage());
            throw e;
        }
    }

    /**
     * POST /rides/{id}/cancel — Cancel a ride.
     */
    public Ride handleCancelRide(String rideId) {
        System.out.printf("  [Controller] POST /rides/%s/cancel%n", rideId);
        try {
            Ride ride = rideService.cancelRide(rideId);
            System.out.printf("  [Controller] 200 OK - Ride cancelled%n");
            return ride;
        } catch (RideException e) {
            System.out.printf("  [Controller] 400 BAD REQUEST - %s%n", e.getMessage());
            throw e;
        }
    }

    /**
     * GET /rides/{id} — Get ride details.
     */
    public Optional<Ride> handleGetRide(String rideId) {
        System.out.printf("  [Controller] GET /rides/%s%n", rideId);
        Optional<Ride> ride = rideService.getRide(rideId);
        if (ride.isPresent()) {
            System.out.printf("  [Controller] 200 OK - %s%n", ride.get());
        } else {
            System.out.printf("  [Controller] 404 NOT FOUND%n");
        }
        return ride;
    }

    /**
     * GET /surge?lat=X&lng=Y — Get surge multiplier at a location.
     */
    public double handleGetSurgePrice(double lat, double lng) {
        System.out.printf("  [Controller] GET /surge?lat=%.4f&lng=%.4f%n", lat, lng);
        Location location = new Location(lat, lng);
        double surge = rideService.getSurgeService().getSurgeMultiplier(location);
        System.out.printf("  [Controller] 200 OK - Surge: %.2fx%n", surge);
        return surge;
    }

    /** Get the underlying ride service (for demo access). */
    public RideService getRideService() {
        return rideService;
    }
}
