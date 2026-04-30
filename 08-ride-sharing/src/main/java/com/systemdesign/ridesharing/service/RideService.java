package com.systemdesign.ridesharing.service;

import com.systemdesign.ridesharing.exception.NoDriverAvailableException;
import com.systemdesign.ridesharing.exception.PaymentFailedException;
import com.systemdesign.ridesharing.exception.RideException;
import com.systemdesign.ridesharing.model.*;
import com.systemdesign.ridesharing.repository.DriverRepository;
import com.systemdesign.ridesharing.repository.RideRepository;
import com.systemdesign.ridesharing.repository.RiderRepository;

import java.util.Optional;

/**
 * RideService — FACADE that orchestrates the entire ride lifecycle.
 *
 * This is the main entry point for all ride operations. It coordinates:
 *   - SurgeService (get surge multiplier for pickup location)
 *   - PricingService (estimate/calculate fares)
 *   - MatchingService (find and match a driver)
 *   - NotificationService (push notifications to rider/driver)
 *   - PaymentService (charge the rider after ride)
 *   - RideRepository (persist ride state)
 *
 * FULL CALL CHAIN for requestRide():
 *   1. RideService.requestRide(riderId, pickup, dropoff, vehicleType)
 *   2.   -> RiderRepository.findById(riderId)  [get rider profile]
 *   3.   -> SurgeService.getSurgeMultiplier(pickup)  [check surge in this zone]
 *   4.   -> PricingService.estimateFare(pickup, dropoff, type, surge)  [pre-ride estimate]
 *   5.   -> Create RideRequest object with all info
 *   6.   -> Create Ride object (Builder pattern, status = REQUESTED)
 *   7.   -> MatchingService.findAndMatchDriver(request)
 *          -> LocationService.findNearbyDrivers(pickup, 5km, 10)
 *              -> QuadTree.findNearby(pickup, 5km, 10)  [spatial query]
 *          -> DriverRepository.findById() for each nearby driver
 *          -> MatchingStrategy.findBestDriver()  [nearest/ETA-based]
 *          -> Simulate driver acceptance (with timeout + cascade)
 *   8.   -> ride.matchDriver(driver)  [state: REQUESTED -> MATCHED]
 *   9.   -> driver.markBusy()  [driver no longer available]
 *   10.  -> NotificationService.notifyRider("Driver found!")
 *   11.  -> NotificationService.notifyDriver("New ride request!")
 *   12.  -> RideRepository.save(ride)
 *   13.  -> Return ride
 *
 * WHY a Facade:
 *   The ride lifecycle involves 7+ services. Without RideService, the controller
 *   would have to coordinate all of them — duplicating logic and creating tight
 *   coupling. The facade provides a clean API and encapsulates the orchestration.
 *
 * INTERVIEW TIP:
 *   "In production Uber, the equivalent of RideService is split into multiple
 *   microservices communicating via events (Kafka). The ride lifecycle is
 *   managed by a state machine service. Each state transition publishes an event
 *   that triggers downstream actions (matching, pricing, notification, etc.)."
 */
public class RideService {

    private final RideRepository rideRepository;
    private final RiderRepository riderRepository;
    private final DriverRepository driverRepository;
    private final MatchingService matchingService;
    private final PricingService pricingService;
    private final SurgeService surgeService;
    private final PaymentService paymentService;
    private final NotificationService notificationService;
    private final LocationService locationService;

    public RideService(RideRepository rideRepository,
                       RiderRepository riderRepository,
                       DriverRepository driverRepository,
                       MatchingService matchingService,
                       PricingService pricingService,
                       SurgeService surgeService,
                       PaymentService paymentService,
                       NotificationService notificationService,
                       LocationService locationService) {
        this.rideRepository = rideRepository;
        this.riderRepository = riderRepository;
        this.driverRepository = driverRepository;
        this.matchingService = matchingService;
        this.pricingService = pricingService;
        this.surgeService = surgeService;
        this.paymentService = paymentService;
        this.notificationService = notificationService;
        this.locationService = locationService;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  REQUEST RIDE — Entry point for the ride lifecycle
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Request a new ride.
     *
     * This is the BIG method — orchestrates the entire flow from request to match.
     * See the full call chain in the class-level Javadoc above.
     *
     * @param riderId     who is requesting the ride
     * @param pickup      where to pick them up
     * @param dropoff     where they want to go
     * @param vehicleType what kind of vehicle they want
     * @return the created Ride (status will be MATCHED if a driver was found)
     * @throws NoDriverAvailableException if no driver can be found
     */
    public Ride requestRide(String riderId, Location pickup, Location dropoff,
                            Vehicle.VehicleType vehicleType) {
        System.out.println("  [RideService] === Requesting new ride ===");

        // Step 1: Get the rider
        Rider rider = riderRepository.findById(riderId)
                .orElseThrow(() -> new RideException("Rider not found: " + riderId));
        System.out.printf("  [RideService] Rider: %s%n", rider.getName());

        // Step 2: Get surge multiplier for the pickup zone
        double surgeMultiplier = surgeService.getSurgeMultiplier(pickup);
        System.out.printf("  [RideService] Surge at pickup: %.2fx%n", surgeMultiplier);

        // Step 3: Estimate the fare (shown to rider before confirmation)
        double estimatedFare = pricingService.estimateFare(pickup, dropoff, vehicleType, surgeMultiplier);
        System.out.printf("  [RideService] Estimated fare: $%.2f%n", estimatedFare);

        // Step 4: Create the ride request
        RideRequest request = new RideRequest(riderId, pickup, dropoff, vehicleType,
                estimatedFare, surgeMultiplier);

        // Step 5: Build the Ride object (status = REQUESTED)
        Ride ride = new Ride.Builder(rider, pickup, dropoff)
                .estimatedFare(estimatedFare)
                .surgeMultiplier(surgeMultiplier)
                .build();
        rideRepository.save(ride);
        System.out.printf("  [RideService] Ride created: %s (status: %s)%n",
                ride.getRideId(), ride.getStatus());

        // Step 6: Find and match a driver
        try {
            Driver driver = matchingService.findAndMatchDriver(request);

            // Step 7: Match driver to ride (state: REQUESTED -> MATCHED)
            ride.matchDriver(driver);
            driver.markBusy();

            // Step 8: Transition to DRIVER_EN_ROUTE (driver heading to pickup)
            ride.driverEnRoute();

            // Step 9: Notify both parties
            double distToPickup = Location.distanceKm(driver.getCurrentLocation(), pickup);
            notificationService.notifyRider(riderId,
                    String.format("Driver %s is on the way! ETA: %.0f min (%.1f km away)",
                            driver.getName(), (distToPickup * 1.3 / 30.0) * 60, distToPickup));
            notificationService.notifyDriver(driver.getId(),
                    String.format("New ride! Pick up %s at %s", rider.getName(), pickup));

            // Step 10: Save updated ride
            rideRepository.save(ride);
            System.out.printf("  [RideService] Ride matched: driver=%s, status=%s%n",
                    driver.getName(), ride.getStatus());

        } catch (NoDriverAvailableException e) {
            System.out.printf("  [RideService] No driver available: %s%n", e.getMessage());
            notificationService.notifyRider(riderId,
                    "Sorry, no drivers available right now. Please try again.");
            throw e;
        }

        return ride;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  START RIDE — Rider has been picked up
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Start a ride (rider has been picked up by the driver).
     * State: DRIVER_EN_ROUTE -> IN_PROGRESS
     */
    public Ride startRide(String rideId) {
        Ride ride = findRideOrThrow(rideId);
        ride.startRide();
        rideRepository.save(ride);

        notificationService.notifyRider(ride.getRider().getId(), "Your ride has started!");
        System.out.printf("  [RideService] Ride %s started (IN_PROGRESS)%n", rideId);
        return ride;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  COMPLETE RIDE — Ride is finished, process payment
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Complete a ride and process payment.
     *
     * Call chain:
     *   1. Validate state (must be IN_PROGRESS)
     *   2. Calculate actual fare from PricingService
     *   3. Transition ride to COMPLETED (sets actualFare, endTime)
     *   4. Process payment via PaymentService
     *   5. Update driver location to dropoff
     *   6. Notify both parties with receipt
     */
    public Ride completeRide(String rideId) {
        Ride ride = findRideOrThrow(rideId);
        System.out.printf("  [RideService] === Completing ride %s ===%n", rideId);

        // Calculate actual fare
        double actualFare = pricingService.calculateActualFare(ride);
        System.out.printf("  [RideService] Actual fare: $%.2f%n", actualFare);

        // Transition to COMPLETED (sets actualFare, endTime, releases driver)
        ride.completeRide(actualFare);
        rideRepository.save(ride);

        // Process payment
        try {
            paymentService.processPayment(ride);
        } catch (PaymentFailedException e) {
            // Payment failed but ride is still completed — add to rider's balance
            System.out.printf("  [RideService] Payment failed: %s (added to balance)%n", e.getMessage());
        }

        // Update driver location to dropoff (they're now at the dropoff point)
        if (ride.getDriver() != null) {
            locationService.updateDriverLocation(ride.getDriver().getId(), ride.getDropoff());
        }

        // Notify
        notificationService.notifyRider(ride.getRider().getId(),
                String.format("Ride complete! Fare: $%.2f. Thanks for riding!", actualFare));
        if (ride.getDriver() != null) {
            notificationService.notifyDriver(ride.getDriver().getId(),
                    String.format("Ride complete! Earned: $%.2f", actualFare));
        }

        System.out.printf("  [RideService] Ride %s completed. Fare: $%.2f%n", rideId, actualFare);
        return ride;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  CANCEL RIDE — Rider or driver cancels
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Cancel a ride.
     * State: (any except COMPLETED) -> CANCELLED
     *
     * Cancellation rules (in production):
     *   - Before match: no charge
     *   - After match, before pickup: cancellation fee ($5)
     *   - During ride: full fare up to cancellation point
     */
    public Ride cancelRide(String rideId) {
        Ride ride = findRideOrThrow(rideId);
        RideStatus previousStatus = ride.getStatus();

        ride.cancelRide();  // releases driver if assigned
        rideRepository.save(ride);

        // Notify
        notificationService.notifyRider(ride.getRider().getId(),
                "Your ride has been cancelled.");
        if (ride.getDriver() != null) {
            notificationService.notifyDriver(ride.getDriver().getId(),
                    "Ride cancelled by rider.");
            // Re-add driver to spatial index as available
            locationService.updateDriverLocation(ride.getDriver().getId(),
                    ride.getDriver().getCurrentLocation());
        }

        System.out.printf("  [RideService] Ride %s cancelled (was %s)%n", rideId, previousStatus);
        return ride;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  GET RIDE — Read ride details
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Get a ride by ID.
     */
    public Optional<Ride> getRide(String rideId) {
        return rideRepository.findById(rideId);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private Ride findRideOrThrow(String rideId) {
        return rideRepository.findById(rideId)
                .orElseThrow(() -> new RideException("Ride not found: " + rideId));
    }

    // --- Getters for sub-services (used by controller/display) ---

    public RideRepository getRideRepository() {
        return rideRepository;
    }

    public PricingService getPricingService() {
        return pricingService;
    }

    public SurgeService getSurgeService() {
        return surgeService;
    }

    public MatchingService getMatchingService() {
        return matchingService;
    }

    public LocationService getLocationService() {
        return locationService;
    }

    public DriverRepository getDriverRepository() {
        return driverRepository;
    }

    public RiderRepository getRiderRepository() {
        return riderRepository;
    }

    public PaymentService getPaymentService() {
        return paymentService;
    }

    public NotificationService getNotificationService() {
        return notificationService;
    }
}
