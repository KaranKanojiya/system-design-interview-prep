package com.systemdesign.ridesharing.display;

import com.systemdesign.ridesharing.model.Driver;
import com.systemdesign.ridesharing.model.Ride;
import com.systemdesign.ridesharing.model.RideStatus;
import com.systemdesign.ridesharing.model.SurgeZone;
import com.systemdesign.ridesharing.service.RideService;

import java.util.List;

/**
 * RideStatsDisplay — Formatted statistics output for the ride-sharing system.
 *
 * Displays:
 *   - Total rides by status (completed, cancelled, in-progress)
 *   - Average fare and average rating
 *   - Surge statistics across zones
 *   - Per-driver earnings breakdown
 *
 * WHY a separate display class:
 *   Separating presentation from business logic. The service layer shouldn't
 *   know or care about console formatting. This follows MVC — the display
 *   reads data from the service and formats it for human consumption.
 */
public class RideStatsDisplay {

    private static final String SEPARATOR = "=".repeat(70);
    private static final String THIN_SEP = "-".repeat(70);

    private final RideService rideService;

    public RideStatsDisplay(RideService rideService) {
        this.rideService = rideService;
    }

    /**
     * Print comprehensive ride statistics.
     */
    public void printStats() {
        System.out.println(SEPARATOR);
        System.out.println("  RIDE STATISTICS");
        System.out.println(SEPARATOR);

        List<Ride> allRides = rideService.getRideRepository().findAll();

        // --- Ride counts by status ---
        long total = allRides.size();
        long completed = allRides.stream().filter(r -> r.getStatus() == RideStatus.COMPLETED).count();
        long cancelled = allRides.stream().filter(r -> r.getStatus() == RideStatus.CANCELLED).count();
        long inProgress = allRides.stream().filter(r -> r.getStatus() == RideStatus.IN_PROGRESS).count();
        long matched = allRides.stream().filter(r -> r.getStatus() == RideStatus.MATCHED
                || r.getStatus() == RideStatus.DRIVER_EN_ROUTE).count();

        System.out.printf("  Total Rides:       %d%n", total);
        System.out.printf("  Completed:         %d%n", completed);
        System.out.printf("  Cancelled:         %d%n", cancelled);
        System.out.printf("  In Progress:       %d%n", inProgress);
        System.out.printf("  Matched/En Route:  %d%n", matched);
        System.out.println(THIN_SEP);

        // --- Average fare ---
        double avgFare = allRides.stream()
                .filter(r -> r.getStatus() == RideStatus.COMPLETED)
                .mapToDouble(Ride::getActualFare)
                .average()
                .orElse(0.0);
        double totalRevenue = allRides.stream()
                .filter(r -> r.getStatus() == RideStatus.COMPLETED)
                .mapToDouble(Ride::getActualFare)
                .sum();

        System.out.printf("  Average Fare:      $%.2f%n", avgFare);
        System.out.printf("  Total Revenue:     $%.2f%n", totalRevenue);
        System.out.println(THIN_SEP);

        // --- Average distance ---
        double avgDistance = allRides.stream()
                .filter(r -> r.getStatus() == RideStatus.COMPLETED)
                .mapToDouble(Ride::getDistance)
                .average()
                .orElse(0.0);
        System.out.printf("  Avg Distance:      %.2f km%n", avgDistance);
        System.out.println(THIN_SEP);

        // --- Surge statistics ---
        printSurgeStats();
        System.out.println(THIN_SEP);

        // --- Driver earnings ---
        printDriverEarnings();
        System.out.println(SEPARATOR);
    }

    /**
     * Print surge zone statistics.
     */
    public void printSurgeStats() {
        List<SurgeZone> zones = rideService.getSurgeService().getZones();
        if (zones.isEmpty()) {
            System.out.println("  No surge zones configured.");
            return;
        }

        long surging = zones.stream().filter(z -> z.getSurgeMultiplier() > 1.0).count();
        double maxSurge = zones.stream().mapToDouble(SurgeZone::getSurgeMultiplier).max().orElse(1.0);
        double avgSurge = zones.stream().mapToDouble(SurgeZone::getSurgeMultiplier).average().orElse(1.0);

        System.out.printf("  Surge Zones:       %d total, %d currently surging%n", zones.size(), surging);
        System.out.printf("  Max Surge:         %.2fx%n", maxSurge);
        System.out.printf("  Avg Surge:         %.2fx%n", avgSurge);
    }

    /**
     * Print per-driver earnings breakdown.
     */
    public void printDriverEarnings() {
        List<Driver> drivers = rideService.getDriverRepository().findAll();
        if (drivers.isEmpty()) {
            System.out.println("  No drivers registered.");
            return;
        }

        System.out.println("  Driver Earnings:");
        System.out.printf("  %-15s %-8s %-10s %-10s%n", "Name", "Rides", "Earnings", "Rating");
        System.out.printf("  %-15s %-8s %-10s %-10s%n", "----", "-----", "--------", "------");

        drivers.stream()
                .filter(d -> d.getTotalRides() > 0)
                .sorted((d1, d2) -> Double.compare(d2.getEarnings(), d1.getEarnings()))
                .forEach(d -> System.out.printf("  %-15s %-8d $%-9.2f %-10.1f%n",
                        d.getName(), d.getTotalRides(), d.getEarnings(), d.getRating()));

        double totalEarnings = drivers.stream().mapToDouble(Driver::getEarnings).sum();
        double avgRating = drivers.stream()
                .filter(d -> d.getTotalRides() > 0)
                .mapToDouble(Driver::getRating)
                .average()
                .orElse(0.0);
        System.out.printf("  %-15s %-8s $%-9.2f %-10.1f%n", "TOTAL/AVG", "",
                totalEarnings, avgRating);
    }
}
