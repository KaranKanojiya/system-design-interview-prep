package com.systemdesign.ridesharing;

import com.systemdesign.ridesharing.config.AppConfig;
import com.systemdesign.ridesharing.exception.InvalidRideStateException;
import com.systemdesign.ridesharing.exception.NoDriverAvailableException;
import com.systemdesign.ridesharing.model.*;
import com.systemdesign.ridesharing.service.*;
import com.systemdesign.ridesharing.spatial.BoundingBox;
import com.systemdesign.ridesharing.spatial.GeoHash;
import com.systemdesign.ridesharing.spatial.QuadTree;
import com.systemdesign.ridesharing.strategy.matching.ETABasedStrategy;
import com.systemdesign.ridesharing.strategy.matching.NearestDriverStrategy;
import com.systemdesign.ridesharing.strategy.pricing.StandardPricingStrategy;
import com.systemdesign.ridesharing.strategy.pricing.SurgePricingStrategy;

import java.util.*;
import java.util.concurrent.*;

/**
 * RideSharingApp — Main demo application showcasing the entire ride-sharing system.
 *
 * Demos:
 *   1. Basic Ride Flow (request -> match -> en route -> start -> complete -> payment)
 *   2. QuadTree Spatial Indexing (insert 100 drivers, find 5 nearest)
 *   3. Matching Strategy Comparison (nearest vs ETA-based)
 *   4. Surge Pricing in Action (high demand zone with few drivers)
 *   5. Vehicle Type Pricing (same route, different vehicle types)
 *   6. Driver Timeout & Cascade (first driver doesn't accept)
 *   7. Ride Cancellation (cancel at different stages)
 *   8. Concurrent Ride Requests (multiple riders competing for same drivers)
 *   9. GeoHash Encoding (lat/lng -> geohash, prefix matching)
 *
 * Run with: java -cp . com.systemdesign.ridesharing.RideSharingApp
 */
public class RideSharingApp {

    private static final String SEPARATOR = "=".repeat(70);
    private static final String THIN_SEP = "-".repeat(70);

    public static void main(String[] args) {
        System.out.println();
        System.out.println(SEPARATOR);
        System.out.println("       Ride-Sharing System (Uber/Lyft) -- System Design Demo");
        System.out.println(SEPARATOR);
        System.out.println();

        demoBasicRideFlow();
        demoQuadTreeSpatialIndexing();
        demoMatchingStrategyComparison();
        demoSurgePricing();
        demoVehicleTypePricing();
        demoDriverTimeoutCascade();
        demoRideCancellation();
        demoConcurrentRideRequests();
        demoGeoHashEncoding();

        printDesignSummary();
    }

    // ─── Demo 1: Basic Ride Flow ──────────────────────────────────────

    private static void demoBasicRideFlow() {
        printHeader("Demo 1: Basic Ride Flow (Full Lifecycle)");
        System.out.println("  Scenario: Rider requests ride -> driver matched -> pickup -> ride -> complete -> payment");
        System.out.println();

        // Create the full system via AppConfig
        RideService rideService = AppConfig.createRideService();

        // Seed data: 1 rider, 3 drivers near San Francisco
        Rider rider = AppConfig.createSampleRider("R1", "Alice", 37.7749, -122.4194);
        rideService.getRiderRepository().save(rider);

        // Create drivers at various distances from the pickup
        Driver d1 = AppConfig.createSampleDriver("D1", "Bob", 37.7760, -122.4180, Vehicle.VehicleType.SEDAN);
        Driver d2 = AppConfig.createSampleDriver("D2", "Carol", 37.7800, -122.4100, Vehicle.VehicleType.SEDAN);
        Driver d3 = AppConfig.createSampleDriver("D3", "Dave", 37.7700, -122.4250, Vehicle.VehicleType.SEDAN);

        rideService.getDriverRepository().save(d1);
        rideService.getDriverRepository().save(d2);
        rideService.getDriverRepository().save(d3);

        // Register drivers in spatial index
        rideService.getLocationService().addDriver("D1", d1.getCurrentLocation());
        rideService.getLocationService().addDriver("D2", d2.getCurrentLocation());
        rideService.getLocationService().addDriver("D3", d3.getCurrentLocation());

        // Request a ride: Alice from Union Square to Mission District
        Location pickup = new Location(37.7749, -122.4194);
        Location dropoff = new Location(37.7599, -122.4148);

        System.out.printf("  Pickup:  %s (Union Square area)%n", pickup);
        System.out.printf("  Dropoff: %s (Mission District)%n", dropoff);
        System.out.printf("  Distance: %.2f km (Haversine)%n", Location.distanceKm(pickup, dropoff));
        System.out.println();

        try {
            // Step 1: Request ride (triggers matching, estimation, notifications)
            Ride ride = rideService.requestRide("R1", pickup, dropoff, Vehicle.VehicleType.SEDAN);
            System.out.println();
            System.out.printf("  Ride created: %s%n", ride);
            System.out.println(THIN_SEP);

            // Step 2: Start ride (rider picked up)
            System.out.println("  Simulating: Rider picked up...");
            ride = rideService.startRide(ride.getRideId());
            System.out.printf("  Ride status: %s%n", ride.getStatus());
            System.out.println(THIN_SEP);

            // Step 3: Complete ride (triggers fare calculation + payment)
            System.out.println("  Simulating: Ride completed...");
            ride = rideService.completeRide(ride.getRideId());
            System.out.println();
            System.out.printf("  Final ride: %s%n", ride);
            System.out.printf("  Estimated fare: $%.2f | Actual fare: $%.2f%n",
                    ride.getEstimatedFare(), ride.getActualFare());
        } catch (Exception e) {
            System.out.printf("  Error: %s%n", e.getMessage());
        }
        System.out.println();
    }

    // ─── Demo 2: QuadTree Spatial Indexing ─────────────────────────────

    private static void demoQuadTreeSpatialIndexing() {
        printHeader("Demo 2: QuadTree Spatial Indexing");
        System.out.println("  Scenario: Insert 100 drivers, find 5 nearest to a point, show distances");
        System.out.println();

        // Create a QuadTree covering San Francisco
        BoundingBox sfBounds = new BoundingBox(37.70, 37.85, -122.52, -122.35);
        QuadTree quadTree = new QuadTree(sfBounds);

        // Insert 100 drivers with random locations in SF
        Random rng = new Random(42);  // fixed seed for reproducibility
        System.out.println("  Inserting 100 drivers into QuadTree...");
        for (int i = 0; i < 100; i++) {
            double lat = 37.70 + rng.nextDouble() * 0.15;   // 37.70 - 37.85
            double lng = -122.52 + rng.nextDouble() * 0.17;  // -122.52 - -122.35
            quadTree.insert("driver-" + i, new Location(lat, lng));
        }

        System.out.printf("  QuadTree size: %d points%n", quadTree.size());
        System.out.printf("  QuadTree nodes: %d%n", quadTree.countNodes());
        System.out.printf("  QuadTree max depth: %d%n", quadTree.getMaxDepth());
        System.out.println(THIN_SEP);

        // Find 5 nearest drivers to Union Square
        Location searchCenter = new Location(37.7880, -122.4075);
        System.out.printf("  Searching for 5 nearest drivers to %s...%n", searchCenter);
        System.out.println();

        List<Map.Entry<String, Location>> nearest = quadTree.findNearby(searchCenter, 5.0, 5);
        System.out.printf("  %-15s %-30s %-15s%n", "Driver ID", "Location", "Distance (km)");
        System.out.printf("  %-15s %-30s %-15s%n", "---------", "--------", "-------------");

        for (Map.Entry<String, Location> entry : nearest) {
            double dist = Location.distanceKm(searchCenter, entry.getValue());
            System.out.printf("  %-15s %-30s %.4f km%n", entry.getKey(), entry.getValue(), dist);
        }

        // Show that increasing radius finds more drivers
        System.out.println();
        for (double radius : new double[]{1.0, 2.0, 5.0, 10.0}) {
            int count = quadTree.findNearby(searchCenter, radius, 100).size();
            System.out.printf("  Within %.0f km: %d drivers%n", radius, count);
        }
        System.out.println();
    }

    // ─── Demo 3: Matching Strategy Comparison ──────────────────────────

    private static void demoMatchingStrategyComparison() {
        printHeader("Demo 3: Matching Strategy Comparison (Nearest vs ETA-Based)");
        System.out.println("  Scenario: Same drivers, different strategies pick different 'best' driver");
        System.out.println();

        Location pickup = new Location(37.7749, -122.4194);

        // Driver A: 1 km away (but in traffic area)
        Driver driverA = AppConfig.createSampleDriver("DA", "Alice-D", 37.7839, -122.4194,
                Vehicle.VehicleType.SEDAN);
        // Driver B: 2 km away (but on open road)
        Driver driverB = AppConfig.createSampleDriver("DB", "Bob-D", 37.7750, -122.3990,
                Vehicle.VehicleType.SEDAN);
        // Driver C: 0.5 km away
        Driver driverC = AppConfig.createSampleDriver("DC", "Carol-D", 37.7780, -122.4170,
                Vehicle.VehicleType.SEDAN);

        List<Driver> drivers = List.of(driverA, driverB, driverC);
        RideRequest request = new RideRequest("R1", pickup,
                new Location(37.7599, -122.4148), Vehicle.VehicleType.SEDAN, 10.0, 1.0);

        // Show distances
        System.out.println("  Drivers and their distances to pickup:");
        for (Driver d : drivers) {
            double dist = Location.distanceKm(d.getCurrentLocation(), pickup);
            System.out.printf("    %s: %.3f km away, location=%s%n", d.getName(), dist, d.getCurrentLocation());
        }
        System.out.println();

        // Strategy 1: Nearest Driver
        NearestDriverStrategy nearestStrategy = new NearestDriverStrategy();
        Optional<Driver> nearestPick = nearestStrategy.findBestDriver(request, new ArrayList<>(drivers), pickup);
        System.out.printf("  NearestDriverStrategy picks: %s (%.3f km)%n",
                nearestPick.map(Driver::getName).orElse("none"),
                nearestPick.map(d -> Location.distanceKm(d.getCurrentLocation(), pickup)).orElse(0.0));

        // Strategy 2: ETA-Based
        ETABasedStrategy etaStrategy = new ETABasedStrategy();
        Optional<Driver> etaPick = etaStrategy.findBestDriver(request, new ArrayList<>(drivers), pickup);
        System.out.printf("  ETABasedStrategy picks:      %s (ETA: %.1f min)%n",
                etaPick.map(Driver::getName).orElse("none"),
                etaPick.map(d -> etaStrategy.calculateETA(d.getCurrentLocation(), pickup,
                        etaStrategy.getTrafficFactor())).orElse(0.0));

        System.out.printf("  Current traffic factor:      %.1fx (%.0f = %s)%n",
                etaStrategy.getTrafficFactor(),
                (double) java.time.LocalTime.now().getHour(),
                etaStrategy.getTrafficFactor() < 1.0 ? "rush hour" :
                        etaStrategy.getTrafficFactor() > 1.0 ? "late night" : "normal");
        System.out.println();
        System.out.println("  KEY INSIGHT: Nearest driver isn't always the fastest.");
        System.out.println("  ETA-based considers traffic, road distance, and time of day.");
        System.out.println();
    }

    // ─── Demo 4: Surge Pricing in Action ──────────────────────────────

    private static void demoSurgePricing() {
        printHeader("Demo 4: Surge Pricing in Action");
        System.out.println("  Scenario: High demand zone with few drivers -> 2.5x surge");
        System.out.println();

        SurgeService surgeService = new SurgeService();

        // Create a zone with high demand but low supply
        Location downtownSF = new Location(37.7749, -122.4194);
        SurgeZone highDemandZone = new SurgeZone("downtown", downtownSF, 2.0);

        // Scenario 1: Balanced supply/demand
        highDemandZone.setSupplyCount(10);
        highDemandZone.setDemandCount(8);
        double surge1 = highDemandZone.calculateMultiplier();
        System.out.printf("  Scenario 1: Supply=10, Demand=8  -> Surge=%.2fx (balanced)%n", surge1);

        // Scenario 2: Slightly more demand than supply
        highDemandZone.setSupplyCount(10);
        highDemandZone.setDemandCount(12);
        double surge2 = highDemandZone.calculateMultiplier();
        System.out.printf("  Scenario 2: Supply=10, Demand=12 -> Surge=%.2fx (slight imbalance)%n", surge2);

        // Scenario 3: High demand
        highDemandZone.setSupplyCount(5);
        highDemandZone.setDemandCount(12);
        double surge3 = highDemandZone.calculateMultiplier();
        System.out.printf("  Scenario 3: Supply=5,  Demand=12 -> Surge=%.2fx (high demand)%n", surge3);

        // Scenario 4: Extreme demand (concert just ended)
        highDemandZone.setSupplyCount(3);
        highDemandZone.setDemandCount(15);
        double surge4 = highDemandZone.calculateMultiplier();
        System.out.printf("  Scenario 4: Supply=3,  Demand=15 -> Surge=%.2fx (extreme!)%n", surge4);

        // Scenario 5: No supply at all
        highDemandZone.setSupplyCount(0);
        highDemandZone.setDemandCount(10);
        double surge5 = highDemandZone.calculateMultiplier();
        System.out.printf("  Scenario 5: Supply=0,  Demand=10 -> Surge=%.2fx (no drivers = max)%n", surge5);

        System.out.println(THIN_SEP);

        // Show impact on fare
        StandardPricingStrategy standard = new StandardPricingStrategy();
        SurgePricingStrategy surgeStrategy = new SurgePricingStrategy(standard);

        double distKm = 5.0;
        double durMin = 15.0;
        Vehicle.VehicleType type = Vehicle.VehicleType.SEDAN;

        double baseFare = standard.calculateFare(distKm, durMin, type, 1.0);
        System.out.printf("%n  Base fare for %.0f km / %.0f min SEDAN ride: $%.2f%n", distKm, durMin, baseFare);
        System.out.println();
        System.out.println("  Fare at different surge levels:");
        for (double surge : new double[]{1.0, 1.25, 1.5, 2.0, 2.5, 3.0}) {
            double fare = surgeStrategy.calculateFare(distKm, durMin, type, surge);
            System.out.printf("    %.2fx surge: $%.2f%s%n", surge, fare,
                    surge > 2.0 ? " (high surge warning!)" : "");
        }
        System.out.println();
    }

    // ─── Demo 5: Vehicle Type Pricing ──────────────────────────────────

    private static void demoVehicleTypePricing() {
        printHeader("Demo 5: Vehicle Type Pricing (Same Route, Different Vehicles)");
        System.out.println("  Scenario: 8 km / 20 min ride — compare fares across all vehicle types");
        System.out.println();

        StandardPricingStrategy pricing = new StandardPricingStrategy();
        double distKm = 8.0;
        double durMin = 20.0;

        System.out.printf("  Route: %.0f km, %.0f minutes%n%n", distKm, durMin);
        System.out.printf("  %-15s %-10s %-10s %-10s %-10s%n",
                "Vehicle Type", "Base ($)", "$/km", "$/min", "Total ($)");
        System.out.printf("  %-15s %-10s %-10s %-10s %-10s%n",
                "------------", "--------", "----", "-----", "---------");

        for (Vehicle.VehicleType type : Vehicle.VehicleType.values()) {
            double[] rates = pricing.getRates(type);
            double fare = pricing.calculateFare(distKm, durMin, type, 1.0);
            System.out.printf("  %-15s $%-9.2f $%-9.2f $%-9.2f $%-9.2f%n",
                    type, rates[0], rates[1], rates[2], fare);
        }

        // Show the cheapest and most expensive
        double cheapest = Double.MAX_VALUE;
        double mostExpensive = 0;
        Vehicle.VehicleType cheapestType = null, expensiveType = null;

        for (Vehicle.VehicleType type : Vehicle.VehicleType.values()) {
            double fare = pricing.calculateFare(distKm, durMin, type, 1.0);
            if (fare < cheapest) { cheapest = fare; cheapestType = type; }
            if (fare > mostExpensive) { mostExpensive = fare; expensiveType = type; }
        }

        System.out.println();
        System.out.printf("  Cheapest:      %s at $%.2f%n", cheapestType, cheapest);
        System.out.printf("  Most expensive: %s at $%.2f%n", expensiveType, mostExpensive);
        System.out.printf("  Difference:     $%.2f (%.0f%% more)%n",
                mostExpensive - cheapest, ((mostExpensive / cheapest) - 1) * 100);
        System.out.println();
    }

    // ─── Demo 6: Driver Timeout & Cascade ──────────────────────────────

    private static void demoDriverTimeoutCascade() {
        printHeader("Demo 6: Driver Timeout & Cascade");
        System.out.println("  Scenario: First driver doesn't accept, system cascades to next driver");
        System.out.println();

        // Set up a system with a fresh spatial index
        RideService rideService = AppConfig.createRideService();

        Rider rider = AppConfig.createSampleRider("R1", "TimeoutTestRider", 37.7749, -122.4194);
        rideService.getRiderRepository().save(rider);

        // Create 3 drivers at increasing distances
        // We want the first driver to "timeout" and the system to cascade
        Driver d1 = AppConfig.createSampleDriver("TIMEOUT-D1", "NearDriver", 37.7760, -122.4180,
                Vehicle.VehicleType.SEDAN);
        Driver d2 = AppConfig.createSampleDriver("TIMEOUT-D2", "MidDriver", 37.7800, -122.4100,
                Vehicle.VehicleType.SEDAN);
        Driver d3 = AppConfig.createSampleDriver("TIMEOUT-D3", "FarDriver", 37.7700, -122.4300,
                Vehicle.VehicleType.SEDAN);

        rideService.getDriverRepository().save(d1);
        rideService.getDriverRepository().save(d2);
        rideService.getDriverRepository().save(d3);

        rideService.getLocationService().addDriver(d1.getId(), d1.getCurrentLocation());
        rideService.getLocationService().addDriver(d2.getId(), d2.getCurrentLocation());
        rideService.getLocationService().addDriver(d3.getId(), d3.getCurrentLocation());

        Location pickup = new Location(37.7749, -122.4194);
        Location dropoff = new Location(37.7599, -122.4148);

        System.out.println("  Drivers (sorted by distance to pickup):");
        List<Driver> sortedDrivers = List.of(d1, d2, d3);
        for (Driver d : sortedDrivers) {
            double dist = Location.distanceKm(d.getCurrentLocation(), pickup);
            System.out.printf("    %s: %.3f km away%n", d.getName(), dist);
        }
        System.out.println();

        // Demo the cascade using MatchingService directly
        NearestDriverStrategy strategy = new NearestDriverStrategy();
        RideRequest request = new RideRequest("R1", pickup, dropoff, Vehicle.VehicleType.SEDAN, 10.0, 1.0);

        MatchingService matchingService = rideService.getMatchingService();
        List<Driver> candidates = new ArrayList<>(List.of(d1, d2, d3));

        // Force the first driver to timeout, second accepts
        System.out.println("  Matching with forced timeout on first driver:");
        Optional<Driver> matched = matchingService.findDriverWithForcedTimeout(
                request, candidates, pickup, 1);

        if (matched.isPresent()) {
            System.out.printf("%n  Result: %s was matched after cascade!%n", matched.get().getName());
            System.out.printf("  Distance: %.3f km%n",
                    Location.distanceKm(matched.get().getCurrentLocation(), pickup));
        } else {
            System.out.println("  Result: No driver accepted.");
        }
        System.out.println();
    }

    // ─── Demo 7: Ride Cancellation ─────────────────────────────────────

    private static void demoRideCancellation() {
        printHeader("Demo 7: Ride Cancellation (Different Stages)");
        System.out.println("  Scenario: Cancel at different ride stages, show state machine guards");
        System.out.println();

        Rider rider = AppConfig.createSampleRider("R1", "CancelTestRider", 37.7749, -122.4194);
        Driver driver = AppConfig.createSampleDriver("D1", "CancelTestDriver", 37.7760, -122.4180,
                Vehicle.VehicleType.SEDAN);
        Location pickup = new Location(37.7749, -122.4194);
        Location dropoff = new Location(37.7599, -122.4148);

        // Cancellation 1: Cancel before match (REQUESTED)
        System.out.println("  --- Cancel before match (REQUESTED state) ---");
        Ride ride1 = new Ride.Builder(rider, pickup, dropoff).rideId("cancel-1").build();
        System.out.printf("  Status before cancel: %s%n", ride1.getStatus());
        ride1.cancelRide();
        System.out.printf("  Status after cancel:  %s%n", ride1.getStatus());
        System.out.println();

        // Cancellation 2: Cancel after match (MATCHED)
        System.out.println("  --- Cancel after match (MATCHED state) ---");
        Ride ride2 = new Ride.Builder(rider, pickup, dropoff).rideId("cancel-2").build();
        ride2.matchDriver(driver);
        driver.markBusy();
        System.out.printf("  Status before cancel: %s (driver: %s, available: %s)%n",
                ride2.getStatus(), driver.getName(), driver.isAvailable());
        ride2.cancelRide();
        System.out.printf("  Status after cancel:  %s (driver available: %s)%n",
                ride2.getStatus(), driver.isAvailable());
        System.out.println();

        // Cancellation 3: Cancel during ride (IN_PROGRESS)
        System.out.println("  --- Cancel during ride (IN_PROGRESS state) ---");
        driver.markAvailable();
        Ride ride3 = new Ride.Builder(rider, pickup, dropoff).rideId("cancel-3").build();
        ride3.matchDriver(driver);
        driver.markBusy();
        ride3.driverEnRoute();
        ride3.startRide();
        System.out.printf("  Status before cancel: %s%n", ride3.getStatus());
        ride3.cancelRide();
        System.out.printf("  Status after cancel:  %s (emergency cancel)%n", ride3.getStatus());
        System.out.println();

        // Cancellation 4: Try to cancel a COMPLETED ride (should fail)
        System.out.println("  --- Try to cancel COMPLETED ride (should fail) ---");
        driver.markAvailable();
        Ride ride4 = new Ride.Builder(rider, pickup, dropoff).rideId("cancel-4").build();
        ride4.matchDriver(driver);
        ride4.driverEnRoute();
        ride4.startRide();
        ride4.completeRide(25.00);
        System.out.printf("  Status: %s%n", ride4.getStatus());
        try {
            ride4.cancelRide();
            System.out.println("  ERROR: Should have thrown exception!");
        } catch (InvalidRideStateException e) {
            System.out.printf("  Correctly threw: %s%n", e.getMessage());
        }

        // Cancellation 5: Try to cancel an already cancelled ride
        System.out.println();
        System.out.println("  --- Try to cancel already CANCELLED ride (should fail) ---");
        try {
            ride1.cancelRide();
            System.out.println("  ERROR: Should have thrown exception!");
        } catch (InvalidRideStateException e) {
            System.out.printf("  Correctly threw: %s%n", e.getMessage());
        }
        System.out.println();
    }

    // ─── Demo 8: Concurrent Ride Requests ──────────────────────────────

    private static void demoConcurrentRideRequests() {
        printHeader("Demo 8: Concurrent Ride Requests");
        System.out.println("  Scenario: 5 riders compete for 3 available drivers simultaneously");
        System.out.println();

        // Set up a fresh system
        RideService rideService = AppConfig.createRideService();

        // Create 5 riders
        for (int i = 1; i <= 5; i++) {
            Rider rider = AppConfig.createSampleRider("CR" + i, "ConcRider" + i,
                    37.77 + i * 0.002, -122.42 + i * 0.002);
            rideService.getRiderRepository().save(rider);
        }

        // Create only 3 drivers (fewer than riders = contention)
        for (int i = 1; i <= 3; i++) {
            Driver driver = AppConfig.createSampleDriver("CD" + i, "ConcDriver" + i,
                    37.775 + i * 0.001, -122.418 + i * 0.001, Vehicle.VehicleType.SEDAN);
            rideService.getDriverRepository().save(driver);
            rideService.getLocationService().addDriver("CD" + i, driver.getCurrentLocation());
        }

        System.out.println("  Riders: 5 | Drivers: 3 (contention expected!)");
        System.out.println();

        // Submit all 5 ride requests concurrently using threads
        ExecutorService executor = Executors.newFixedThreadPool(5);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 1; i <= 5; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                String riderId = "CR" + idx;
                Location pickup = new Location(37.77 + idx * 0.002, -122.42 + idx * 0.002);
                Location dropoff = new Location(37.76, -122.41);

                try {
                    Ride ride = rideService.requestRide(riderId, pickup, dropoff, Vehicle.VehicleType.SEDAN);
                    return String.format("  Rider %s: MATCHED with %s (ride %s)",
                            riderId, ride.getDriver().getName(), ride.getRideId());
                } catch (NoDriverAvailableException e) {
                    return String.format("  Rider %s: NO DRIVER - %s", riderId, e.getMessage());
                } catch (Exception e) {
                    return String.format("  Rider %s: ERROR - %s", riderId, e.getMessage());
                }
            }));
        }

        // Collect results
        System.out.println("  Results:");
        for (Future<String> future : futures) {
            try {
                System.out.println(future.get(10, TimeUnit.SECONDS));
            } catch (Exception e) {
                System.out.printf("  Error: %s%n", e.getMessage());
            }
        }
        executor.shutdown();

        System.out.println();
        System.out.println("  NOTE: Some riders may not get drivers — this is the 'thundering herd'");
        System.out.println("  problem. In production, Uber uses batched matching to optimize globally.");
        System.out.println();
    }

    // ─── Demo 9: GeoHash Encoding ─────────────────────────────────────

    private static void demoGeoHashEncoding() {
        printHeader("Demo 9: GeoHash Encoding");
        System.out.println("  Scenario: Encode lat/lng to geohash, decode back, show neighbor cells");
        System.out.println();

        // Encode various San Francisco locations
        double[][] locations = {
                {37.7749, -122.4194, 0},  // Union Square
                {37.7849, -122.4094, 0},  // Nob Hill
                {37.7599, -122.4148, 0},  // Mission
                {37.8024, -122.4058, 0},  // North Beach
        };
        String[] names = {"Union Square", "Nob Hill", "Mission District", "North Beach"};

        System.out.printf("  %-20s %-25s %-12s %-12s %-12s%n",
                "Location", "Coordinates", "Precision 5", "Precision 6", "Precision 8");
        System.out.printf("  %-20s %-25s %-12s %-12s %-12s%n",
                "--------", "-----------", "-----------", "-----------", "-----------");

        for (int i = 0; i < locations.length; i++) {
            double lat = locations[i][0];
            double lng = locations[i][1];
            String gh5 = GeoHash.encode(lat, lng, 5);
            String gh6 = GeoHash.encode(lat, lng, 6);
            String gh8 = GeoHash.encode(lat, lng, 8);
            System.out.printf("  %-20s (%.4f, %.4f)     %-12s %-12s %-12s%n",
                    names[i], lat, lng, gh5, gh6, gh8);
        }

        System.out.println();

        // Show prefix matching — nearby locations share prefixes
        String refHash = GeoHash.encode(37.7749, -122.4194, 6);
        System.out.printf("  Reference: Union Square geohash (precision 6) = %s%n", refHash);
        System.out.println("  Prefix matching with other locations:");
        for (int i = 1; i < locations.length; i++) {
            String hash = GeoHash.encode(locations[i][0], locations[i][1], 6);
            int commonPrefix = 0;
            for (int j = 0; j < Math.min(refHash.length(), hash.length()); j++) {
                if (refHash.charAt(j) == hash.charAt(j)) commonPrefix++;
                else break;
            }
            System.out.printf("    %s: %s (common prefix: %d chars)%n", names[i], hash, commonPrefix);
        }

        System.out.println();

        // Show decode (roundtrip)
        System.out.println("  Roundtrip encoding/decoding:");
        String encoded = GeoHash.encode(37.7749, -122.4194, 8);
        Location decoded = GeoHash.decode(encoded);
        System.out.printf("    Original:  (37.7749, -122.4194)%n");
        System.out.printf("    Encoded:   %s%n", encoded);
        System.out.printf("    Decoded:   %s%n", decoded);
        System.out.printf("    Error:     %.6f km%n",
                Location.distanceKm(new Location(37.7749, -122.4194), decoded));

        System.out.println();

        // Show neighbors
        String centerHash = GeoHash.encode(37.7749, -122.4194, 5);
        System.out.printf("  Neighbors of %s (precision 5):%n", centerHash);
        List<String> neighbors = GeoHash.getNeighbors(centerHash);
        for (int i = 0; i < neighbors.size(); i++) {
            String dir = switch (i) {
                case 0 -> "SW";
                case 1 -> "S ";
                case 2 -> "SE";
                case 3 -> "W ";
                case 4 -> "E ";
                case 5 -> "NW";
                case 6 -> "N ";
                case 7 -> "NE";
                default -> "? ";
            };
            System.out.printf("    %s: %s%n", dir, neighbors.get(i));
        }
        System.out.println();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  DESIGN SUMMARY
    // ═══════════════════════════════════════════════════════════════════

    private static void printDesignSummary() {
        System.out.println(SEPARATOR);
        System.out.println("  DESIGN SUMMARY — Ride-Sharing System (Uber/Lyft)");
        System.out.println(SEPARATOR);
        System.out.println();
        System.out.println("  PATTERNS USED:");
        System.out.println("    - Strategy:   MatchingStrategy (NearestDriver, ETABased)");
        System.out.println("    - Strategy:   PricingStrategy (Standard, Surge)");
        System.out.println("    - Decorator:  SurgePricingStrategy wraps StandardPricingStrategy");
        System.out.println("    - Builder:    Ride.Builder (many optional fields)");
        System.out.println("    - Facade:     RideService orchestrates all services");
        System.out.println("    - Repository: Interface + InMemory implementation");
        System.out.println("    - Factory:    AppConfig creates and wires all dependencies");
        System.out.println("    - State Machine: Ride status transitions with guards");
        System.out.println();
        System.out.println("  KEY DATA STRUCTURES:");
        System.out.println("    - QuadTree:  Spatial index for O(sqrt(n)+k) nearby driver queries");
        System.out.println("    - GeoHash:   Encodes lat/lng to string (prefix = proximity)");
        System.out.println("    - BoundingBox: Axis-aligned rectangle for spatial pruning");
        System.out.println();
        System.out.println("  SCALABILITY CONSIDERATIONS:");
        System.out.println("    - Spatial sharding: partition by city/region (each has own QuadTree)");
        System.out.println("    - Location updates: 250K/sec at scale -> Redis + H3 (Uber uses this)");
        System.out.println("    - Matching: batched every 2s for global optimization (not greedy)");
        System.out.println("    - Surge: ML-based in production (not ratio tiers)");
        System.out.println("    - Payments: async, event-driven (Kafka), retry with backoff");
        System.out.println("    - State machine: Saga pattern for distributed transactions");
        System.out.println();
        System.out.println("  EDGE CASES DEMONSTRATED:");
        System.out.println("    - No driver available (expanded search, retry)");
        System.out.println("    - Driver timeout + cascade to next driver");
        System.out.println("    - Surge pricing at different demand/supply ratios");
        System.out.println("    - Payment failure handling (90% success simulation)");
        System.out.println("    - Concurrent ride requests (thundering herd)");
        System.out.println("    - Ride cancellation at every stage (state machine guards)");
        System.out.println("    - GeoHash boundary problem (neighbor cells)");
        System.out.println();
        System.out.println(SEPARATOR);
        System.out.println("  Total: 42 files | 10 model | 5 spatial | 5 strategy");
        System.out.println("         | 7 service | 6 repository | 3 exception");
        System.out.println("         | 1 controller | 1 config | 1 display | 1 app");
        System.out.println(SEPARATOR);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  UTILITY
    // ═══════════════════════════════════════════════════════════════════

    private static void printHeader(String title) {
        System.out.println(SEPARATOR);
        System.out.printf("  %s%n", title);
        System.out.println(SEPARATOR);
        System.out.println();
    }
}
