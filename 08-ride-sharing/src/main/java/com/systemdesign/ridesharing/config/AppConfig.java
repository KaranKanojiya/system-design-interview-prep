package com.systemdesign.ridesharing.config;

import com.systemdesign.ridesharing.controller.RideController;
import com.systemdesign.ridesharing.model.*;
import com.systemdesign.ridesharing.repository.*;
import com.systemdesign.ridesharing.service.*;
import com.systemdesign.ridesharing.spatial.BoundingBox;
import com.systemdesign.ridesharing.spatial.QuadTree;
import com.systemdesign.ridesharing.spatial.SpatialIndex;
import com.systemdesign.ridesharing.strategy.matching.ETABasedStrategy;
import com.systemdesign.ridesharing.strategy.matching.MatchingStrategy;
import com.systemdesign.ridesharing.strategy.matching.NearestDriverStrategy;
import com.systemdesign.ridesharing.strategy.pricing.PricingStrategy;
import com.systemdesign.ridesharing.strategy.pricing.StandardPricingStrategy;
import com.systemdesign.ridesharing.strategy.pricing.SurgePricingStrategy;

/**
 * AppConfig — FACTORY that creates all objects and wires dependencies.
 *
 * This is the ONLY place in the entire codebase where "new ConcreteClass()" appears.
 * Everything else codes to interfaces. This is manual dependency injection —
 * in production, Spring/Guice would handle this.
 *
 * DEPENDENCY GRAPH (bottom-up):
 *
 *   Repositories (data layer):
 *     InMemoryRideRepository    ─┐
 *     InMemoryRiderRepository   ─┤
 *     InMemoryDriverRepository  ─┘── used by services
 *
 *   Spatial Index:
 *     QuadTree (BoundingBox)    ─── used by LocationService
 *
 *   Strategies:
 *     NearestDriverStrategy     ─── used by MatchingService
 *     ETABasedStrategy          ─── alternative matching
 *     StandardPricingStrategy   ─┐
 *     SurgePricingStrategy      ─┘── used by PricingService
 *
 *   Services:
 *     LocationService (QuadTree)           ─── spatial queries
 *     MatchingService (Location, Driver, Strategy)  ─── driver matching
 *     PricingService (PricingStrategy)     ─── fare calculation
 *     SurgeService                         ─── surge zones
 *     PaymentService                       ─── payment processing
 *     NotificationService                  ─── push notifications
 *
 *   Facade:
 *     RideService (all services + all repos) ─── orchestrates lifecycle
 *
 *   Controller:
 *     RideController (RideService)           ─── HTTP endpoint simulation
 *
 * WIRING ORDER matters:
 *   1. Create repositories (no dependencies)
 *   2. Create spatial index (no dependencies)
 *   3. Create strategies (no dependencies)
 *   4. Create services (depend on repos, spatial index, strategies)
 *   5. Create facade (depends on all services)
 *   6. Create controller (depends on facade)
 */
public class AppConfig {

    // ═══════════════════════════════════════════════════════════════════
    //  CONFIGURATION CONSTANTS
    // ═══════════════════════════════════════════════════════════════════

    /** San Francisco bounding box — our demo city. */
    public static final double SF_MIN_LAT = 37.70;
    public static final double SF_MAX_LAT = 37.85;
    public static final double SF_MIN_LNG = -122.52;
    public static final double SF_MAX_LNG = -122.35;

    /** Surge grid configuration. */
    public static final double SURGE_GRID_SIZE_KM = 20.0;
    public static final double SURGE_ZONE_RADIUS_KM = 2.0;

    private AppConfig() {} // utility class — no instances

    // ═══════════════════════════════════════════════════════════════════
    //  REPOSITORY FACTORIES — data layer, no dependencies
    // ═══════════════════════════════════════════════════════════════════

    public static RideRepository createRideRepository() {
        return new InMemoryRideRepository();
    }

    public static RiderRepository createRiderRepository() {
        return new InMemoryRiderRepository();
    }

    public static DriverRepository createDriverRepository() {
        return new InMemoryDriverRepository();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  SPATIAL INDEX FACTORY — QuadTree covering San Francisco
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Create a QuadTree spatial index covering the given area.
     * Default: San Francisco metro area.
     */
    public static SpatialIndex createSpatialIndex() {
        BoundingBox sfBounds = new BoundingBox(SF_MIN_LAT, SF_MAX_LAT, SF_MIN_LNG, SF_MAX_LNG);
        return new QuadTree(sfBounds);
    }

    /**
     * Create a QuadTree with custom bounds (for global or other city demos).
     */
    public static SpatialIndex createSpatialIndex(BoundingBox bounds) {
        return new QuadTree(bounds);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  STRATEGY FACTORIES — no dependencies
    // ═══════════════════════════════════════════════════════════════════

    public static MatchingStrategy createNearestDriverStrategy() {
        return new NearestDriverStrategy();
    }

    public static MatchingStrategy createETABasedStrategy() {
        return new ETABasedStrategy();
    }

    public static StandardPricingStrategy createStandardPricingStrategy() {
        return new StandardPricingStrategy();
    }

    public static PricingStrategy createSurgePricingStrategy() {
        return new SurgePricingStrategy(new StandardPricingStrategy());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  SERVICE FACTORIES — depend on repos, spatial index, strategies
    // ═══════════════════════════════════════════════════════════════════

    public static LocationService createLocationService() {
        return new LocationService(createSpatialIndex());
    }

    public static LocationService createLocationService(SpatialIndex spatialIndex) {
        return new LocationService(spatialIndex);
    }

    public static MatchingService createMatchingService(LocationService locationService,
                                                         DriverRepository driverRepository) {
        return new MatchingService(locationService, driverRepository, createNearestDriverStrategy());
    }

    public static MatchingService createMatchingService(LocationService locationService,
                                                         DriverRepository driverRepository,
                                                         MatchingStrategy strategy) {
        return new MatchingService(locationService, driverRepository, strategy);
    }

    public static PricingService createPricingService() {
        return new PricingService(createSurgePricingStrategy());
    }

    public static PricingService createPricingService(PricingStrategy strategy) {
        return new PricingService(strategy);
    }

    public static SurgeService createSurgeService() {
        SurgeService service = new SurgeService();
        // Initialize with a default grid centered on San Francisco
        double centerLat = (SF_MIN_LAT + SF_MAX_LAT) / 2;
        double centerLng = (SF_MIN_LNG + SF_MAX_LNG) / 2;
        service.initializeGrid(centerLat, centerLng, SURGE_GRID_SIZE_KM, SURGE_ZONE_RADIUS_KM);
        return service;
    }

    public static PaymentService createPaymentService() {
        return new PaymentService();
    }

    public static NotificationService createNotificationService() {
        return new NotificationService();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  FACADE FACTORY — depends on all services
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Create a fully-wired RideService with all dependencies.
     *
     * This is the main factory method — call this to get a complete system.
     * All concrete classes are instantiated HERE and only here.
     */
    public static RideService createRideService() {
        // 1. Repositories
        RideRepository rideRepo = createRideRepository();
        RiderRepository riderRepo = createRiderRepository();
        DriverRepository driverRepo = createDriverRepository();

        // 2. Spatial index
        SpatialIndex spatialIndex = createSpatialIndex();

        // 3. Services (order matters — some depend on others)
        LocationService locationService = createLocationService(spatialIndex);
        MatchingService matchingService = createMatchingService(locationService, driverRepo);
        PricingService pricingService = createPricingService();
        SurgeService surgeService = createSurgeService();
        PaymentService paymentService = createPaymentService();
        NotificationService notificationService = createNotificationService();

        // 4. Facade
        return new RideService(rideRepo, riderRepo, driverRepo,
                matchingService, pricingService, surgeService,
                paymentService, notificationService, locationService);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  CONTROLLER FACTORY — depends on facade
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Create a fully-wired RideController.
     * Top-level factory — creates EVERYTHING needed for the system.
     */
    public static RideController createController() {
        return new RideController(createRideService());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  DEMO DATA FACTORIES — create sample riders, drivers, vehicles
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Create a sample rider for demos.
     */
    public static Rider createSampleRider(String id, String name, double lat, double lng) {
        return new Rider(id, name, 4.8, PaymentMethod.CREDIT_CARD, new Location(lat, lng));
    }

    /**
     * Create a sample driver with a SEDAN for demos.
     */
    public static Driver createSampleDriver(String id, String name, double lat, double lng) {
        return new Driver(id, name, 4.7,
                new Vehicle(Vehicle.VehicleType.SEDAN, "ABC-" + id.hashCode() % 1000),
                new Location(lat, lng));
    }

    /**
     * Create a sample driver with a specific vehicle type.
     */
    public static Driver createSampleDriver(String id, String name, double lat, double lng,
                                             Vehicle.VehicleType vehicleType) {
        return new Driver(id, name, 4.5 + (Math.abs(id.hashCode()) % 5) * 0.1,
                new Vehicle(vehicleType, "PLT-" + Math.abs(id.hashCode()) % 10000),
                new Location(lat, lng));
    }
}
