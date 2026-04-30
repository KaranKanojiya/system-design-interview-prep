package com.systemdesign.ridesharing.strategy.matching;

import com.systemdesign.ridesharing.model.Driver;
import com.systemdesign.ridesharing.model.Location;
import com.systemdesign.ridesharing.model.RideRequest;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * ETABasedStrategy — Picks the driver with the lowest estimated time of arrival (ETA).
 *
 * WHY ETA is better than raw distance:
 *   Driver A is 2 km away but stuck in highway traffic (ETA: 15 min).
 *   Driver B is 3 km away on an open road (ETA: 5 min).
 *   Nearest-driver picks A. ETA-based picks B. The rider gets picked up 10 min sooner.
 *
 * HOW we simulate traffic:
 *   We use a time-of-day factor to simulate realistic traffic patterns.
 *   In production, Uber uses real-time traffic data from:
 *   - Their own driver GPS traces (millions of data points per minute)
 *   - Third-party APIs (Google Maps, TomTom)
 *   - Historical patterns (rush hour on Mondays is predictable)
 *
 * Traffic factor:
 *   Rush hour (7-9 AM, 5-7 PM): 0.5x speed (traffic moves at half normal speed)
 *   Normal hours:                1.0x speed
 *   Late night (11 PM - 5 AM):  1.2x speed (empty roads, slightly faster)
 *
 * ETA formula:
 *   ETA = distance_km / (average_speed_kmh * traffic_factor)
 *   average_speed in city: 30 km/h
 *   With rush hour factor: 30 * 0.5 = 15 km/h effective speed
 */
public class ETABasedStrategy implements MatchingStrategy {

    /** Average city driving speed in km/h (without traffic adjustment). */
    private static final double AVERAGE_SPEED_KMH = 30.0;

    @Override
    public Optional<Driver> findBestDriver(RideRequest request, List<Driver> availableDrivers, Location pickup) {
        double trafficFactor = getTrafficFactor();

        return availableDrivers.stream()
                .filter(Driver::isAvailable)
                .filter(d -> d.getVehicle().getType() == request.getVehicleType())
                .min((d1, d2) -> {
                    double eta1 = calculateETA(d1.getCurrentLocation(), pickup, trafficFactor);
                    double eta2 = calculateETA(d2.getCurrentLocation(), pickup, trafficFactor);
                    return Double.compare(eta1, eta2);
                });
    }

    /**
     * Calculate ETA in minutes from driver's location to pickup.
     *
     * ETA = (distance_km / effective_speed_kmh) * 60 minutes
     *
     * In production, this would call a routing service (OSRM, Google Directions)
     * that accounts for actual road network, one-way streets, turn restrictions, etc.
     * Here we use straight-line Haversine * 1.3 (road detour factor) for realism.
     */
    public double calculateETA(Location from, Location to, double trafficFactor) {
        // Haversine gives straight-line distance; real roads are ~30% longer on average
        double straightLineKm = Location.distanceKm(from, to);
        double roadDistanceKm = straightLineKm * 1.3;  // detour factor

        // Effective speed = base speed * traffic factor
        double effectiveSpeed = AVERAGE_SPEED_KMH * trafficFactor;

        // ETA in minutes
        return (roadDistanceKm / effectiveSpeed) * 60.0;
    }

    /**
     * Get traffic factor based on current time of day.
     *
     * WHY simulate this:
     *   In an interview, showing awareness of real-world factors (traffic, time of day,
     *   events) demonstrates production-level thinking. The interviewer doesn't expect
     *   a real traffic API, but they do expect you to mention it matters.
     */
    public double getTrafficFactor() {
        int hour = LocalTime.now().getHour();

        if ((hour >= 7 && hour <= 9) || (hour >= 17 && hour <= 19)) {
            // Rush hour — traffic crawls at half speed
            return 0.5;
        } else if (hour >= 23 || hour <= 5) {
            // Late night — roads are empty, slightly faster than normal
            return 1.2;
        } else {
            // Normal hours
            return 1.0;
        }
    }

    /**
     * Overloaded for testing: pass a specific traffic factor.
     */
    public double calculateETAWithFactor(Location from, Location to, double trafficFactor) {
        return calculateETA(from, to, trafficFactor);
    }

    @Override
    public String toString() {
        return "ETABasedStrategy";
    }
}
