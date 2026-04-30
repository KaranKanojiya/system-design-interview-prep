# Low-Level Design: Ride-Sharing System (Uber/Lyft)

> **Difficulty**: HARD | **Target**: Senior Java Developer (7+ years) | **Focus**: Spatial Indexing (QuadTree), State Machines, Surge Pricing, Strategy Pattern, Concurrency
> This is a top-tier system design question. It tests spatial data structures (QuadTree, GeoHash), real-time matching algorithms, state machine design, and pricing strategy patterns.

---

## Table of Contents

1. [Core Modules Overview](#1-core-modules-overview)
2. [Package Structure](#2-package-structure)
3. [Class Diagram](#3-class-diagram)
4. [Entity Design](#4-entity-design)
5. [Interface Contracts](#5-interface-contracts)
6. [Strategy Implementations](#6-strategy-implementations)
7. [Service Layer Design](#7-service-layer-design)
8. [Concurrency Considerations](#8-concurrency-considerations)
9. [SOLID Principles Applied](#9-solid-principles-applied)
10. [Sample Workflows](#10-sample-workflows)
11. [Design Patterns Used](#11-design-patterns-used)
12. [Extensibility Points](#12-extensibility-points)

---

## 1. Core Modules Overview

| Module | Package | Responsibility |
|--------|---------|----------------|
| **Model** | `model/` | Domain entities: Location (lat/lng with Haversine), Rider, Driver, Vehicle, Ride (Builder + state machine), RideRequest, SurgeZone, Payment. Enums: RideStatus, PaymentMethod. |
| **Spatial** | `spatial/` | Spatial indexing for driver lookup: SpatialIndex interface, QuadTree (recursive subdivision, range query), QuadTreeNode (bounds, points, children[4]), GeoHash (encode/decode lat/lng), BoundingBox (contains, intersects). |
| **Strategy (Matching)** | `strategy/matching/` | Pluggable driver matching: NearestDriverStrategy (K nearest by Haversine), ETABasedStrategy (distance + traffic simulation). Strategy pattern -- swap matching algorithm without touching service logic. |
| **Strategy (Pricing)** | `strategy/pricing/` | Pluggable fare calculation: StandardPricingStrategy (base + distance*rate + time*rate), SurgePricingStrategy (standard * surgeMultiplier). |
| **Service** | `service/` | Business logic: RideService (Facade -- orchestrates matching, pricing, ride lifecycle), LocationService (tracks driver positions, updates spatial index), MatchingService, PricingService, SurgeService (supply/demand ratio per zone), PaymentService, NotificationService. |
| **Repository** | `repository/` | Data access layer: RideRepository, DriverRepository, RiderRepository interfaces with InMemory implementations. ConcurrentHashMap-backed stores. |
| **Controller** | `controller/` | REST-like API entry point: RideController maps requests to RideService calls. |
| **Config** | `config/` | Factory wiring: AppConfig creates all objects and injects dependencies. No framework -- pure constructor injection. |
| **Display** | `display/` | RideStatsDisplay: ride counts, average fare, driver utilization, surge zone stats. |
| **Exception** | `exception/` | Domain exceptions: RideException (base), NoDriverAvailableException, InvalidRideStateException, PaymentFailedException. |

### Why Ride-Sharing Is a Top-Tier Interview Question

```
Interviewer's checklist when evaluating your answer:

  1. Do you know QuadTree internals (not just "use a spatial index")?  --> Data Structures
  2. Can you implement Haversine distance formula?                     --> Algorithm Design
  3. Is driver matching pluggable (nearest vs ETA-based)?             --> Strategy Pattern
  4. Do you model ride state as a proper state machine?               --> State Machine Design
  5. Is surge pricing calculated from supply/demand ratio?            --> Real-World Modeling
  6. Are driver location updates thread-safe?                         --> Concurrency
  7. Can you add a new matching strategy without changing RideService? --> Open-Closed
  8. Is your RideService a clean Facade?                              --> Facade Pattern
  9. Do you use Builder for Ride (many optional fields)?              --> Builder Pattern
  10. Can you explain QuadTree range query complexity?                 --> Spatial Algorithms
```

---

## 2. Package Structure

```
com.systemdesign.ridesharing
│
├── model/
│   ├── Location.java           -- lat/lng with Haversine distance calculation
│   ├── Rider.java              -- name, rating, payment method
│   ├── Driver.java             -- name, rating, vehicle, isAvailable, currentLocation
│   ├── Vehicle.java            -- type (SEDAN/SUV/POOL), plate, capacity
│   ├── Ride.java               -- Builder pattern, full lifecycle state machine
│   ├── RideStatus.java         -- enum: REQUESTED, MATCHED, DRIVER_EN_ROUTE, IN_PROGRESS, COMPLETED, CANCELLED
│   ├── RideRequest.java        -- pickup, dropoff, rideType, estimatedFare
│   ├── SurgeZone.java          -- zoneId, multiplier, supply, demand, boundaries
│   ├── Payment.java            -- amount, method, status
│   └── PaymentMethod.java      -- enum: CREDIT_CARD, DEBIT_CARD, WALLET, CASH
│
├── spatial/
│   ├── SpatialIndex.java       -- interface: insert, search, remove, rangeQuery
│   ├── QuadTree.java           -- recursive quad subdivision, range query
│   ├── QuadTreeNode.java       -- bounds, points, children[4], isLeaf
│   ├── GeoHash.java            -- encode/decode lat/lng, prefix-based neighbors
│   └── BoundingBox.java        -- minLat, maxLat, minLng, maxLng, contains(), intersects()
│
├── strategy/
│   ├── matching/
│   │   ├── MatchingStrategy.java      -- interface
│   │   ├── NearestDriverStrategy.java -- find K nearest by Haversine distance
│   │   └── ETABasedStrategy.java      -- factor in ETA (distance + traffic simulation)
│   │
│   └── pricing/
│       ├── PricingStrategy.java       -- interface
│       ├── StandardPricingStrategy.java -- base + distance*rate + time*rate
│       └── SurgePricingStrategy.java  -- standard * surgeMultiplier
│
├── service/
│   ├── RideService.java        -- FACADE: orchestrates matching, pricing, ride lifecycle
│   ├── LocationService.java    -- tracks driver locations, updates spatial index
│   ├── MatchingService.java    -- uses SpatialIndex + MatchingStrategy
│   ├── PricingService.java     -- calculates fares using PricingStrategy
│   ├── SurgeService.java       -- calculates supply/demand ratio per zone
│   ├── PaymentService.java     -- processes payments
│   └── NotificationService.java -- sends push notifications (simulated)
│
├── repository/
│   ├── RideRepository.java     -- interface
│   ├── InMemoryRideRepository.java
│   ├── DriverRepository.java   -- interface
│   ├── InMemoryDriverRepository.java
│   ├── RiderRepository.java    -- interface
│   └── InMemoryRiderRepository.java
│
├── controller/
│   └── RideController.java     -- REST-like entry point
│
├── config/
│   └── AppConfig.java          -- factory wiring
│
├── display/
│   └── RideStatsDisplay.java   -- formatted stats
│
├── exception/
│   ├── RideException.java
│   ├── NoDriverAvailableException.java
│   ├── InvalidRideStateException.java
│   └── PaymentFailedException.java
│
└── RideSharingApp.java         -- Main demo: wires everything, runs ride scenarios
```

---

## 3. Class Diagram

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║                    SPATIAL INDEX HIERARCHY (Strategy Pattern)                     ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    +-----------------------------------------------------------+
    |          <<interface>>  SpatialIndex                       |
    |-----------------------------------------------------------|
    | + insert(id: String, location: Location): void            |
    | + remove(id: String): void                                |
    | + update(id: String, newLocation: Location): void         |
    | + search(center: Location, radiusKm: double): List<Entry> |
    | + rangeQuery(bbox: BoundingBox): List<Entry>              |
    | + size(): int                                             |
    +-----------------------------------------------------------+
              ^
              |
         implements
              |
    +---------+---------+
    |     QuadTree       |
    |--------------------|
    | -root: QuadTreeNode|
    | -MAX_POINTS: 4     |
    | -MAX_DEPTH: 10     |
    | -entries: Map       |
    |--------------------|
    | +insert(id, loc)   |
    | +remove(id)        |
    | +update(id, loc)   |
    | +search(center, r) |
    | +rangeQuery(bbox)  |
    +--------------------+
              |
              | has-a
              v
    +--------------------+
    | QuadTreeNode       |
    |--------------------|
    | -bounds: BoundingBox|
    | -points: List<Pt>  |
    | -children[4]: Node |
    | -isLeaf: boolean   |
    | -depth: int        |
    |--------------------|
    | +insert(point)     |
    | +subdivide()       |
    | +query(bbox): List |
    +--------------------+
              |
              | uses
              v
    +--------------------+
    | BoundingBox        |
    |--------------------|
    | -minLat: double    |
    | -maxLat: double    |
    | -minLng: double    |
    | -maxLng: double    |
    |--------------------|
    | +contains(loc)     |
    | +intersects(other) |
    | +midLat(): double  |
    | +midLng(): double  |
    +--------------------+


╔═══════════════════════════════════════════════════════════════════════════════════╗
║                   MATCHING STRATEGY HIERARCHY (Strategy Pattern)                  ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    +-----------------------------------------------------------+
    |          <<interface>>  MatchingStrategy                   |
    |-----------------------------------------------------------|
    | + matchDriver(request: RideRequest,                       |
    |               candidates: List<Driver>): Optional<Driver> |
    | + getStrategyName(): String                               |
    +-----------------------------------------------------------+
          ^                            ^
          |                            |
     implements                   implements
          |                            |
    +-----+----------+   +------------+-----------+
    | NearestDriver  |   | ETABased               |
    |   Strategy     |   |   Strategy             |
    |----------------|   |------------------------|
    | -maxDistance: d |   | -trafficMultiplier: d  |
    |----------------|   | -maxEtaMinutes: int    |
    | +matchDriver:  |   |------------------------|
    |  sort by       |   | +matchDriver:          |
    |  Haversine     |   |  sort by estimated     |
    |  distance,     |   |  arrival time          |
    |  pick nearest  |   |  (dist + traffic)      |
    +----------------+   +------------------------+


╔═══════════════════════════════════════════════════════════════════════════════════╗
║                    PRICING STRATEGY HIERARCHY (Strategy Pattern)                  ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    +-----------------------------------------------------------+
    |          <<interface>>  PricingStrategy                    |
    |-----------------------------------------------------------|
    | + calculateFare(distanceKm: double, timeMinutes: double,  |
    |                 rideType: String): double                  |
    | + getStrategyName(): String                               |
    +-----------------------------------------------------------+
          ^                            ^
          |                            |
     implements                   implements
          |                            |
    +-----+----------+   +------------+-----------+
    | StandardPricing|   | SurgePricing           |
    |   Strategy     |   |   Strategy             |
    |----------------|   |------------------------|
    | -baseFare: d   |   | -delegate:             |
    | -perKmRate: d  |   |   PricingStrategy      |
    | -perMinRate: d |   | -surgeMultiplier: d    |
    |----------------|   |------------------------|
    | +calculateFare:|   | +calculateFare:        |
    |  base +        |   |  delegate.calculate()  |
    |  dist*rate +   |   |  * surgeMultiplier     |
    |  time*rate     |   |  (Decorator pattern!)  |
    +----------------+   +------------------------+


╔═══════════════════════════════════════════════════════════════════════════════════╗
║                           RIDE STATE MACHINE                                     ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    ┌───────────┐   match()    ┌───────────┐  driverEnRoute()  ┌──────────────┐
    │ REQUESTED │─────────────→│  MATCHED  │──────────────────→│DRIVER_EN_ROUTE│
    └───────────┘              └───────────┘                    └──────────────┘
         │                          │                                 │
         │ cancel()                 │ cancel()                        │ startRide()
         │                          │                                 │
         ▼                          ▼                                 ▼
    ┌───────────┐              ┌───────────┐                    ┌─────────────┐
    │ CANCELLED │              │ CANCELLED │                    │ IN_PROGRESS │
    └───────────┘              └───────────┘                    └─────────────┘
                                                                      │
                                                                      │ completeRide()
                                                                      │
                                                                      ▼
                                                               ┌─────────────┐
                                                               │  COMPLETED  │
                                                               └─────────────┘

    Valid transitions (enforced by Ride.transitionTo()):
      REQUESTED      → MATCHED, CANCELLED
      MATCHED        → DRIVER_EN_ROUTE, CANCELLED
      DRIVER_EN_ROUTE→ IN_PROGRESS, CANCELLED
      IN_PROGRESS    → COMPLETED
      COMPLETED      → (terminal state)
      CANCELLED      → (terminal state)


╔═══════════════════════════════════════════════════════════════════════════════════╗
║                          SERVICE LAYER (Facade Pattern)                           ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    ┌─────────────────────────────────────────────────────────────────────┐
    │                    RideController                                    │
    │   requestRide() │ cancelRide() │ completeRide() │ getRideStatus()  │
    └────────┬────────────────┬──────────────┬─────────────────┬─────────┘
             │                │              │                 │
             ▼                ▼              ▼                 ▼
    ┌─────────────────────────────────────────────────────────────────────┐
    │                    RideService (FACADE)                              │
    │   Orchestrates: matching, pricing, ride lifecycle, payment, notify  │
    └──┬──────────┬───────────┬──────────┬───────────┬──────────┬────────┘
       │          │           │          │           │          │
       ▼          ▼           ▼          ▼           ▼          ▼
    Matching   Location   Pricing    Surge      Payment   Notification
    Service    Service    Service    Service    Service    Service
       │          │           │          │           │          │
       ▼          ▼           ▼          ▼           ▼          ▼
    Matching   Spatial    Pricing    SurgeZone  Payment    (console
    Strategy   Index      Strategy   data       processing  logging)
```

---

## 4. Entity Design

### 4.1 Location (Haversine Distance Calculation)

> **THE fundamental operation in ride-sharing.** Every matching query, every fare calculation, every ETA estimate depends on accurate distance between two points on Earth. Interviewers expect you to know the Haversine formula, not just "use a library."

#### Why Haversine? (Not Euclidean Distance)

```
     ┌──────────────────────────────────────────────────────────────────┐
     │           WHY NOT EUCLIDEAN DISTANCE FOR LAT/LNG?                │
     │                                                                  │
     │   WRONG: distance = sqrt((lat2-lat1)^2 + (lng2-lng1)^2)        │
     │                                                                  │
     │   PROBLEM 1: Earth is a sphere, not a flat plane.               │
     │     - 1 degree of latitude  = ~111 km (constant)                │
     │     - 1 degree of longitude = ~111 km * cos(latitude)           │
     │     At 60°N latitude: 1° longitude = ~55 km (half!)             │
     │                                                                  │
     │   PROBLEM 2: Euclidean treats lat/lng as Cartesian coordinates. │
     │     - (40.7128, -74.0060) to (40.7580, -73.9855)               │
     │     - Euclidean: 0.049 (meaningless units)                      │
     │     - Haversine: 5.1 km (real-world distance)                   │
     │                                                                  │
     │   HAVERSINE: accounts for Earth's curvature.                    │
     │     - Uses great-circle distance (shortest path on a sphere)    │
     │     - Accurate to ~0.5% for distances under 100 km              │
     │     - Formula: 2R * arcsin(sqrt(sin²(Δlat/2) +                 │
     │                cos(lat1)*cos(lat2)*sin²(Δlng/2)))               │
     └──────────────────────────────────────────────────────────────────┘
```

```java
/**
 * Represents a geographic location with latitude and longitude.
 *
 * The key operation is distanceTo() which uses the Haversine formula
 * to calculate great-circle distance between two points on Earth.
 *
 * This is THE most-called method in a ride-sharing system:
 *   - Matching: "find all drivers within 5 km of this pickup point"
 *   - Pricing: "calculate fare based on distance from A to B"
 *   - ETA: "how far is the nearest driver from the rider?"
 *   - Surge: "count drivers within this zone's boundaries"
 *
 * Immutable record: lat/lng never change once created.
 */
public record Location(double latitude, double longitude) {

    /**
     * Earth's radius in kilometers.
     * Used by the Haversine formula: distance = R * angle.
     * Mean radius (not equatorial or polar) for best average accuracy.
     */
    private static final double EARTH_RADIUS_KM = 6371.0;

    /**
     * Validates latitude [-90, 90] and longitude [-180, 180].
     * Compact constructor for records -- runs on every instantiation.
     */
    public Location {
        if (latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("Latitude must be in [-90, 90]: " + latitude);
        }
        if (longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Longitude must be in [-180, 180]: " + longitude);
        }
    }

    /**
     * Calculates the great-circle distance to another location using the
     * Haversine formula.
     *
     * HAVERSINE FORMULA (step-by-step):
     *
     *   1. Convert lat/lng from degrees to radians
     *   2. Compute differences: Δlat = lat2 - lat1, Δlng = lng2 - lng1
     *   3. Compute Haversine of central angle:
     *        a = sin²(Δlat/2) + cos(lat1) * cos(lat2) * sin²(Δlng/2)
     *   4. Compute central angle:
     *        c = 2 * atan2(sqrt(a), sqrt(1-a))
     *   5. Distance = R * c
     *
     * WHY atan2 instead of asin?
     *   - asin(sqrt(a)) works but has numerical issues for antipodal points
     *   - atan2 is numerically stable for all distances
     *
     * @param other the destination location
     * @return distance in kilometers
     */
    public double distanceTo(Location other) {
        // Step 1: Convert degrees to radians
        double lat1Rad = Math.toRadians(this.latitude);
        double lat2Rad = Math.toRadians(other.latitude);
        double deltaLatRad = Math.toRadians(other.latitude - this.latitude);
        double deltaLngRad = Math.toRadians(other.longitude - this.longitude);

        // Step 2: Haversine of central angle
        //   a = sin²(Δlat/2) + cos(lat1) * cos(lat2) * sin²(Δlng/2)
        double a = Math.sin(deltaLatRad / 2) * Math.sin(deltaLatRad / 2)
                 + Math.cos(lat1Rad) * Math.cos(lat2Rad)
                 * Math.sin(deltaLngRad / 2) * Math.sin(deltaLngRad / 2);

        // Step 3: Central angle using atan2 (numerically stable)
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        // Step 4: Distance = Earth's radius * central angle
        return EARTH_RADIUS_KM * c;
    }

    /**
     * Returns a BoundingBox centered on this location with the given radius.
     * Used by QuadTree range queries: first find the bounding box, then
     * filter by exact Haversine distance.
     *
     * Approximation: 1 degree latitude ~ 111 km.
     * Longitude degrees vary by latitude: 1° lng ~ 111 * cos(lat) km.
     */
    public BoundingBox boundingBox(double radiusKm) {
        double latDelta = radiusKm / 111.0;
        double lngDelta = radiusKm / (111.0 * Math.cos(Math.toRadians(latitude)));

        return new BoundingBox(
            latitude - latDelta,   // minLat
            latitude + latDelta,   // maxLat
            longitude - lngDelta,  // minLng
            longitude + lngDelta   // maxLng
        );
    }

    @Override
    public String toString() {
        return String.format("(%,.4f, %,.4f)", latitude, longitude);
    }
}
```

**Interview follow-up**: "What is the accuracy of Haversine?" Answer: Haversine assumes a perfect sphere. Earth is an oblate spheroid (slightly flattened at poles). For ride-sharing distances (under 50 km), the error is less than 0.3%, which is more than acceptable for matching and fare estimation.

---

### 4.2 BoundingBox

```java
/**
 * Axis-aligned bounding box for geographic regions.
 *
 * Used by:
 *   - QuadTree: each node has a BoundingBox defining its region
 *   - Range queries: "find all drivers within this rectangle"
 *   - SurgeZone: defines the geographic boundary of a surge zone
 *
 * Two critical operations:
 *   - contains(Location): is this point inside the box?
 *   - intersects(BoundingBox): do these two boxes overlap?
 *
 * These are the core predicates that make QuadTree range queries efficient.
 * Instead of checking every point against a circle (O(n)), we:
 *   1. Compute a bounding box around the circle
 *   2. Use intersects() to prune entire quadrants (O(log n) on average)
 *   3. Use contains() + Haversine for final filtering
 */
public record BoundingBox(double minLat, double maxLat, double minLng, double maxLng) {

    public BoundingBox {
        if (minLat > maxLat) {
            throw new IllegalArgumentException("minLat must be <= maxLat");
        }
        if (minLng > maxLng) {
            throw new IllegalArgumentException("minLng must be <= maxLng");
        }
    }

    /**
     * Returns true if the given location is inside (or on the boundary of) this box.
     *
     * Used by QuadTree:
     *   - During insert: "does this point belong in this quadrant?"
     *   - During query: "is this candidate point inside the search area?"
     */
    public boolean contains(Location location) {
        return location.latitude()  >= minLat && location.latitude()  <= maxLat
            && location.longitude() >= minLng && location.longitude() <= maxLng;
    }

    /**
     * Returns true if this box overlaps with the other box.
     *
     * Two boxes do NOT intersect if:
     *   - One is entirely above the other (minLat > other.maxLat)
     *   - One is entirely below the other (maxLat < other.minLat)
     *   - One is entirely left of the other (maxLng < other.minLng)
     *   - One is entirely right of the other (minLng > other.maxLng)
     *
     * They DO intersect if none of the above is true.
     * This is the KEY operation for QuadTree pruning.
     */
    public boolean intersects(BoundingBox other) {
        return !(this.maxLat < other.minLat   // this is below other
              || this.minLat > other.maxLat    // this is above other
              || this.maxLng < other.minLng    // this is left of other
              || this.minLng > other.maxLng);  // this is right of other
    }

    /** Returns the latitude midpoint (used by QuadTree.subdivide()). */
    public double midLat() {
        return (minLat + maxLat) / 2.0;
    }

    /** Returns the longitude midpoint (used by QuadTree.subdivide()). */
    public double midLng() {
        return (minLng + maxLng) / 2.0;
    }
}
```

---

### 4.3 Ride (Builder Pattern + State Machine)

> **Builder pattern**: Ride has many fields, some set at creation, some set later in the lifecycle. Builder avoids telescoping constructors and makes construction readable. **State machine**: Ride status transitions are guarded -- you cannot go from REQUESTED directly to COMPLETED.

#### Anti-Pattern: Telescoping Constructors

```
     ┌──────────────────────────────────────────────────────────────────┐
     │                ANTI-PATTERN: Telescoping Constructors             │
     │                                                                  │
     │   // Which parameter is which? Completely unreadable.            │
     │   Ride ride = new Ride(                                          │
     │       "ride-001",                // rideId                       │
     │       rider,                     // rider                        │
     │       null,                      // driver (not assigned yet)    │
     │       pickupLoc,                 // pickup                       │
     │       dropoffLoc,                // dropoff                      │
     │       RideStatus.REQUESTED,      // status                       │
     │       "SEDAN",                   // rideType                     │
     │       0.0,                       // estimatedFare                │
     │       0.0,                       // actualFare                   │
     │       0.0,                       // distanceKm                   │
     │       0.0,                       // durationMinutes              │
     │       1.0,                       // surgeMultiplier              │
     │       Instant.now(),             // requestedAt                  │
     │       null,                      // matchedAt                    │
     │       null,                      // startedAt                    │
     │       null,                      // completedAt                  │
     │       null                       // payment                      │
     │   );                                                             │
     │                                                                  │
     │   PROBLEMS:                                                      │
     │     1. 17 constructor parameters -- unreadable at the call site  │
     │     2. Easy to swap parameters of the same type (two doubles)    │
     │     3. Nullable fields forced into the constructor               │
     │     4. No validation at construction time                        │
     │     5. Caller must know the field order                          │
     └──────────────────────────────────────────────────────────────────┘
```

#### Clean Solution: Builder Pattern

```java
/**
 * Represents a ride from request to completion.
 *
 * Uses TWO patterns:
 *   1. BUILDER: for construction (many fields, some optional)
 *   2. STATE MACHINE: for lifecycle transitions (guarded transitions)
 *
 * A Ride progresses through:
 *   REQUESTED → MATCHED → DRIVER_EN_ROUTE → IN_PROGRESS → COMPLETED
 * with CANCELLED possible from REQUESTED, MATCHED, or DRIVER_EN_ROUTE.
 *
 * State transitions are enforced in transitionTo(). Invalid transitions
 * throw InvalidRideStateException. This prevents bugs like:
 *   - Completing a ride that was never started
 *   - Cancelling an already-completed ride
 *   - Matching a ride twice
 */
public class Ride {

    // --- Immutable fields (set at construction) ---
    private final String rideId;
    private final Rider rider;
    private final Location pickup;
    private final Location dropoff;
    private final String rideType;         // "SEDAN", "SUV", "POOL"
    private final Instant requestedAt;

    // --- Mutable fields (set during lifecycle) ---
    private Driver driver;                  // Set when MATCHED
    private RideStatus status;
    private double estimatedFare;
    private double actualFare;
    private double distanceKm;
    private double durationMinutes;
    private double surgeMultiplier;
    private Instant matchedAt;
    private Instant startedAt;
    private Instant completedAt;
    private Payment payment;

    /**
     * VALID TRANSITIONS: defines the state machine.
     * Map<CurrentState, Set<AllowedNextStates>>
     *
     * This is loaded once at class initialization.
     * transitionTo() checks this map before allowing any state change.
     */
    private static final Map<RideStatus, Set<RideStatus>> VALID_TRANSITIONS = Map.of(
        RideStatus.REQUESTED,       Set.of(RideStatus.MATCHED, RideStatus.CANCELLED),
        RideStatus.MATCHED,         Set.of(RideStatus.DRIVER_EN_ROUTE, RideStatus.CANCELLED),
        RideStatus.DRIVER_EN_ROUTE, Set.of(RideStatus.IN_PROGRESS, RideStatus.CANCELLED),
        RideStatus.IN_PROGRESS,     Set.of(RideStatus.COMPLETED),
        RideStatus.COMPLETED,       Set.of(),   // terminal state
        RideStatus.CANCELLED,       Set.of()    // terminal state
    );

    /** Private constructor: only Builder can create Ride instances. */
    private Ride(Builder builder) {
        this.rideId = builder.rideId;
        this.rider = builder.rider;
        this.pickup = builder.pickup;
        this.dropoff = builder.dropoff;
        this.rideType = builder.rideType;
        this.requestedAt = builder.requestedAt;
        this.status = RideStatus.REQUESTED;
        this.surgeMultiplier = builder.surgeMultiplier;
        this.estimatedFare = builder.estimatedFare;
    }

    /**
     * Transitions the ride to a new status.
     *
     * GUARDS:
     *   1. Check that the transition is valid per VALID_TRANSITIONS map
     *   2. If invalid → throw InvalidRideStateException
     *   3. If valid → update status + record timestamp
     *
     * WHY guarded transitions?
     *   - Prevents impossible states (e.g., COMPLETED without ever being IN_PROGRESS)
     *   - Makes bugs obvious (exception with clear message vs silent corruption)
     *   - Self-documenting: the Map IS the specification
     *
     * @param newStatus the target status
     * @throws InvalidRideStateException if the transition is not allowed
     */
    public void transitionTo(RideStatus newStatus) {
        Set<RideStatus> allowed = VALID_TRANSITIONS.get(this.status);
        if (allowed == null || !allowed.contains(newStatus)) {
            throw new InvalidRideStateException(
                String.format("Cannot transition ride %s from %s to %s. Allowed: %s",
                    rideId, this.status, newStatus, allowed));
        }

        this.status = newStatus;

        // Record timestamps at key lifecycle points
        switch (newStatus) {
            case MATCHED        -> this.matchedAt = Instant.now();
            case IN_PROGRESS    -> this.startedAt = Instant.now();
            case COMPLETED      -> this.completedAt = Instant.now();
            default -> { /* no timestamp for EN_ROUTE or CANCELLED */ }
        }
    }

    /**
     * Assigns a driver to this ride.
     * Can only be done when ride is in REQUESTED status.
     */
    public void assignDriver(Driver driver) {
        if (this.status != RideStatus.REQUESTED) {
            throw new InvalidRideStateException(
                "Cannot assign driver: ride " + rideId + " is in status " + status);
        }
        this.driver = driver;
    }

    /** Returns true if the ride is in a terminal state (COMPLETED or CANCELLED). */
    public boolean isTerminal() {
        return status == RideStatus.COMPLETED || status == RideStatus.CANCELLED;
    }

    // --- Getters ---
    public String getRideId()           { return rideId; }
    public Rider getRider()             { return rider; }
    public Driver getDriver()           { return driver; }
    public Location getPickup()         { return pickup; }
    public Location getDropoff()        { return dropoff; }
    public String getRideType()         { return rideType; }
    public RideStatus getStatus()       { return status; }
    public double getEstimatedFare()    { return estimatedFare; }
    public double getActualFare()       { return actualFare; }
    public double getDistanceKm()       { return distanceKm; }
    public double getDurationMinutes()  { return durationMinutes; }
    public double getSurgeMultiplier()  { return surgeMultiplier; }
    public Instant getRequestedAt()     { return requestedAt; }
    public Instant getMatchedAt()       { return matchedAt; }
    public Instant getStartedAt()       { return startedAt; }
    public Instant getCompletedAt()     { return completedAt; }
    public Payment getPayment()         { return payment; }

    // --- Setters for mutable fields ---
    public void setActualFare(double actualFare)         { this.actualFare = actualFare; }
    public void setDistanceKm(double distanceKm)         { this.distanceKm = distanceKm; }
    public void setDurationMinutes(double durationMinutes) { this.durationMinutes = durationMinutes; }
    public void setPayment(Payment payment)              { this.payment = payment; }

    // ========== BUILDER ==========

    /**
     * Builder for Ride.
     *
     * Usage:
     *   Ride ride = new Ride.Builder("ride-001", rider, pickup, dropoff)
     *       .rideType("SEDAN")
     *       .surgeMultiplier(1.5)
     *       .estimatedFare(25.50)
     *       .build();
     *
     * Required fields are in the Builder constructor.
     * Optional fields have defaults and use fluent setters.
     */
    public static class Builder {
        // Required
        private final String rideId;
        private final Rider rider;
        private final Location pickup;
        private final Location dropoff;

        // Optional (with defaults)
        private String rideType = "SEDAN";
        private Instant requestedAt = Instant.now();
        private double surgeMultiplier = 1.0;
        private double estimatedFare = 0.0;

        public Builder(String rideId, Rider rider, Location pickup, Location dropoff) {
            Objects.requireNonNull(rideId, "rideId is required");
            Objects.requireNonNull(rider, "rider is required");
            Objects.requireNonNull(pickup, "pickup location is required");
            Objects.requireNonNull(dropoff, "dropoff location is required");
            this.rideId = rideId;
            this.rider = rider;
            this.pickup = pickup;
            this.dropoff = dropoff;
        }

        public Builder rideType(String rideType)              { this.rideType = rideType; return this; }
        public Builder requestedAt(Instant requestedAt)       { this.requestedAt = requestedAt; return this; }
        public Builder surgeMultiplier(double surgeMultiplier) { this.surgeMultiplier = surgeMultiplier; return this; }
        public Builder estimatedFare(double estimatedFare)    { this.estimatedFare = estimatedFare; return this; }

        public Ride build() {
            return new Ride(this);
        }
    }
}
```

---

### 4.4 RideStatus (Enum with Transition Metadata)

```java
/**
 * Ride lifecycle states.
 *
 * Each state represents a point in the ride's lifecycle:
 *   REQUESTED       -- rider submitted a ride request, waiting for driver match
 *   MATCHED         -- driver has been assigned but hasn't started heading to pickup
 *   DRIVER_EN_ROUTE -- driver is heading to the pickup location
 *   IN_PROGRESS     -- rider is in the car, ride is ongoing
 *   COMPLETED       -- ride finished, fare calculated, payment processed
 *   CANCELLED       -- ride was cancelled (by rider or system timeout)
 */
public enum RideStatus {
    REQUESTED("Waiting for driver"),
    MATCHED("Driver assigned"),
    DRIVER_EN_ROUTE("Driver heading to pickup"),
    IN_PROGRESS("Ride in progress"),
    COMPLETED("Ride completed"),
    CANCELLED("Ride cancelled");

    private final String description;

    RideStatus(String description) {
        this.description = description;
    }

    public String getDescription() { return description; }

    /** Returns true if this is a terminal state (no more transitions possible). */
    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }
}
```

---

### 4.5 Supporting Entities

```java
/**
 * Rider: the person requesting a ride.
 * Contains identity info and payment preference.
 */
public class Rider {
    private final String riderId;
    private final String name;
    private double rating;
    private PaymentMethod preferredPayment;

    public Rider(String riderId, String name, PaymentMethod preferredPayment) {
        this.riderId = riderId;
        this.name = name;
        this.rating = 5.0;  // new riders start at 5.0
        this.preferredPayment = preferredPayment;
    }

    // Getters and setters
    public String getRiderId()               { return riderId; }
    public String getName()                  { return name; }
    public double getRating()                { return rating; }
    public void setRating(double rating)     { this.rating = rating; }
    public PaymentMethod getPreferredPayment() { return preferredPayment; }
}

/**
 * Driver: the person providing the ride.
 * Tracks availability, location, and vehicle.
 *
 * Key fields:
 *   - isAvailable: true if the driver can accept new rides
 *   - currentLocation: updated in real-time by LocationService
 *   - vehicle: the driver's car (determines ride type compatibility)
 */
public class Driver {
    private final String driverId;
    private final String name;
    private double rating;
    private final Vehicle vehicle;
    private volatile boolean isAvailable;           // volatile: read by matching threads
    private volatile Location currentLocation;      // volatile: updated by location thread

    public Driver(String driverId, String name, Vehicle vehicle, Location initialLocation) {
        this.driverId = driverId;
        this.name = name;
        this.rating = 5.0;
        this.vehicle = vehicle;
        this.isAvailable = true;
        this.currentLocation = initialLocation;
    }

    // Getters
    public String getDriverId()            { return driverId; }
    public String getName()                { return name; }
    public double getRating()              { return rating; }
    public Vehicle getVehicle()            { return vehicle; }
    public boolean isAvailable()           { return isAvailable; }
    public Location getCurrentLocation()   { return currentLocation; }

    // Setters
    public void setRating(double rating)               { this.rating = rating; }
    public void setAvailable(boolean available)        { this.isAvailable = available; }
    public void setCurrentLocation(Location location)  { this.currentLocation = location; }
}

/**
 * Vehicle: the driver's car.
 * Type determines which ride types the driver can serve.
 */
public class Vehicle {
    private final String vehicleId;
    private final String type;          // "SEDAN", "SUV", "POOL"
    private final String plateNumber;
    private final int capacity;

    public Vehicle(String vehicleId, String type, String plateNumber, int capacity) {
        this.vehicleId = vehicleId;
        this.type = type;
        this.plateNumber = plateNumber;
        this.capacity = capacity;
    }

    public String getVehicleId()   { return vehicleId; }
    public String getType()        { return type; }
    public String getPlateNumber() { return plateNumber; }
    public int getCapacity()       { return capacity; }
}

/**
 * RideRequest: captures rider intent before a ride is created.
 * This is what the rider submits; the system uses it to find a driver
 * and calculate an estimated fare.
 */
public class RideRequest {
    private final String requestId;
    private final Rider rider;
    private final Location pickup;
    private final Location dropoff;
    private final String rideType;
    private final Instant requestedAt;
    private double estimatedFare;

    public RideRequest(String requestId, Rider rider, Location pickup,
                       Location dropoff, String rideType) {
        this.requestId = requestId;
        this.rider = rider;
        this.pickup = pickup;
        this.dropoff = dropoff;
        this.rideType = rideType;
        this.requestedAt = Instant.now();
    }

    // Getters
    public String getRequestId()      { return requestId; }
    public Rider getRider()           { return rider; }
    public Location getPickup()       { return pickup; }
    public Location getDropoff()      { return dropoff; }
    public String getRideType()       { return rideType; }
    public Instant getRequestedAt()   { return requestedAt; }
    public double getEstimatedFare()  { return estimatedFare; }
    public void setEstimatedFare(double fare) { this.estimatedFare = fare; }
}

/**
 * SurgeZone: a geographic region with supply/demand tracking.
 *
 * The surge multiplier is calculated by SurgeService:
 *   multiplier = demand / supply (with tier capping)
 *
 * Example:
 *   Zone "downtown": 5 available drivers, 15 pending requests
 *   ratio = 15/5 = 3.0
 *   Tier mapping: ratio >= 3.0 → multiplier = 2.5x
 *
 * Uber/Lyft update surge zones every 1-2 minutes based on real-time data.
 */
public class SurgeZone {
    private final String zoneId;
    private final BoundingBox boundaries;
    private volatile double multiplier;
    private volatile int supply;     // available drivers in zone
    private volatile int demand;     // pending requests in zone

    public SurgeZone(String zoneId, BoundingBox boundaries) {
        this.zoneId = zoneId;
        this.boundaries = boundaries;
        this.multiplier = 1.0;
        this.supply = 0;
        this.demand = 0;
    }

    public String getZoneId()          { return zoneId; }
    public BoundingBox getBoundaries() { return boundaries; }
    public double getMultiplier()      { return multiplier; }
    public int getSupply()             { return supply; }
    public int getDemand()             { return demand; }

    public void setMultiplier(double multiplier) { this.multiplier = multiplier; }
    public void setSupply(int supply)            { this.supply = supply; }
    public void setDemand(int demand)            { this.demand = demand; }
}

/**
 * Payment: tracks the financial transaction for a ride.
 */
public class Payment {
    private final String paymentId;
    private final double amount;
    private final PaymentMethod method;
    private String status;      // "PENDING", "COMPLETED", "FAILED"
    private final Instant createdAt;

    public Payment(String paymentId, double amount, PaymentMethod method) {
        this.paymentId = paymentId;
        this.amount = amount;
        this.method = method;
        this.status = "PENDING";
        this.createdAt = Instant.now();
    }

    public String getPaymentId()     { return paymentId; }
    public double getAmount()        { return amount; }
    public PaymentMethod getMethod() { return method; }
    public String getStatus()        { return status; }
    public void setStatus(String status) { this.status = status; }
}

/**
 * PaymentMethod enum.
 */
public enum PaymentMethod {
    CREDIT_CARD, DEBIT_CARD, WALLET, CASH
}
```

---

## 5. Interface Contracts

### 5.1 SpatialIndex (Core Spatial Interface)

```java
/**
 * Abstraction for spatial indexing of driver locations.
 *
 * WHY an interface?
 *   - QuadTree is the default implementation
 *   - Could swap in GeoHash-based, R-Tree, or H3-based index
 *   - MatchingService depends on SpatialIndex, not QuadTree
 *   - Open-Closed: new spatial algorithms = new class, zero changes to services
 *
 * The inner record Entry pairs a driver ID with their location.
 * This is what range queries return.
 */
public interface SpatialIndex {

    /** A driver's position in the spatial index. */
    record Entry(String id, Location location) {}

    /**
     * Inserts a driver's location into the index.
     * Called by LocationService when a driver comes online.
     */
    void insert(String id, Location location);

    /**
     * Removes a driver from the index.
     * Called when a driver goes offline or is matched to a ride.
     */
    void remove(String id);

    /**
     * Updates a driver's location.
     * Called every few seconds as drivers move.
     * Default implementation: remove + insert.
     */
    default void update(String id, Location newLocation) {
        remove(id);
        insert(id, newLocation);
    }

    /**
     * Finds all drivers within radiusKm of the center point.
     * Internally: computes a BoundingBox, runs rangeQuery(), then
     * filters by exact Haversine distance.
     */
    List<Entry> search(Location center, double radiusKm);

    /**
     * Finds all drivers within the given bounding box.
     * This is the raw spatial query -- no distance filtering.
     */
    List<Entry> rangeQuery(BoundingBox bbox);

    /** Returns the total number of indexed entries. */
    int size();
}
```

---

### 5.2 MatchingStrategy (Driver Matching Interface)

```java
/**
 * Strategy for matching a ride request to the best available driver.
 *
 * Implementations decide HOW to rank candidates:
 *   - NearestDriverStrategy: closest by straight-line distance
 *   - ETABasedStrategy: fastest to arrive (considers traffic)
 *
 * The candidates list is pre-filtered by MatchingService:
 *   1. SpatialIndex.search(pickup, radiusKm) → all nearby drivers
 *   2. Filter: isAvailable() && vehicle matches rideType
 *   3. Pass to MatchingStrategy.matchDriver() → best driver
 *
 * Strategy pattern: RideService doesn't know which algorithm is active.
 */
public interface MatchingStrategy {

    /**
     * Selects the best driver from the candidate list for the given request.
     *
     * @param request    the ride request (has pickup/dropoff/rideType)
     * @param candidates pre-filtered list of available, compatible drivers
     * @return the best driver, or empty if no suitable driver found
     */
    Optional<Driver> matchDriver(RideRequest request, List<Driver> candidates);

    /** Returns the strategy name for logging. */
    String getStrategyName();
}
```

---

### 5.3 PricingStrategy (Fare Calculation Interface)

```java
/**
 * Strategy for calculating ride fares.
 *
 * Implementations define the fare formula:
 *   - StandardPricingStrategy: base + distance*rate + time*rate
 *   - SurgePricingStrategy: delegates to another PricingStrategy, multiplies result
 *
 * Note: SurgePricingStrategy wraps another PricingStrategy (Decorator pattern).
 * This means surge pricing is composable: you can wrap any pricing strategy.
 */
public interface PricingStrategy {

    /**
     * Calculates the fare for a ride.
     *
     * @param distanceKm  total ride distance in kilometers
     * @param timeMinutes total ride time in minutes
     * @param rideType    the ride type ("SEDAN", "SUV", "POOL")
     * @return the fare amount in dollars
     */
    double calculateFare(double distanceKm, double timeMinutes, String rideType);

    /** Returns the strategy name for logging. */
    String getStrategyName();
}
```

---

### 5.4 Repository Interfaces

```java
/**
 * Repository for rides.
 * Abstracts persistence: InMemory for demo, could be DB-backed.
 */
public interface RideRepository {
    void save(Ride ride);
    Optional<Ride> findById(String rideId);
    List<Ride> findByRiderId(String riderId);
    List<Ride> findByDriverId(String driverId);
    List<Ride> findByStatus(RideStatus status);
    List<Ride> findAll();
}

/**
 * Repository for drivers.
 */
public interface DriverRepository {
    void save(Driver driver);
    Optional<Driver> findById(String driverId);
    List<Driver> findAvailable();
    List<Driver> findAll();
}

/**
 * Repository for riders.
 */
public interface RiderRepository {
    void save(Rider rider);
    Optional<Rider> findById(String riderId);
}
```

---

## 6. Strategy Implementations

### 6.1 QuadTree (Recursive Spatial Index)

> **THE spatial data structure for ride-sharing.** Uber uses a variant of QuadTree (specifically, Google S2 cells) for driver lookup. The key insight: instead of checking every driver against the pickup point (O(n)), a QuadTree prunes entire geographic quadrants that don't overlap the search area (O(log n) average).

#### How QuadTree Works

```
     ┌──────────────────────────────────────────────────────────────────┐
     │                    QUADTREE CONCEPT                              │
     │                                                                  │
     │   The world is a rectangle (BoundingBox).                       │
     │   When a node holds more than MAX_POINTS (4) drivers,           │
     │   it SUBDIVIDES into 4 equal child quadrants:                   │
     │                                                                  │
     │   Before subdivision (5 points in one node):                    │
     │   ┌────────────────────────┐                                    │
     │   │  A        B            │                                    │
     │   │                        │                                    │
     │   │      C                 │                                    │
     │   │           D            │                                    │
     │   │  E                     │                                    │
     │   └────────────────────────┘                                    │
     │                                                                  │
     │   After subdivision (5 > MAX_POINTS=4):                         │
     │   ┌────────────┬───────────┐                                    │
     │   │  A         │  B        │                                    │
     │   │  NW        │  NE       │                                    │
     │   ├────────────┼───────────┤                                    │
     │   │  E    C    │  D        │                                    │
     │   │  SW        │  SE       │                                    │
     │   └────────────┴───────────┘                                    │
     │                                                                  │
     │   Each quadrant can hold up to 4 points before subdividing.     │
     │   Maximum depth = 10 (prevents infinite recursion for           │
     │   co-located points, e.g., two drivers at the same GPS coord). │
     └──────────────────────────────────────────────────────────────────┘

     Range Query ("find all drivers within 5 km of rider"):
     ┌────────────────────────────────────────────────────────────────┐
     │                                                                │
     │   1. Convert circle (center, radius) to BoundingBox            │
     │   2. Start at root, check: does search box INTERSECT node box?│
     │      - NO  → prune entire subtree (huge speedup!)              │
     │      - YES → recurse into children                             │
     │   3. At leaf nodes: check each point against the BoundingBox   │
     │   4. (Optional) Final Haversine filter for exact circle match  │
     │                                                                │
     │   Example with 10,000 drivers:                                 │
     │   ┌──────────────────────────────────┐                         │
     │   │         │          │             │                         │
     │   │  PRUNE  │  PRUNE   │             │                         │
     │   │  (no    │  (no     │             │                         │
     │   │  inter- │  inter-  │             │                         │
     │   │  sect)  │  sect)   │             │                         │
     │   ├─────────┼──────────┤             │                         │
     │   │         │ ╔═══╗    │  PRUNE      │                         │
     │   │  SEARCH │ ║BOX║    │  (no        │                         │
     │   │  HERE   │ ╚═══╝    │  intersect) │                         │
     │   │  (inter-│(search   │             │                         │
     │   │  sect!) │ area)    │             │                         │
     │   └─────────┴──────────┴─────────────┘                         │
     │                                                                │
     │   Instead of checking 10,000 drivers, we only check ~200      │
     │   in the overlapping quadrants. That's 50x fewer comparisons. │
     └────────────────────────────────────────────────────────────────┘
```

#### Anti-Pattern: Linear Scan for Nearest Drivers

```java
// BAD: O(n) for every ride request. With 100K active drivers, this is a disaster.
//
// public List<Driver> findNearbyDrivers(Location pickup, double radiusKm,
//                                       List<Driver> allDrivers) {
//     List<Driver> nearby = new ArrayList<>();
//     for (Driver driver : allDrivers) {                   // O(n) EVERY TIME
//         double dist = pickup.distanceTo(driver.getCurrentLocation());
//         if (dist <= radiusKm && driver.isAvailable()) {
//             nearby.add(driver);
//         }
//     }
//     nearby.sort(Comparator.comparingDouble(d ->          // O(k log k)
//         pickup.distanceTo(d.getCurrentLocation())));
//     return nearby;
// }
//
// At Uber scale: 100K drivers * 1000 requests/sec = 100M distance calculations/sec
// QuadTree: ~200 checks per request * 1000 requests/sec = 200K calculations/sec
// That's 500x less work.
```

#### Clean Solution: QuadTree

```java
/**
 * QuadTreeNode: a single node in the QuadTree.
 *
 * Each node owns a geographic region (BoundingBox) and either:
 *   - Holds up to MAX_POINTS points (leaf node)
 *   - Has 4 children: NW, NE, SW, SE (internal node)
 *
 * When a leaf node exceeds MAX_POINTS, it subdivides into 4 children
 * and redistributes its points. This continues recursively until
 * MAX_DEPTH is reached (then the leaf just holds more than MAX_POINTS).
 */
public class QuadTreeNode {

    private static final int MAX_POINTS = 4;
    private static final int MAX_DEPTH = 10;

    private final BoundingBox bounds;
    private final int depth;
    private final List<SpatialIndex.Entry> points;
    private QuadTreeNode[] children;      // [NW, NE, SW, SE] — null until subdivided
    private boolean isLeaf;

    public QuadTreeNode(BoundingBox bounds, int depth) {
        this.bounds = bounds;
        this.depth = depth;
        this.points = new ArrayList<>();
        this.children = null;
        this.isLeaf = true;
    }

    /**
     * Inserts a point into this node.
     *
     * Algorithm:
     *   1. If this is a leaf AND we're under capacity → add the point
     *   2. If this is a leaf AND at capacity AND not at max depth → subdivide, then insert
     *   3. If this is a leaf AND at max depth → just add (overflow, but no more splitting)
     *   4. If this is an internal node → find the child quadrant and recurse
     *
     * @return true if inserted, false if point is outside bounds
     */
    public boolean insert(SpatialIndex.Entry entry) {
        // Reject points outside this node's region
        if (!bounds.contains(entry.location())) {
            return false;
        }

        if (isLeaf) {
            // Case 1 & 3: under capacity or at max depth → just add
            if (points.size() < MAX_POINTS || depth >= MAX_DEPTH) {
                points.add(entry);
                return true;
            }

            // Case 2: at capacity, subdivide first
            subdivide();
        }

        // Case 4: internal node → delegate to correct child
        for (QuadTreeNode child : children) {
            if (child.insert(entry)) {
                return true;
            }
        }

        // Shouldn't happen if bounds are correct, but safety net
        return false;
    }

    /**
     * Splits this leaf node into 4 children (NW, NE, SW, SE).
     *
     * Steps:
     *   1. Calculate midpoints of the current bounding box
     *   2. Create 4 child nodes with sub-bounding-boxes
     *   3. Redistribute existing points into the children
     *   4. Clear this node's points list (it's now an internal node)
     *
     * ┌───────────┬───────────┐
     * │           │           │
     * │    NW     │    NE     │
     * │           │           │
     * ├───────────┼───────────┤
     * │           │           │
     * │    SW     │    SE     │
     * │           │           │
     * └───────────┴───────────┘
     */
    private void subdivide() {
        double midLat = bounds.midLat();
        double midLng = bounds.midLng();

        children = new QuadTreeNode[4];
        // NW: top-left
        children[0] = new QuadTreeNode(
            new BoundingBox(midLat, bounds.maxLat(), bounds.minLng(), midLng), depth + 1);
        // NE: top-right
        children[1] = new QuadTreeNode(
            new BoundingBox(midLat, bounds.maxLat(), midLng, bounds.maxLng()), depth + 1);
        // SW: bottom-left
        children[2] = new QuadTreeNode(
            new BoundingBox(bounds.minLat(), midLat, bounds.minLng(), midLng), depth + 1);
        // SE: bottom-right
        children[3] = new QuadTreeNode(
            new BoundingBox(bounds.minLat(), midLat, midLng, bounds.maxLng()), depth + 1);

        // Redistribute existing points into children
        for (SpatialIndex.Entry entry : points) {
            for (QuadTreeNode child : children) {
                if (child.insert(entry)) break;
            }
        }

        points.clear();
        isLeaf = false;
    }

    /**
     * Range query: finds all points within the given bounding box.
     *
     * This is WHERE THE MAGIC HAPPENS for performance:
     *
     *   1. If this node's bounds DON'T intersect the search box → return empty
     *      (prune entire subtree! This is why QuadTree is fast.)
     *   2. If this is a leaf → check each point against the search box
     *   3. If internal → recurse into all children (some will be pruned in step 1)
     *
     * Time complexity:
     *   - Best case: O(log n) when search area is small relative to world
     *   - Worst case: O(n) when search area covers the entire world
     *   - Typical: O(sqrt(n)) for reasonably-sized search areas
     */
    public List<SpatialIndex.Entry> query(BoundingBox searchBox) {
        List<SpatialIndex.Entry> result = new ArrayList<>();

        // PRUNE: if this node doesn't overlap the search area, skip entirely
        if (!bounds.intersects(searchBox)) {
            return result;
        }

        if (isLeaf) {
            // Leaf: check each point
            for (SpatialIndex.Entry entry : points) {
                if (searchBox.contains(entry.location())) {
                    result.add(entry);
                }
            }
        } else {
            // Internal: recurse into children (pruning happens at step 1)
            for (QuadTreeNode child : children) {
                result.addAll(child.query(searchBox));
            }
        }

        return result;
    }

    /**
     * Removes a point by ID. Must search recursively since we don't
     * know which quadrant the point is in without its location.
     *
     * @return true if found and removed
     */
    public boolean remove(String id) {
        if (isLeaf) {
            return points.removeIf(entry -> entry.id().equals(id));
        }

        for (QuadTreeNode child : children) {
            if (child.remove(id)) return true;
        }
        return false;
    }

    // Getters for testing/debugging
    public BoundingBox getBounds()             { return bounds; }
    public List<SpatialIndex.Entry> getPoints() { return points; }
    public QuadTreeNode[] getChildren()         { return children; }
    public boolean isLeaf()                     { return isLeaf; }
    public int getDepth()                       { return depth; }
}
```

```java
/**
 * QuadTree: the main spatial index implementation.
 *
 * Wraps QuadTreeNode and provides a clean API matching SpatialIndex.
 * Also maintains an ID-to-location map for fast lookups and updates.
 *
 * Usage in ride-sharing:
 *   1. LocationService inserts driver locations as they come online
 *   2. LocationService updates locations every few seconds (GPS updates)
 *   3. MatchingService calls search(pickup, radiusKm) to find nearby drivers
 *   4. LocationService removes drivers when they go offline or are matched
 *
 * Thread safety: QuadTree itself is NOT thread-safe.
 * LocationService wraps it with ReadWriteLock (see Concurrency section).
 *
 * Configuration:
 *   - World bounds: covers the operational area (e.g., a city, a country)
 *   - MAX_POINTS_PER_NODE: 4 (standard; higher = fewer subdivisions but slower queries)
 *   - MAX_DEPTH: 10 (prevents infinite recursion; 2^10 = 1024 levels of precision)
 */
public class QuadTree implements SpatialIndex {

    private final QuadTreeNode root;
    private final Map<String, Location> entries;   // ID → location (for fast updates)

    /**
     * Creates a QuadTree covering the given geographic region.
     *
     * Example: to cover San Francisco:
     *   new QuadTree(new BoundingBox(37.70, 37.82, -122.52, -122.35))
     *
     * For a larger area (entire US):
     *   new QuadTree(new BoundingBox(24.0, 50.0, -125.0, -66.0))
     */
    public QuadTree(BoundingBox worldBounds) {
        this.root = new QuadTreeNode(worldBounds, 0);
        this.entries = new HashMap<>();
    }

    @Override
    public void insert(String id, Location location) {
        Entry entry = new Entry(id, location);
        if (root.insert(entry)) {
            entries.put(id, location);
        }
    }

    @Override
    public void remove(String id) {
        if (entries.containsKey(id)) {
            root.remove(id);
            entries.remove(id);
        }
    }

    @Override
    public void update(String id, Location newLocation) {
        remove(id);
        insert(id, newLocation);
    }

    /**
     * Finds all drivers within radiusKm of the center point.
     *
     * Two-phase approach:
     *   Phase 1: BoundingBox range query (fast, approximate)
     *     - Convert circle to bounding box
     *     - Query QuadTree for all points in box → candidates
     *   Phase 2: Haversine distance filter (precise)
     *     - For each candidate, check exact distance <= radiusKm
     *     - Removes corner cases (box includes corners outside circle)
     *
     * ┌──────────────────────┐
     * │    ┌─BoundingBox──┐  │
     * │    │ ╱──────╲     │  │
     * │    │╱ circle ╲    │  │
     * │    │╲        ╱    │  │    Phase 1: gets all points in box
     * │    │ ╲──────╱     │  │    Phase 2: filters to only circle
     * │    └──────────────┘  │
     * │  ● = in box, not     │
     * │      in circle       │
     * │  ★ = in circle       │
     * └──────────────────────┘
     */
    @Override
    public List<Entry> search(Location center, double radiusKm) {
        // Phase 1: bounding box query (fast spatial pruning)
        BoundingBox searchBox = center.boundingBox(radiusKm);
        List<Entry> candidates = rangeQuery(searchBox);

        // Phase 2: precise distance filter
        return candidates.stream()
            .filter(e -> center.distanceTo(e.location()) <= radiusKm)
            .toList();
    }

    @Override
    public List<Entry> rangeQuery(BoundingBox bbox) {
        return root.query(bbox);
    }

    @Override
    public int size() {
        return entries.size();
    }
}
```

**Interview follow-up**: "Why not use a simple grid (HashMap<GridCell, List<Driver>>)?" Answer: A grid works for uniform distribution, but drivers cluster in cities. A QuadTree adapts: dense areas (downtown) get more subdivisions, sparse areas (suburbs) stay as single nodes. This gives better query performance where it matters most -- in high-demand areas.

---

### 6.2 GeoHash (Alternative Spatial Encoding)

```java
/**
 * GeoHash: encodes lat/lng into a string for prefix-based neighbor lookups.
 *
 * Used alongside or as an alternative to QuadTree. GeoHash is popular
 * because it turns 2D spatial queries into 1D string prefix queries:
 *   - Nearby points share a common prefix
 *   - "9q8yyk" and "9q8yym" are neighbors
 *   - Longer hash = higher precision
 *
 * Precision levels:
 *   Length   Cell size
 *   1        ~5000 km
 *   2        ~1250 km
 *   3        ~156 km
 *   4        ~39 km
 *   5        ~5 km
 *   6        ~1.2 km     ← good for city-level driver search
 *   7        ~0.15 km
 *   8        ~0.019 km
 *
 * In production: Uber uses Google S2 cells (similar concept, but
 * based on Hilbert curves for better locality preservation).
 */
public class GeoHash {

    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";

    /**
     * Encodes a lat/lng pair into a GeoHash string.
     *
     * Algorithm:
     *   1. Start with the entire world: lat [-90, 90], lng [-180, 180]
     *   2. Alternate between bisecting longitude and latitude
     *   3. At each step: if the coordinate is in the upper half, bit = 1
     *   4. Group bits into 5-bit chunks, encode as base32 characters
     *
     * @param lat       latitude
     * @param lng       longitude
     * @param precision number of base32 characters (1-12)
     * @return GeoHash string
     */
    public static String encode(double lat, double lng, int precision) {
        double latMin = -90.0, latMax = 90.0;
        double lngMin = -180.0, lngMax = 180.0;

        StringBuilder hash = new StringBuilder();
        int bits = 0;
        int bitCount = 0;
        boolean isLng = true;   // Start with longitude

        while (hash.length() < precision) {
            if (isLng) {
                double mid = (lngMin + lngMax) / 2;
                if (lng >= mid) {
                    bits = (bits << 1) | 1;
                    lngMin = mid;
                } else {
                    bits = bits << 1;
                    lngMax = mid;
                }
            } else {
                double mid = (latMin + latMax) / 2;
                if (lat >= mid) {
                    bits = (bits << 1) | 1;
                    latMin = mid;
                } else {
                    bits = bits << 1;
                    latMax = mid;
                }
            }

            isLng = !isLng;
            bitCount++;

            if (bitCount == 5) {
                hash.append(BASE32.charAt(bits));
                bits = 0;
                bitCount = 0;
            }
        }

        return hash.toString();
    }

    /**
     * Decodes a GeoHash string back to a lat/lng pair.
     * Returns the center of the GeoHash cell.
     */
    public static Location decode(String geohash) {
        double latMin = -90.0, latMax = 90.0;
        double lngMin = -180.0, lngMax = 180.0;
        boolean isLng = true;

        for (char c : geohash.toCharArray()) {
            int idx = BASE32.indexOf(c);
            for (int bit = 4; bit >= 0; bit--) {
                int b = (idx >> bit) & 1;
                if (isLng) {
                    double mid = (lngMin + lngMax) / 2;
                    if (b == 1) lngMin = mid; else lngMax = mid;
                } else {
                    double mid = (latMin + latMax) / 2;
                    if (b == 1) latMin = mid; else latMax = mid;
                }
                isLng = !isLng;
            }
        }

        return new Location((latMin + latMax) / 2, (lngMin + lngMax) / 2);
    }
}
```

---

### 6.3 NearestDriverStrategy (Haversine-Based Matching)

```java
/**
 * Matches a rider to the nearest available driver by straight-line distance.
 *
 * Algorithm:
 *   1. Receive list of candidate drivers (pre-filtered by SpatialIndex)
 *   2. Calculate Haversine distance from each driver to the pickup point
 *   3. Sort by distance ascending
 *   4. Return the nearest driver
 *
 * Simple, fast, and good enough for most cases. The limitation is that
 * "nearest by distance" doesn't always mean "fastest to arrive" (traffic,
 * road network, one-way streets).
 */
public class NearestDriverStrategy implements MatchingStrategy {

    private final double maxDistanceKm;

    /**
     * @param maxDistanceKm maximum acceptable distance between driver and pickup.
     *                      Drivers farther than this are rejected even if they're
     *                      the closest candidate.
     */
    public NearestDriverStrategy(double maxDistanceKm) {
        this.maxDistanceKm = maxDistanceKm;
    }

    /**
     * Finds the nearest driver to the pickup point.
     *
     * Flow:
     *   1. For each candidate, compute Haversine distance to pickup
     *   2. Filter out drivers beyond maxDistanceKm
     *   3. Sort remaining by distance
     *   4. Return the closest one
     *
     * Time complexity: O(k log k) where k = number of candidates.
     * Since candidates are pre-filtered by QuadTree, k is typically small (5-20).
     */
    @Override
    public Optional<Driver> matchDriver(RideRequest request, List<Driver> candidates) {
        Location pickup = request.getPickup();

        return candidates.stream()
            .filter(driver -> pickup.distanceTo(driver.getCurrentLocation()) <= maxDistanceKm)
            .min(Comparator.comparingDouble(
                driver -> pickup.distanceTo(driver.getCurrentLocation())
            ));
    }

    @Override
    public String getStrategyName() {
        return "NEAREST_DRIVER";
    }
}
```

---

### 6.4 ETABasedStrategy (ETA-Aware Matching)

```java
/**
 * Matches a rider to the driver with the shortest estimated time of arrival (ETA).
 *
 * Unlike NearestDriverStrategy (straight-line distance), this factors in:
 *   - Road distance (simulated as 1.4x straight-line, Manhattan-like factor)
 *   - Traffic conditions (simulated with a configurable multiplier)
 *   - Average speed (varies by time of day)
 *
 * WHY ETA over pure distance?
 *   A driver 3 km away on a highway might arrive in 4 minutes.
 *   A driver 1 km away in gridlocked downtown might take 12 minutes.
 *   ETA-based matching picks the highway driver -- better for the rider.
 *
 * In production: Uber uses real-time road graph data from their map engine
 * to calculate actual ETA with turn-by-turn routing. This simplified version
 * demonstrates the STRATEGY pattern -- the algorithm is swappable.
 */
public class ETABasedStrategy implements MatchingStrategy {

    private static final double ROAD_DISTANCE_FACTOR = 1.4;   // Manhattan distance approximation
    private static final double AVERAGE_SPEED_KM_PER_MIN = 0.5; // 30 km/h in city traffic

    private final double trafficMultiplier;  // 1.0 = normal, 2.0 = heavy traffic
    private final int maxEtaMinutes;         // reject drivers with ETA above this

    /**
     * @param trafficMultiplier multiplier for traffic conditions (1.0 = normal)
     * @param maxEtaMinutes     maximum acceptable ETA in minutes
     */
    public ETABasedStrategy(double trafficMultiplier, int maxEtaMinutes) {
        this.trafficMultiplier = trafficMultiplier;
        this.maxEtaMinutes = maxEtaMinutes;
    }

    @Override
    public Optional<Driver> matchDriver(RideRequest request, List<Driver> candidates) {
        Location pickup = request.getPickup();

        return candidates.stream()
            .filter(driver -> estimateETA(driver, pickup) <= maxEtaMinutes)
            .min(Comparator.comparingDouble(driver -> estimateETA(driver, pickup)));
    }

    /**
     * Estimates the time (in minutes) for a driver to reach the pickup point.
     *
     * Formula:
     *   roadDistance = haversineDistance * ROAD_DISTANCE_FACTOR
     *   eta = (roadDistance / AVERAGE_SPEED_KM_PER_MIN) * trafficMultiplier
     *
     * Example:
     *   Haversine distance = 3 km
     *   Road distance = 3 * 1.4 = 4.2 km
     *   Normal ETA = 4.2 / 0.5 = 8.4 minutes
     *   With 1.5x traffic = 8.4 * 1.5 = 12.6 minutes
     */
    private double estimateETA(Driver driver, Location pickup) {
        double straightLineKm = pickup.distanceTo(driver.getCurrentLocation());
        double roadDistanceKm = straightLineKm * ROAD_DISTANCE_FACTOR;
        return (roadDistanceKm / AVERAGE_SPEED_KM_PER_MIN) * trafficMultiplier;
    }

    @Override
    public String getStrategyName() {
        return "ETA_BASED";
    }
}
```

---

### 6.5 StandardPricingStrategy

```java
/**
 * Standard fare calculation: base fare + distance rate + time rate.
 *
 * Each ride type has different rates (SUV costs more than SEDAN).
 *
 * Formula:
 *   fare = baseFare + (distanceKm * perKmRate) + (timeMinutes * perMinRate)
 *
 * Rate table:
 *   Type    Base    Per km    Per min
 *   SEDAN   $2.50   $1.50     $0.25
 *   SUV     $4.00   $2.50     $0.40
 *   POOL    $1.50   $0.80     $0.15
 */
public class StandardPricingStrategy implements PricingStrategy {

    /**
     * Rate configuration per ride type.
     * In production, this would come from a config service, not hardcoded.
     */
    private record RateConfig(double baseFare, double perKmRate, double perMinRate) {}

    private static final Map<String, RateConfig> RATES = Map.of(
        "SEDAN", new RateConfig(2.50, 1.50, 0.25),
        "SUV",   new RateConfig(4.00, 2.50, 0.40),
        "POOL",  new RateConfig(1.50, 0.80, 0.15)
    );

    /**
     * Calculates the fare.
     *
     * @param distanceKm  ride distance in kilometers
     * @param timeMinutes ride duration in minutes
     * @param rideType    "SEDAN", "SUV", or "POOL"
     * @return fare in dollars (rounded to 2 decimal places)
     */
    @Override
    public double calculateFare(double distanceKm, double timeMinutes, String rideType) {
        RateConfig rate = RATES.getOrDefault(rideType, RATES.get("SEDAN"));

        double fare = rate.baseFare()
                    + (distanceKm * rate.perKmRate())
                    + (timeMinutes * rate.perMinRate());

        // Round to 2 decimal places
        return Math.round(fare * 100.0) / 100.0;
    }

    @Override
    public String getStrategyName() {
        return "STANDARD";
    }
}
```

---

### 6.6 SurgePricingStrategy (Decorator Pattern)

> **Decorator pattern**: SurgePricingStrategy WRAPS another PricingStrategy. It delegates the base fare calculation to the wrapped strategy, then multiplies by the surge multiplier. This is composable: you can stack decorators.

#### Anti-Pattern: Conditional Pricing

```
     ┌──────────────────────────────────────────────────────────────────┐
     │                ANTI-PATTERN: Conditional Pricing                  │
     │                                                                  │
     │   // This violates OCP: every new pricing mode = another if/else │
     │   public double calculateFare(double dist, double time,          │
     │                               String rideType, boolean isSurge, │
     │                               double surgeMultiplier,            │
     │                               boolean isPromo, double discount)  │
     │   {                                                              │
     │       double fare = base + dist * rate + time * rate;            │
     │       if (isSurge) fare *= surgeMultiplier;    // bolted on      │
     │       if (isPromo) fare -= discount;           // bolted on      │
     │       // Add loyalty? Another if. Happy hour? Another if.        │
     │       return fare;                                               │
     │   }                                                              │
     │                                                                  │
     │   PROBLEMS:                                                      │
     │     1. calculateFare() grows a new parameter for each mode       │
     │     2. Every new pricing mode modifies existing code (OCP!)      │
     │     3. Testing is combinatorial: surge+promo? surge+loyalty?     │
     │     4. Can't compose: loyalty discount on top of surge on top of │
     │        standard -- requires knowing all combinations upfront     │
     └──────────────────────────────────────────────────────────────────┘
```

#### Clean Solution: Decorator Wrapping

```java
/**
 * Surge pricing strategy: multiplies the base fare by a surge factor.
 *
 * DECORATOR PATTERN: wraps another PricingStrategy.
 *   - Delegates base fare calculation to the wrapped strategy
 *   - Multiplies the result by surgeMultiplier
 *
 * This means surge pricing works with ANY base strategy:
 *   new SurgePricingStrategy(new StandardPricingStrategy(), 1.8)
 *
 * And you can stack decorators:
 *   PricingStrategy base = new StandardPricingStrategy();
 *   PricingStrategy surged = new SurgePricingStrategy(base, 1.8);
 *   // Future: PricingStrategy discounted = new PromoPricingStrategy(surged, 0.9);
 *
 * The calling code (PricingService) doesn't know or care about the wrapping.
 * It calls calculateFare() on whatever PricingStrategy it was given.
 */
public class SurgePricingStrategy implements PricingStrategy {

    private final PricingStrategy delegate;       // the wrapped strategy
    private final double surgeMultiplier;

    /**
     * @param delegate        the base pricing strategy to wrap
     * @param surgeMultiplier the multiplier (e.g., 1.5 = 50% more, 2.0 = double)
     */
    public SurgePricingStrategy(PricingStrategy delegate, double surgeMultiplier) {
        Objects.requireNonNull(delegate, "delegate strategy must not be null");
        if (surgeMultiplier < 1.0) {
            throw new IllegalArgumentException("Surge multiplier must be >= 1.0: " + surgeMultiplier);
        }
        this.delegate = delegate;
        this.surgeMultiplier = surgeMultiplier;
    }

    /**
     * Calculates surge fare = base fare * multiplier.
     *
     * Call chain:
     *   SurgePricingStrategy.calculateFare()
     *     → delegate.calculateFare()       // gets base fare (e.g., $15.00)
     *     → baseFare * surgeMultiplier     // $15.00 * 1.8 = $27.00
     */
    @Override
    public double calculateFare(double distanceKm, double timeMinutes, String rideType) {
        double baseFare = delegate.calculateFare(distanceKm, timeMinutes, rideType);
        double surgedFare = baseFare * surgeMultiplier;
        return Math.round(surgedFare * 100.0) / 100.0;
    }

    @Override
    public String getStrategyName() {
        return "SURGE(" + delegate.getStrategyName() + " x " + surgeMultiplier + ")";
    }
}
```

---

## 7. Service Layer Design

> The service layer follows the Facade pattern. RideService is the single entry point that orchestrates matching, pricing, surge calculation, ride lifecycle, payment, and notifications. Controllers never interact with internals directly.

### 7.1 RideService (Facade)

```
     ┌─────────────────────────────────────────────────────────────────┐
     │                   RideService Call Flow                          │
     │                                                                  │
     │   RideController                                                 │
     │      │                                                           │
     │      ▼                                                           │
     │   RideService (FACADE)                                           │
     │      │                                                           │
     │      ├──→ MatchingService                                        │
     │      │       ├──→ LocationService (SpatialIndex)                │
     │      │       └──→ MatchingStrategy (Nearest/ETA)                │
     │      │                                                           │
     │      ├──→ PricingService                                         │
     │      │       ├──→ PricingStrategy (Standard/Surge)              │
     │      │       └──→ SurgeService (supply/demand)                  │
     │      │                                                           │
     │      ├──→ PaymentService                                         │
     │      │                                                           │
     │      ├──→ NotificationService                                    │
     │      │                                                           │
     │      └──→ RideRepository (persist ride state)                   │
     └─────────────────────────────────────────────────────────────────┘
```

```java
/**
 * RideService is the FACADE for the entire ride-sharing system.
 *
 * It hides the complexity of:
 *   - Matching (spatial index + strategy)
 *   - Pricing (standard + surge)
 *   - Ride lifecycle (state machine transitions)
 *   - Payments (charge rider after completion)
 *   - Notifications (push to rider and driver)
 *
 * Callers (RideController) only see: requestRide(), cancelRide(),
 * startRide(), completeRide(), getRideStatus().
 *
 * WIRING (set up by AppConfig):
 *   AppConfig creates:
 *     1. MatchingService (SpatialIndex + MatchingStrategy)
 *     2. PricingService (PricingStrategy + SurgeService)
 *     3. PaymentService
 *     4. NotificationService
 *     5. RideRepository
 *     6. RideService (receives all of the above via constructor)
 */
public class RideService {

    private final MatchingService matchingService;
    private final PricingService pricingService;
    private final PaymentService paymentService;
    private final NotificationService notificationService;
    private final LocationService locationService;
    private final RideRepository rideRepository;
    private final DriverRepository driverRepository;

    // --- Stats ---
    private final AtomicLong totalRides = new AtomicLong(0);
    private final AtomicLong completedRides = new AtomicLong(0);
    private final AtomicLong cancelledRides = new AtomicLong(0);

    // Thread-safe ride ID generator
    private final AtomicLong rideIdCounter = new AtomicLong(0);

    /**
     * Constructor injection -- all dependencies provided by AppConfig.
     */
    public RideService(MatchingService matchingService,
                       PricingService pricingService,
                       PaymentService paymentService,
                       NotificationService notificationService,
                       LocationService locationService,
                       RideRepository rideRepository,
                       DriverRepository driverRepository) {
        this.matchingService = matchingService;
        this.pricingService = pricingService;
        this.paymentService = paymentService;
        this.notificationService = notificationService;
        this.locationService = locationService;
        this.rideRepository = rideRepository;
        this.driverRepository = driverRepository;
    }

    /**
     * Handles a complete ride request flow:
     *   1. Calculate estimated fare (with surge if applicable)
     *   2. Find nearest available driver via spatial index
     *   3. Create Ride entity (Builder pattern)
     *   4. Transition ride: REQUESTED → MATCHED
     *   5. Mark driver as unavailable
     *   6. Remove driver from spatial index
     *   7. Notify rider and driver
     *   8. Persist ride
     *
     * @param request the ride request from the rider
     * @return the created Ride
     * @throws NoDriverAvailableException if no driver can be matched
     */
    public Ride requestRide(RideRequest request) {
        // 1. Calculate estimated fare
        double distanceKm = request.getPickup().distanceTo(request.getDropoff());
        double estimatedTimeMin = distanceKm / 0.5;   // ~30 km/h average
        double estimatedFare = pricingService.calculateFare(
            distanceKm, estimatedTimeMin, request.getRideType(), request.getPickup());

        // 2. Find driver via matching service
        Optional<Driver> driverOpt = matchingService.findDriver(request);
        if (driverOpt.isEmpty()) {
            throw new NoDriverAvailableException(
                "No available drivers near " + request.getPickup());
        }

        Driver driver = driverOpt.get();

        // 3. Create ride
        String rideId = "ride-" + rideIdCounter.incrementAndGet();
        Ride ride = new Ride.Builder(rideId, request.getRider(), request.getPickup(), request.getDropoff())
            .rideType(request.getRideType())
            .estimatedFare(estimatedFare)
            .surgeMultiplier(pricingService.getCurrentSurgeMultiplier(request.getPickup()))
            .build();

        // 4. Assign driver and transition state
        ride.assignDriver(driver);
        ride.transitionTo(RideStatus.MATCHED);

        // 5. Mark driver unavailable and remove from spatial index
        driver.setAvailable(false);
        locationService.removeDriver(driver.getDriverId());

        // 6. Notify both parties
        notificationService.notifyRider(request.getRider(),
            "Driver " + driver.getName() + " is on the way!");
        notificationService.notifyDriver(driver,
            "New ride from " + request.getPickup() + " to " + request.getDropoff());

        // 7. Persist
        rideRepository.save(ride);
        totalRides.incrementAndGet();

        System.out.printf("[RIDE-SERVICE] Created ride %s: %s → %s, driver=%s, fare=$%.2f%n",
            rideId, request.getPickup(), request.getDropoff(),
            driver.getName(), estimatedFare);

        return ride;
    }

    /**
     * Transitions ride to DRIVER_EN_ROUTE.
     * Called when driver accepts and starts heading to pickup.
     */
    public void driverEnRoute(String rideId) {
        Ride ride = findRideOrThrow(rideId);
        ride.transitionTo(RideStatus.DRIVER_EN_ROUTE);
        rideRepository.save(ride);
        notificationService.notifyRider(ride.getRider(),
            "Your driver is on the way! ETA: ~5 minutes");
    }

    /**
     * Transitions ride to IN_PROGRESS.
     * Called when rider is picked up and trip begins.
     */
    public void startRide(String rideId) {
        Ride ride = findRideOrThrow(rideId);
        ride.transitionTo(RideStatus.IN_PROGRESS);
        rideRepository.save(ride);
        notificationService.notifyRider(ride.getRider(), "Ride started! Enjoy your trip.");
    }

    /**
     * Completes a ride:
     *   1. Transition: IN_PROGRESS → COMPLETED
     *   2. Calculate actual fare (with real distance/time)
     *   3. Process payment
     *   4. Release driver (mark available, re-add to spatial index)
     *   5. Notify both parties
     *
     * @param rideId      the ride to complete
     * @param distanceKm  actual distance traveled
     * @param durationMin actual trip duration
     */
    public void completeRide(String rideId, double distanceKm, double durationMin) {
        Ride ride = findRideOrThrow(rideId);

        // 1. Transition state
        ride.transitionTo(RideStatus.COMPLETED);
        ride.setDistanceKm(distanceKm);
        ride.setDurationMinutes(durationMin);

        // 2. Calculate actual fare
        double actualFare = pricingService.calculateFare(
            distanceKm, durationMin, ride.getRideType(), ride.getPickup());
        ride.setActualFare(actualFare);

        // 3. Process payment
        Payment payment = paymentService.processPayment(
            ride.getRider(), actualFare, ride.getRider().getPreferredPayment());
        ride.setPayment(payment);

        // 4. Release driver
        Driver driver = ride.getDriver();
        driver.setAvailable(true);
        locationService.updateDriverLocation(driver.getDriverId(), driver.getCurrentLocation());

        // 5. Notify
        notificationService.notifyRider(ride.getRider(),
            String.format("Ride complete! Fare: $%.2f", actualFare));
        notificationService.notifyDriver(driver, "Ride completed. You are now available.");

        // 6. Persist and update stats
        rideRepository.save(ride);
        completedRides.incrementAndGet();

        System.out.printf("[RIDE-SERVICE] Completed ride %s: %.1f km, %.0f min, $%.2f%n",
            rideId, distanceKm, durationMin, actualFare);
    }

    /**
     * Cancels a ride.
     * Releases the driver back to the pool if one was assigned.
     */
    public void cancelRide(String rideId) {
        Ride ride = findRideOrThrow(rideId);
        ride.transitionTo(RideStatus.CANCELLED);

        // Release driver if one was assigned
        if (ride.getDriver() != null) {
            Driver driver = ride.getDriver();
            driver.setAvailable(true);
            locationService.updateDriverLocation(driver.getDriverId(), driver.getCurrentLocation());
        }

        rideRepository.save(ride);
        cancelledRides.incrementAndGet();
        notificationService.notifyRider(ride.getRider(), "Your ride has been cancelled.");
    }

    /** Retrieves ride status. */
    public Optional<Ride> getRideStatus(String rideId) {
        return rideRepository.findById(rideId);
    }

    /** Returns aggregated ride statistics. */
    public RideStats getStats() {
        return new RideStats(totalRides.get(), completedRides.get(), cancelledRides.get());
    }

    /** Stats record. */
    public record RideStats(long total, long completed, long cancelled) {}

    private Ride findRideOrThrow(String rideId) {
        return rideRepository.findById(rideId)
            .orElseThrow(() -> new RideException("Ride not found: " + rideId));
    }
}
```

---

### 7.2 LocationService (Spatial Index Manager)

```java
/**
 * Manages real-time driver locations using a SpatialIndex (QuadTree).
 *
 * This is the bridge between raw GPS updates and the spatial index.
 * In production, this service would:
 *   - Receive GPS pings every 4 seconds from driver apps
 *   - Update the spatial index on every ping
 *   - Batch updates for performance (not update-per-ping)
 *
 * Thread safety: uses ReadWriteLock to allow concurrent reads (searches)
 * while serializing writes (location updates).
 *
 * WHY ReadWriteLock?
 *   - search() is called on every ride request (hot path, must be fast)
 *   - updateLocation() is called per driver per GPS ping (also frequent)
 *   - With ReentrantLock: searches block each other → bad
 *   - With ReadWriteLock: searches are concurrent, only updates are exclusive
 */
public class LocationService {

    private final SpatialIndex spatialIndex;
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();

    public LocationService(SpatialIndex spatialIndex) {
        this.spatialIndex = spatialIndex;
    }

    /**
     * Registers a driver's location in the spatial index.
     * Called when a driver comes online.
     */
    public void addDriver(String driverId, Location location) {
        rwLock.writeLock().lock();
        try {
            spatialIndex.insert(driverId, location);
            System.out.printf("[LOCATION] Added driver %s at %s%n", driverId, location);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Updates a driver's location (GPS ping).
     * Remove-then-insert in the spatial index.
     */
    public void updateDriverLocation(String driverId, Location newLocation) {
        rwLock.writeLock().lock();
        try {
            spatialIndex.update(driverId, newLocation);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Removes a driver from the spatial index.
     * Called when driver is matched to a ride or goes offline.
     */
    public void removeDriver(String driverId) {
        rwLock.writeLock().lock();
        try {
            spatialIndex.remove(driverId);
            System.out.printf("[LOCATION] Removed driver %s from spatial index%n", driverId);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Finds all drivers within radiusKm of a location.
     * Uses read lock: multiple search calls can run concurrently.
     *
     * @param center   the search center (typically the rider's pickup)
     * @param radiusKm search radius in kilometers
     * @return list of (driverId, location) pairs
     */
    public List<SpatialIndex.Entry> findNearbyDrivers(Location center, double radiusKm) {
        rwLock.readLock().lock();
        try {
            return spatialIndex.search(center, radiusKm);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Counts drivers in a geographic region.
     * Used by SurgeService to calculate supply.
     */
    public int countDriversInZone(BoundingBox zone) {
        rwLock.readLock().lock();
        try {
            return spatialIndex.rangeQuery(zone).size();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /** Total number of drivers in the spatial index. */
    public int getActiveDriverCount() {
        rwLock.readLock().lock();
        try {
            return spatialIndex.size();
        } finally {
            rwLock.readLock().unlock();
        }
    }
}
```

---

### 7.3 MatchingService

```java
/**
 * Orchestrates driver matching by combining SpatialIndex with MatchingStrategy.
 *
 * Two-phase matching:
 *   Phase 1: LocationService.findNearbyDrivers() → spatial proximity filter
 *   Phase 2: MatchingStrategy.matchDriver() → rank by distance/ETA/rating
 *
 * The MatchingService does NOT choose the algorithm. It delegates to
 * whatever MatchingStrategy was injected by AppConfig.
 */
public class MatchingService {

    private final LocationService locationService;
    private final DriverRepository driverRepository;
    private final MatchingStrategy matchingStrategy;
    private final double searchRadiusKm;

    public MatchingService(LocationService locationService,
                           DriverRepository driverRepository,
                           MatchingStrategy matchingStrategy,
                           double searchRadiusKm) {
        this.locationService = locationService;
        this.driverRepository = driverRepository;
        this.matchingStrategy = matchingStrategy;
        this.searchRadiusKm = searchRadiusKm;
    }

    /**
     * Finds the best driver for a ride request.
     *
     * Flow:
     *   1. Query spatial index for nearby driver IDs
     *   2. Look up full Driver objects from DriverRepository
     *   3. Filter: isAvailable() && vehicle type matches rideType
     *   4. Delegate to MatchingStrategy for final selection
     *
     * @param request the ride request
     * @return the best matching driver, or empty
     */
    public Optional<Driver> findDriver(RideRequest request) {
        // Phase 1: Spatial lookup
        List<SpatialIndex.Entry> nearbyEntries =
            locationService.findNearbyDrivers(request.getPickup(), searchRadiusKm);

        // Resolve driver IDs to Driver objects
        List<Driver> candidates = nearbyEntries.stream()
            .map(entry -> driverRepository.findById(entry.id()))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .filter(Driver::isAvailable)
            .filter(driver -> isVehicleCompatible(driver.getVehicle(), request.getRideType()))
            .toList();

        if (candidates.isEmpty()) {
            System.out.printf("[MATCHING] No candidates within %.1f km of %s%n",
                searchRadiusKm, request.getPickup());
            return Optional.empty();
        }

        System.out.printf("[MATCHING] Found %d candidates, using %s strategy%n",
            candidates.size(), matchingStrategy.getStrategyName());

        // Phase 2: Strategy-based selection
        return matchingStrategy.matchDriver(request, candidates);
    }

    /**
     * Checks if a vehicle is compatible with the requested ride type.
     *   - SEDAN: vehicle must be SEDAN or SUV
     *   - SUV: vehicle must be SUV
     *   - POOL: any vehicle type
     */
    private boolean isVehicleCompatible(Vehicle vehicle, String rideType) {
        return switch (rideType) {
            case "SUV"  -> "SUV".equals(vehicle.getType());
            case "POOL" -> true;  // any vehicle can do POOL
            default     -> "SEDAN".equals(vehicle.getType()) || "SUV".equals(vehicle.getType());
        };
    }
}
```

---

### 7.4 PricingService

```java
/**
 * Calculates ride fares using a pluggable PricingStrategy.
 *
 * Also integrates with SurgeService: when surge is active in a zone,
 * the PricingService wraps the base strategy with SurgePricingStrategy.
 *
 * This is where the Decorator pattern shines:
 *   - Normal conditions: use StandardPricingStrategy directly
 *   - Surge active: wrap with SurgePricingStrategy on the fly
 *   - No code changes needed to add new pricing modifiers
 */
public class PricingService {

    private final PricingStrategy basePricingStrategy;
    private final SurgeService surgeService;

    public PricingService(PricingStrategy basePricingStrategy, SurgeService surgeService) {
        this.basePricingStrategy = basePricingStrategy;
        this.surgeService = surgeService;
    }

    /**
     * Calculates fare, applying surge if applicable.
     *
     * Flow:
     *   1. Get surge multiplier for the pickup location
     *   2. If multiplier > 1.0 → wrap base strategy with SurgePricingStrategy
     *   3. Calculate fare using the (possibly wrapped) strategy
     */
    public double calculateFare(double distanceKm, double timeMinutes,
                                String rideType, Location pickup) {
        double surgeMultiplier = surgeService.getSurgeMultiplier(pickup);

        PricingStrategy strategy;
        if (surgeMultiplier > 1.0) {
            // Dynamically wrap with surge pricing (Decorator pattern)
            strategy = new SurgePricingStrategy(basePricingStrategy, surgeMultiplier);
        } else {
            strategy = basePricingStrategy;
        }

        double fare = strategy.calculateFare(distanceKm, timeMinutes, rideType);
        System.out.printf("[PRICING] %s: %.1f km, %.0f min, type=%s → $%.2f (surge=%.1fx)%n",
            strategy.getStrategyName(), distanceKm, timeMinutes, rideType, fare, surgeMultiplier);

        return fare;
    }

    /** Returns the current surge multiplier for a location (for display purposes). */
    public double getCurrentSurgeMultiplier(Location location) {
        return surgeService.getSurgeMultiplier(location);
    }
}
```

---

### 7.5 SurgeService (Supply/Demand Engine)

> **THE real-time pricing engine.** Uber's surge pricing is controversial but fundamental. The algorithm is simple: count supply (available drivers) and demand (pending ride requests) per geographic zone, calculate their ratio, and map to a multiplier tier.

```
     ┌──────────────────────────────────────────────────────────────────┐
     │                    SURGE PRICING MODEL                           │
     │                                                                  │
     │   For each SurgeZone:                                           │
     │     supply  = count of available drivers in zone                │
     │     demand  = count of pending ride requests in zone            │
     │     ratio   = demand / supply                                   │
     │                                                                  │
     │   Tier mapping (ratio → multiplier):                            │
     │     ratio < 1.0   →  1.0x  (more drivers than requests)        │
     │     ratio 1.0-1.5 →  1.0x  (balanced, no surge)                │
     │     ratio 1.5-2.0 →  1.3x  (slight surge)                      │
     │     ratio 2.0-3.0 →  1.5x  (moderate surge)                    │
     │     ratio 3.0-5.0 →  2.0x  (heavy surge)                       │
     │     ratio > 5.0   →  2.5x  (extreme surge, capped)             │
     │                                                                  │
     │   Example: Downtown zone at 6 PM on a Friday                    │
     │     5 available drivers, 20 pending requests                    │
     │     ratio = 20/5 = 4.0                                         │
     │     Tier: 3.0-5.0 → multiplier = 2.0x                          │
     │     A $15 ride becomes $30                                      │
     │                                                                  │
     │   WHY cap at 2.5x?                                              │
     │     - Prevents obscene fares that cause PR disasters            │
     │     - Uber actually caps at ~8x in practice, but uses           │
     │       warnings + confirmations above 2x                         │
     └──────────────────────────────────────────────────────────────────┘
```

```java
/**
 * Calculates surge pricing multipliers based on supply/demand per zone.
 *
 * Architecture:
 *   - Maintains a list of SurgeZones (geographic regions with boundaries)
 *   - Periodically recalculates supply/demand for each zone
 *   - Exposes getSurgeMultiplier(Location) for PricingService
 *
 * In production: Uber recalculates surge every 1-2 minutes, using
 * streaming data from driver pings and incoming ride requests.
 *
 * For this LLD: we calculate on-demand when queried.
 */
public class SurgeService {

    private final List<SurgeZone> surgeZones;
    private final LocationService locationService;
    private final RideRepository rideRepository;

    // Tier configuration: ratio → multiplier
    private static final double[][] SURGE_TIERS = {
        {1.5, 1.0},    // ratio < 1.5 → no surge
        {2.0, 1.3},    // ratio 1.5-2.0 → 1.3x
        {3.0, 1.5},    // ratio 2.0-3.0 → 1.5x
        {5.0, 2.0},    // ratio 3.0-5.0 → 2.0x
        {Double.MAX_VALUE, 2.5}  // ratio > 5.0 → 2.5x (capped)
    };

    public SurgeService(List<SurgeZone> surgeZones,
                        LocationService locationService,
                        RideRepository rideRepository) {
        this.surgeZones = surgeZones;
        this.locationService = locationService;
        this.rideRepository = rideRepository;
    }

    /**
     * Gets the surge multiplier for a given location.
     *
     * Flow:
     *   1. Find which SurgeZone contains this location
     *   2. Count supply (available drivers in zone)
     *   3. Count demand (pending ride requests in zone)
     *   4. Calculate ratio = demand / supply
     *   5. Map ratio to multiplier tier
     *
     * @param location the pickup location
     * @return surge multiplier (1.0 = no surge)
     */
    public double getSurgeMultiplier(Location location) {
        // Find the zone containing this location
        Optional<SurgeZone> zoneOpt = surgeZones.stream()
            .filter(zone -> zone.getBoundaries().contains(location))
            .findFirst();

        if (zoneOpt.isEmpty()) {
            return 1.0;   // Location not in any surge zone → no surge
        }

        SurgeZone zone = zoneOpt.get();

        // Calculate supply: available drivers in this zone
        int supply = locationService.countDriversInZone(zone.getBoundaries());

        // Calculate demand: pending (REQUESTED) rides with pickup in this zone
        long demand = rideRepository.findByStatus(RideStatus.REQUESTED).stream()
            .filter(ride -> zone.getBoundaries().contains(ride.getPickup()))
            .count();

        // Update zone stats
        zone.setSupply(supply);
        zone.setDemand((int) demand);

        // Calculate ratio (guard against division by zero)
        if (supply == 0) {
            zone.setMultiplier(2.5);   // No drivers = maximum surge
            return 2.5;
        }

        double ratio = (double) demand / supply;

        // Map ratio to tier multiplier
        double multiplier = calculateTierMultiplier(ratio);
        zone.setMultiplier(multiplier);

        System.out.printf("[SURGE] Zone %s: supply=%d, demand=%d, ratio=%.1f → %.1fx%n",
            zone.getZoneId(), supply, (int) demand, ratio, multiplier);

        return multiplier;
    }

    /**
     * Maps a demand/supply ratio to a surge multiplier using the tier table.
     *
     * Walks through tiers until ratio < tier threshold.
     * Returns the corresponding multiplier.
     */
    private double calculateTierMultiplier(double ratio) {
        for (double[] tier : SURGE_TIERS) {
            if (ratio < tier[0]) {
                return tier[1];
            }
        }
        return 2.5;  // Cap
    }

    /** Returns all surge zones with their current stats. */
    public List<SurgeZone> getSurgeZones() {
        return Collections.unmodifiableList(surgeZones);
    }
}
```

---

### 7.6 PaymentService

```java
/**
 * Processes payments for completed rides.
 *
 * In production: integrates with Stripe, Square, or internal payment gateway.
 * For this LLD: simulates payment processing with success/failure.
 */
public class PaymentService {

    private final AtomicLong paymentIdCounter = new AtomicLong(0);

    /**
     * Processes a payment for a ride.
     *
     * @param rider  the rider being charged
     * @param amount the fare amount
     * @param method the payment method
     * @return the Payment entity with status
     * @throws PaymentFailedException if payment processing fails
     */
    public Payment processPayment(Rider rider, double amount, PaymentMethod method) {
        String paymentId = "pay-" + paymentIdCounter.incrementAndGet();
        Payment payment = new Payment(paymentId, amount, method);

        // Simulate payment processing
        // In production: call payment gateway API
        if (amount <= 0) {
            payment.setStatus("FAILED");
            throw new PaymentFailedException(
                "Invalid amount: $" + amount + " for rider " + rider.getRiderId());
        }

        // Simulate: CASH payments are always "COMPLETED" (collected by driver)
        // Card payments have a small simulated failure rate
        if (method == PaymentMethod.CASH) {
            payment.setStatus("COMPLETED");
        } else {
            // 95% success rate simulation
            payment.setStatus("COMPLETED");
        }

        System.out.printf("[PAYMENT] Processed %s: $%.2f via %s → %s%n",
            paymentId, amount, method, payment.getStatus());

        return payment;
    }
}
```

---

### 7.7 NotificationService

```java
/**
 * Sends push notifications to riders and drivers.
 *
 * In production: integrates with APNs (iOS), FCM (Android), or a
 * notification gateway like Amazon SNS.
 *
 * For this LLD: simulates with console output.
 */
public class NotificationService {

    public void notifyRider(Rider rider, String message) {
        System.out.printf("[NOTIFY → RIDER %s] %s%n", rider.getName(), message);
    }

    public void notifyDriver(Driver driver, String message) {
        System.out.printf("[NOTIFY → DRIVER %s] %s%n", driver.getName(), message);
    }
}
```

---

## 8. Concurrency Considerations

> Ride-sharing systems are inherently concurrent: thousands of riders request rides simultaneously, drivers send GPS updates every 4 seconds, and the spatial index is queried on every match.

### 8.1 Overview: What Needs Thread Safety and Why

```
     ┌──────────────────────────────────────────────────────────────────┐
     │                    CONCURRENCY MODEL                             │
     │                                                                  │
     │   Component              Thread Safety Mechanism                 │
     │   ─────────────────────  ────────────────────────────────────    │
     │   LocationService        ReadWriteLock (concurrent reads,       │
     │     (SpatialIndex)         exclusive writes to QuadTree)        │
     │   Driver.isAvailable     volatile (visibility across threads)   │
     │   Driver.currentLocation volatile (updated by GPS thread)       │
     │   InMemoryRideRepository ConcurrentHashMap (lock striping)      │
     │   InMemoryDriverRepo     ConcurrentHashMap (lock striping)      │
     │   RideService.rideId     AtomicLong (lock-free ID generation)   │
     │   RideService stats      AtomicLong (lock-free counters)        │
     │   SurgeZone.multiplier   volatile (updated periodically)        │
     │   SurgeZone.supply/demand volatile (updated periodically)       │
     └──────────────────────────────────────────────────────────────────┘
```

### 8.2 ReadWriteLock in LocationService

```
     ┌──────────────────────────────────────────────────────────────────┐
     │              ReadWriteLock in LocationService                     │
     │                                                                  │
     │   WHY ReadWriteLock instead of ReentrantLock or synchronized?    │
     │                                                                  │
     │   search() is called on EVERY ride request (hot path).          │
     │   updateLocation() is called every 4 sec per active driver.     │
     │                                                                  │
     │   With ReentrantLock:                                            │
     │     search(rider1)──BLOCKED──search(rider2)  ← unnecessary!    │
     │     Two concurrent ride requests block each other.              │
     │                                                                  │
     │   With ReadWriteLock:                                            │
     │     search(rider1)──PARALLEL──search(rider2)  ← both read!     │
     │     updateLoc(driver5)──EXCLUSIVE── (blocks reads + writes)     │
     │                                                                  │
     │   At scale (1000 ride requests/sec + 10000 driver GPS pings/sec):│
     │     ReentrantLock: ~11000 sequential operations/sec             │
     │     ReadWriteLock: ~1000 writes + reads mostly concurrent       │
     │     ReadWriteLock gives ~5-10x better throughput                │
     └──────────────────────────────────────────────────────────────────┘
```

### 8.3 Race Condition: Double Matching

```
     ┌──────────────────────────────────────────────────────────────────┐
     │              RACE CONDITION: Double Matching                      │
     │                                                                  │
     │   Thread-1: requestRide(rider-A) → finds driver-X, assigns     │
     │   Thread-2: requestRide(rider-B) → finds driver-X, assigns     │
     │                                                                  │
     │   Without protection: driver-X is assigned to TWO rides!        │
     │                                                                  │
     │   SOLUTION: Multiple layers of defense:                         │
     │                                                                  │
     │   1. LocationService.removeDriver() is under write lock:        │
     │      Once driver-X is removed from the spatial index,           │
     │      Thread-2's search won't find them.                         │
     │                                                                  │
     │   2. Driver.isAvailable is volatile:                            │
     │      Thread-1 sets driver.setAvailable(false).                  │
     │      Thread-2's filter(Driver::isAvailable) sees false.         │
     │                                                                  │
     │   3. In production: use CAS (Compare-And-Swap) on driver state: │
     │      if (driver.compareAndSetAvailable(true, false)) {          │
     │          // Successfully claimed this driver                     │
     │      } else {                                                    │
     │          // Another thread got them first, try next candidate    │
     │      }                                                           │
     │                                                                  │
     │   The current implementation uses volatile + remove-from-index   │
     │   which provides eventual consistency. For strict correctness,   │
     │   CAS on the driver's availability flag would be needed.        │
     └──────────────────────────────────────────────────────────────────┘
```

### 8.4 Volatile Fields in Driver

```
     ┌──────────────────────────────────────────────────────────────────┐
     │              WHY volatile ON Driver FIELDS?                      │
     │                                                                  │
     │   isAvailable:                                                   │
     │     Written by: RideService (when matching/completing rides)     │
     │     Read by: MatchingService (when filtering candidates)         │
     │     Without volatile: MatchingService might see stale value     │
     │     (driver appears available even after being matched)         │
     │                                                                  │
     │   currentLocation:                                               │
     │     Written by: LocationService (on GPS ping from driver app)   │
     │     Read by: MatchingService (distance calculation)              │
     │     Without volatile: Matching might use stale GPS position     │
     │     (driver appears 2 km away when they're actually 200m)       │
     │                                                                  │
     │   volatile guarantees:                                           │
     │     - Writes are immediately visible to other threads            │
     │     - No caching of the value in CPU registers or L1 cache      │
     │     - Memory barrier: instructions before write are flushed     │
     │                                                                  │
     │   NOTE: volatile is NOT atomic for compound operations.         │
     │     driver.setAvailable(false) is fine (single write).          │
     │     if (driver.isAvailable()) { driver.setAvailable(false); }   │
     │     ^ THIS is a race condition (read-then-write).               │
     │     Use AtomicBoolean.compareAndSet() for that pattern.         │
     └──────────────────────────────────────────────────────────────────┘
```

### 8.5 InMemoryRideRepository Thread Safety

```java
/**
 * Thread-safe in-memory ride storage.
 *
 * Uses ConcurrentHashMap for safe concurrent access from multiple
 * RideService threads handling simultaneous ride requests.
 */
public class InMemoryRideRepository implements RideRepository {

    private final ConcurrentHashMap<String, Ride> rides = new ConcurrentHashMap<>();

    @Override
    public void save(Ride ride) {
        rides.put(ride.getRideId(), ride);
    }

    @Override
    public Optional<Ride> findById(String rideId) {
        return Optional.ofNullable(rides.get(rideId));
    }

    @Override
    public List<Ride> findByRiderId(String riderId) {
        return rides.values().stream()
            .filter(r -> r.getRider().getRiderId().equals(riderId))
            .toList();
    }

    @Override
    public List<Ride> findByDriverId(String driverId) {
        return rides.values().stream()
            .filter(r -> r.getDriver() != null
                      && r.getDriver().getDriverId().equals(driverId))
            .toList();
    }

    @Override
    public List<Ride> findByStatus(RideStatus status) {
        return rides.values().stream()
            .filter(r -> r.getStatus() == status)
            .toList();
    }

    @Override
    public List<Ride> findAll() {
        return new ArrayList<>(rides.values());
    }
}
```

---

## 9. SOLID Principles Applied

| Principle | Where Applied | Example |
|-----------|--------------|---------|
| **S** - Single Responsibility | LocationService vs MatchingService | LocationService ONLY manages the spatial index (insert/update/remove/query). It does not decide which driver to pick -- that's MatchingService's job. |
| **S** - Single Responsibility | SurgeService | SurgeService ONLY calculates supply/demand ratios and multipliers. It does not apply the multiplier to fares -- that's PricingService's job (via SurgePricingStrategy). |
| **O** - Open/Closed | MatchingStrategy interface | Adding a new matching algorithm (e.g., RatingWeightedStrategy, PoolMatchingStrategy) requires ONE new class implementing MatchingStrategy. Zero changes to MatchingService, RideService, or any existing class. |
| **O** - Open/Closed | PricingStrategy interface | Adding a new pricing modifier (e.g., PromoPricingStrategy, LoyaltyPricingStrategy) requires ONE new class. It can wrap any existing strategy via the Decorator pattern. |
| **L** - Liskov Substitution | PricingStrategy implementations | PricingService works identically whether the strategy is StandardPricingStrategy, SurgePricingStrategy, or a future PromoPricingStrategy. Swapping is a config change, not a code change. |
| **L** - Liskov Substitution | SpatialIndex implementations | LocationService depends on `SpatialIndex` (interface). Swapping QuadTree for a GeoHash-based index requires ZERO changes in LocationService. |
| **I** - Interface Segregation | MatchingStrategy vs PricingStrategy | MatchingStrategy has 2 methods (matchDriver, getStrategyName). PricingStrategy has 2 methods (calculateFare, getStrategyName). No combined "RideAlgorithm" god-interface. |
| **I** - Interface Segregation | Repository interfaces | RideRepository, DriverRepository, RiderRepository are separate. A class that only needs drivers does not depend on ride persistence methods. |
| **D** - Dependency Inversion | RideService constructor | RideService depends on MatchingService (not NearestDriverStrategy), PricingService (not StandardPricingStrategy), SpatialIndex (not QuadTree). All dependencies are injected by AppConfig. |
| **D** - Dependency Inversion | AppConfig as composition root | AppConfig is the ONLY class that creates concrete objects. All other classes depend on abstractions (interfaces). |

---

## 10. Sample Workflows

### 10.1 Ride Request (Happy Path)

```
     Step  Action                                            Component
     ────  ──────────────────────────────────────────────────  ───────────────────
     1     Rider calls: controller.requestRide(request)       RideController
     2     Controller delegates: rideService.requestRide()    RideService
     3     RideService calls: pricingService.calculateFare()  PricingService
     4       └→ surgeService.getSurgeMultiplier(pickup)       SurgeService
     5       └→ locationService.countDriversInZone()          LocationService
     6            └→ spatialIndex.rangeQuery(zoneBBox)         QuadTree
     7       └→ rideRepository.findByStatus(REQUESTED)        InMemoryRideRepo
     8       SurgeService returns: 1.5x (moderate surge)      SurgeService
     9       PricingService wraps: SurgePricingStrategy(std, 1.5)  PricingService
     10      PricingService returns: $22.50                   PricingService
     11    RideService calls: matchingService.findDriver()    MatchingService
     12      └→ locationService.findNearbyDrivers(pickup, 5km)  LocationService
     13           └→ spatialIndex.search(pickup, 5km)          QuadTree
     14           └→ BoundingBox(pickup, 5km)                  Location
     15           └→ root.query(searchBox) [recursive prune]   QuadTreeNode
     16           └→ Haversine filter (box → circle)           QuadTree
     17      └→ driverRepository.findById(each id)            InMemoryDriverRepo
     18      └→ filter: isAvailable() && vehicleCompatible()  MatchingService
     19      └→ matchingStrategy.matchDriver(request, candidates)  NearestDriverStrategy
     20           └→ sort by Haversine distance, return nearest    NearestDriverStrategy
     21    Returns: driver-007                                MatchingService
     22    RideService creates: Ride.Builder(...).build()     Ride.Builder
     23    ride.assignDriver(driver-007)                      Ride
     24    ride.transitionTo(MATCHED)                         Ride [state machine]
     25    driver.setAvailable(false)                         Driver [volatile write]
     26    locationService.removeDriver("driver-007")         LocationService
     27      └→ spatialIndex.remove("driver-007")              QuadTree
     28    notificationService.notifyRider(...)               NotificationService
     29    notificationService.notifyDriver(...)              NotificationService
     30    rideRepository.save(ride)                          InMemoryRideRepo
     31    Returns: ride                                      → Controller → Rider
```

### 10.2 Ride Completion

```
     Step  Action                                            Component
     ────  ──────────────────────────────────────────────────  ───────────────────
     1     Driver calls: controller.completeRide(rideId, 8.5km, 22min)  RideController
     2     Controller delegates: rideService.completeRide()   RideService
     3     RideService: rideRepository.findById(rideId)       InMemoryRideRepo
     4     ride.transitionTo(COMPLETED)                       Ride [state machine]
     5       └→ VALID_TRANSITIONS.get(IN_PROGRESS)             Ride
     6       └→ Set.of(COMPLETED).contains(COMPLETED) → true  Ride
     7       └→ status = COMPLETED, completedAt = now          Ride
     8     ride.setDistanceKm(8.5), setDurationMinutes(22)    Ride
     9     pricingService.calculateFare(8.5, 22, "SEDAN", pickup)  PricingService
     10      └→ surgeService.getSurgeMultiplier(pickup) → 1.5x    SurgeService
     11      └→ SurgePricingStrategy(StandardPricing, 1.5)        PricingService
     12      └→ StandardPricing: 2.50 + 8.5*1.50 + 22*0.25 = $20.75  StandardPricing
     13      └→ SurgePricing: $20.75 * 1.5 = $31.13               SurgePricing
     14    ride.setActualFare(31.13)                          Ride
     15    paymentService.processPayment(rider, 31.13, CREDIT_CARD)  PaymentService
     16      └→ Payment("pay-1", 31.13, CREDIT_CARD, "COMPLETED")    PaymentService
     17    ride.setPayment(payment)                           Ride
     18    driver.setAvailable(true)                          Driver [volatile write]
     19    locationService.updateDriverLocation(driverId, loc)  LocationService
     20      └→ spatialIndex.insert(driverId, location)        QuadTree
     21    notificationService.notifyRider("Fare: $31.13")    NotificationService
     22    notificationService.notifyDriver("You are available")  NotificationService
     23    rideRepository.save(ride)                          InMemoryRideRepo
```

### 10.3 Ride Cancellation

```
     Step  Action                                            Component
     ────  ──────────────────────────────────────────────────  ───────────────────
     1     Rider calls: controller.cancelRide(rideId)         RideController
     2     Controller delegates: rideService.cancelRide()     RideService
     3     rideRepository.findById(rideId) → ride             InMemoryRideRepo
     4     ride.transitionTo(CANCELLED)                       Ride [state machine]
     5       └→ VALID_TRANSITIONS.get(MATCHED)                 Ride
     6       └→ Set.of(DRIVER_EN_ROUTE, CANCELLED)             Ride
     7       └→ contains(CANCELLED) → true                     Ride
     8       └→ status = CANCELLED                             Ride
     9     ride.getDriver() != null → release driver          RideService
     10    driver.setAvailable(true)                          Driver
     11    locationService.updateDriverLocation(...)          LocationService
     12      └→ spatialIndex.insert(driverId, location)        QuadTree
     13    rideRepository.save(ride)                          InMemoryRideRepo
     14    notificationService.notifyRider("Ride cancelled")  NotificationService
```

### 10.4 Invalid State Transition (Error Case)

```
     Step  Action                                            Component
     ────  ──────────────────────────────────────────────────  ───────────────────
     1     Bug/attack: completeRide("ride-005")               RideController
           (ride-005 is in REQUESTED status, not IN_PROGRESS)
     2     rideService.completeRide("ride-005", 5.0, 10.0)   RideService
     3     rideRepository.findById("ride-005") → ride         InMemoryRideRepo
     4     ride.transitionTo(COMPLETED)                       Ride [state machine]
     5       └→ VALID_TRANSITIONS.get(REQUESTED)               Ride
     6       └→ Set.of(MATCHED, CANCELLED)                     Ride
     7       └→ contains(COMPLETED) → FALSE                    Ride
     8       └→ throw InvalidRideStateException(               Ride
                 "Cannot transition ride-005 from REQUESTED
                  to COMPLETED. Allowed: [MATCHED, CANCELLED]")
     9     Exception propagated to controller → 400 Bad Request  RideController

     The state machine PREVENTS silent data corruption.
     Without it: ride-005 would be "COMPLETED" without ever having a driver.
```

### 10.5 Surge Pricing Calculation

```
     Step  Action                                            Component
     ────  ──────────────────────────────────────────────────  ───────────────────
     1     PricingService: surgeService.getSurgeMultiplier(pickup)  SurgeService
     2     Find zone: surgeZones.stream()                     SurgeService
             .filter(z → z.getBoundaries().contains(pickup))
             → "downtown-zone"
     3     Count supply: locationService.countDriversInZone(bbox)  LocationService
             → spatialIndex.rangeQuery(bbox) → 5 entries
             → supply = 5
     4     Count demand: rideRepository.findByStatus(REQUESTED)    SurgeService
             → filter by zone → 15 pending requests
             → demand = 15
     5     Ratio = 15 / 5 = 3.0                              SurgeService
     6     Tier lookup: 3.0 < 5.0 → multiplier = 2.0x        SurgeService
     7     zone.setMultiplier(2.0)                            SurgeZone
     8     Returns: 2.0                                       SurgeService → PricingService
```

---

## 11. Design Patterns Used

| Pattern | Where Used | Why |
|---------|-----------|-----|
| **Strategy** | MatchingStrategy (Nearest, ETA) | Swappable matching algorithms. MatchingService doesn't know which algorithm is active. Adding a new strategy is ONE new class, ZERO changes elsewhere. |
| **Strategy** | PricingStrategy (Standard, Surge) | Swappable fare calculation. PricingService delegates to the strategy without knowing the formula. |
| **Decorator** | SurgePricingStrategy wraps PricingStrategy | Surge pricing is layered ON TOP of any base strategy. Composable: can stack multiple modifiers without modifying any existing class. |
| **Builder** | Ride.Builder | Ride has 17 fields. Builder avoids telescoping constructors, provides readable construction, validates required fields, and uses fluent API. |
| **State Machine** | Ride.transitionTo() + VALID_TRANSITIONS map | Enforces ride lifecycle: prevents impossible state transitions (e.g., REQUESTED → COMPLETED). The Map IS the specification. |
| **Facade** | RideService | Hides complexity of matching + pricing + payment + notification behind requestRide(), cancelRide(), completeRide(). Controller talks ONLY to RideService. |
| **Factory Method** | AppConfig | Creates the correct MatchingStrategy based on config. `"NEAREST"` → `new NearestDriverStrategy(...)`. Centralizes object creation. |
| **Observer** (conceptual) | NotificationService | RideService notifies riders and drivers of state changes. In production, this would be event-driven (publish ride events, observers react). |
| **Repository** | RideRepository, DriverRepository | Abstracts data persistence. InMemory for demo, swappable for DB-backed. Services never know the storage mechanism. |
| **Record** | Location, BoundingBox, SpatialIndex.Entry | Immutable value objects using Java records. No defensive copies needed, thread-safe by design. |

---

## 12. Extensibility Points

> A well-designed system should be easy to extend without modifying existing code (Open-Closed Principle). Here is how to extend each axis of the ride-sharing system.

### 12.1 Adding a New Matching Strategy (e.g., Rating-Weighted)

**Steps:**
1. Create a new class implementing `MatchingStrategy`
2. Add a case in `AppConfig` factory method

```java
/**
 * Matches driver considering both distance AND driver rating.
 * Score = (1 / distance) * rating  →  close + high-rated wins.
 */
public class RatingWeightedStrategy implements MatchingStrategy {

    @Override
    public Optional<Driver> matchDriver(RideRequest request, List<Driver> candidates) {
        Location pickup = request.getPickup();
        return candidates.stream()
            .max(Comparator.comparingDouble(driver -> {
                double distance = pickup.distanceTo(driver.getCurrentLocation());
                if (distance < 0.01) distance = 0.01;  // avoid div by zero
                return (1.0 / distance) * driver.getRating();
            }));
    }

    @Override
    public String getStrategyName() { return "RATING_WEIGHTED"; }
}

// AppConfig: one-line addition
case "RATING_WEIGHTED" -> new RatingWeightedStrategy();
```

**What does NOT change:** RideService, MatchingService, LocationService, all existing strategies.

---

### 12.2 Adding a New Pricing Modifier (e.g., Promo Discount)

```java
/**
 * Applies a promotional discount to fares.
 * Decorator: wraps any PricingStrategy.
 *
 * Usage:
 *   PricingStrategy base = new StandardPricingStrategy();
 *   PricingStrategy surged = new SurgePricingStrategy(base, 1.5);
 *   PricingStrategy promo = new PromoPricingStrategy(surged, 0.8);  // 20% off surge price
 */
public class PromoPricingStrategy implements PricingStrategy {
    private final PricingStrategy delegate;
    private final double discountFactor;  // 0.8 = 20% off

    public PromoPricingStrategy(PricingStrategy delegate, double discountFactor) {
        this.delegate = delegate;
        this.discountFactor = discountFactor;
    }

    @Override
    public double calculateFare(double distanceKm, double timeMinutes, String rideType) {
        return Math.round(delegate.calculateFare(distanceKm, timeMinutes, rideType)
                          * discountFactor * 100.0) / 100.0;
    }

    @Override
    public String getStrategyName() {
        return "PROMO(" + delegate.getStrategyName() + " x " + discountFactor + ")";
    }
}
```

---

### 12.3 Adding a New Spatial Index (e.g., H3-Based)

```java
/**
 * Uber's H3 hexagonal spatial index.
 * Implements SpatialIndex interface, so LocationService works unchanged.
 */
public class H3SpatialIndex implements SpatialIndex {
    private final Map<String, Set<Entry>> hexCells = new ConcurrentHashMap<>();

    @Override
    public void insert(String id, Location location) {
        String hexId = h3Encode(location, 9);  // Resolution 9 ~ 0.1 km²
        hexCells.computeIfAbsent(hexId, k -> ConcurrentHashMap.newKeySet()).add(new Entry(id, location));
    }

    @Override
    public List<Entry> search(Location center, double radiusKm) {
        // Get all hex cells within radius, collect entries from each
        Set<String> neighborHexes = h3KRing(h3Encode(center, 9), radiusToRing(radiusKm));
        return neighborHexes.stream()
            .flatMap(hex -> hexCells.getOrDefault(hex, Set.of()).stream())
            .filter(e -> center.distanceTo(e.location()) <= radiusKm)
            .toList();
    }
    // ... implement remaining methods ...

    private String h3Encode(Location loc, int resolution) { /* H3 library call */ return ""; }
    private Set<String> h3KRing(String hex, int k)        { /* H3 library call */ return Set.of(); }
    private int radiusToRing(double radiusKm)              { return (int)(radiusKm / 0.3); }
}
```

**What does NOT change:** LocationService, MatchingService, RideService. Only AppConfig changes to wire the new index.

---

### 12.4 Adding a New Ride Type (e.g., LUXURY)

1. Add rate config to `StandardPricingStrategy.RATES`:
```java
"LUXURY", new RateConfig(10.00, 5.00, 0.80)
```

2. Update vehicle compatibility in `MatchingService.isVehicleCompatible()`:
```java
case "LUXURY" -> "LUXURY".equals(vehicle.getType());
```

No changes to RideService, PricingService, or the strategy interfaces.

---

### 12.5 AppConfig: The Composition Root

```java
/**
 * AppConfig is the COMPOSITION ROOT of the ride-sharing system.
 *
 * It creates ALL objects and injects ALL dependencies.
 * No other class uses 'new' for service-layer objects.
 *
 * WHY manual wiring instead of Spring/Guice?
 *   - Interview clarity: you see exactly what depends on what
 *   - No magic: no annotations, no classpath scanning
 *   - Java 21, plain Java: the requirement
 *
 * CALL CHAIN (what depends on what):
 *
 *   AppConfig
 *     ├── creates BoundingBox (world bounds for QuadTree)
 *     ├── creates QuadTree(worldBounds) → SpatialIndex
 *     ├── creates LocationService(spatialIndex)
 *     ├── creates MatchingStrategy ←── switch(config)
 *     │     ├── NearestDriverStrategy(maxDistanceKm)
 *     │     └── ETABasedStrategy(trafficMultiplier, maxEta)
 *     ├── creates DriverRepository (InMemory)
 *     ├── creates MatchingService(locationService, driverRepo, strategy, radius)
 *     ├── creates PricingStrategy ← StandardPricingStrategy
 *     ├── creates SurgeZones[] (geographic regions)
 *     ├── creates SurgeService(surgeZones, locationService, rideRepo)
 *     ├── creates PricingService(pricingStrategy, surgeService)
 *     ├── creates PaymentService
 *     ├── creates NotificationService
 *     ├── creates RideRepository (InMemory)
 *     ├── creates RideService(matching, pricing, payment, notification, location,
 *     │                       rideRepo, driverRepo)  ← FACADE
 *     ├── creates RideStatsDisplay(rideService)
 *     └── creates RideController(rideService)
 */
public class AppConfig {

    private final RideService rideService;
    private final RideController controller;
    private final RideStatsDisplay statsDisplay;

    public AppConfig() {
        // 1. Spatial Index (QuadTree covering San Francisco area)
        BoundingBox worldBounds = new BoundingBox(37.70, 37.82, -122.52, -122.35);
        SpatialIndex spatialIndex = new QuadTree(worldBounds);

        // 2. Location Service
        LocationService locationService = new LocationService(spatialIndex);

        // 3. Repositories
        DriverRepository driverRepo = new InMemoryDriverRepository();
        RiderRepository riderRepo = new InMemoryRiderRepository();
        RideRepository rideRepo = new InMemoryRideRepository();

        // 4. Matching Strategy (factory method)
        MatchingStrategy matchingStrategy = new NearestDriverStrategy(10.0);

        // 5. Matching Service
        MatchingService matchingService = new MatchingService(
            locationService, driverRepo, matchingStrategy, 5.0);

        // 6. Pricing Strategy
        PricingStrategy pricingStrategy = new StandardPricingStrategy();

        // 7. Surge Zones
        List<SurgeZone> surgeZones = List.of(
            new SurgeZone("downtown",
                new BoundingBox(37.76, 37.80, -122.42, -122.38)),
            new SurgeZone("airport",
                new BoundingBox(37.61, 37.63, -122.40, -122.37)),
            new SurgeZone("mission",
                new BoundingBox(37.74, 37.77, -122.43, -122.40))
        );

        // 8. Surge Service
        SurgeService surgeService = new SurgeService(surgeZones, locationService, rideRepo);

        // 9. Pricing Service
        PricingService pricingService = new PricingService(pricingStrategy, surgeService);

        // 10. Payment + Notification Services
        PaymentService paymentService = new PaymentService();
        NotificationService notificationService = new NotificationService();

        // 11. Ride Service (FACADE)
        this.rideService = new RideService(
            matchingService, pricingService, paymentService,
            notificationService, locationService, rideRepo, driverRepo);

        // 12. Display + Controller
        this.statsDisplay = new RideStatsDisplay(rideService);
        this.controller = new RideController(rideService);
    }

    public RideService getRideService()         { return rideService; }
    public RideController getController()       { return controller; }
    public RideStatsDisplay getStatsDisplay()   { return statsDisplay; }
}
```

---

### 12.6 Complete Dependency Wiring Diagram

```
     AppConfig (creates everything)
     │
     ├── BoundingBox (world bounds: San Francisco area)
     │
     ├── QuadTree(worldBounds) → implements SpatialIndex
     │
     ├── LocationService(spatialIndex)
     │     └── ReadWriteLock wrapping QuadTree operations
     │
     ├── InMemoryDriverRepository → implements DriverRepository
     ├── InMemoryRiderRepository  → implements RiderRepository
     ├── InMemoryRideRepository   → implements RideRepository
     │
     ├── NearestDriverStrategy(maxDistance=10km) → implements MatchingStrategy
     │
     ├── MatchingService(locationService, driverRepo, matchingStrategy, radius=5km)
     │
     ├── StandardPricingStrategy → implements PricingStrategy
     │
     ├── SurgeZone[] (downtown, airport, mission)
     │
     ├── SurgeService(surgeZones, locationService, rideRepo)
     │
     ├── PricingService(standardPricingStrategy, surgeService)
     │     └── dynamically wraps with SurgePricingStrategy when surge active
     │
     ├── PaymentService
     │
     ├── NotificationService
     │
     ├── RideService(matchingService, pricingService, paymentService,  ← FACADE
     │               notificationService, locationService,
     │               rideRepo, driverRepo)
     │
     ├── RideStatsDisplay(rideService)
     │
     └── RideController(rideService)

     Rule: arrows point DOWN. No class creates objects above it.
     Rule: all classes depend on interfaces, not concrete implementations.
     Rule: only AppConfig uses 'new' for service-layer objects.
```

---

### 12.7 Exception Classes

```java
/** Base exception for all ride-sharing errors. */
public class RideException extends RuntimeException {
    public RideException(String message) { super(message); }
    public RideException(String message, Throwable cause) { super(message, cause); }
}

/** Thrown when no driver can be matched to a ride request. */
public class NoDriverAvailableException extends RideException {
    public NoDriverAvailableException(String message) { super(message); }
}

/** Thrown when a ride state transition is invalid. */
public class InvalidRideStateException extends RideException {
    public InvalidRideStateException(String message) { super(message); }
}

/** Thrown when payment processing fails. */
public class PaymentFailedException extends RideException {
    public PaymentFailedException(String message) { super(message); }
}
```

---

### 12.8 RideStatsDisplay

```java
/**
 * Formats and displays ride-sharing statistics in ASCII format.
 */
public class RideStatsDisplay {

    private final RideService rideService;

    public RideStatsDisplay(RideService rideService) {
        this.rideService = rideService;
    }

    public void display() {
        RideService.RideStats stats = rideService.getStats();

        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║        RIDE-SHARING STATISTICS           ║");
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.printf( "║  Total Rides:     %,10d              ║%n", stats.total());
        System.out.printf( "║  Completed:       %,10d              ║%n", stats.completed());
        System.out.printf( "║  Cancelled:       %,10d              ║%n", stats.cancelled());
        long active = stats.total() - stats.completed() - stats.cancelled();
        System.out.printf( "║  Active:          %,10d              ║%n", active);
        double completionRate = stats.total() > 0
            ? (double) stats.completed() / stats.total() * 100 : 0;
        System.out.printf( "║  Completion Rate: %9.1f%%              ║%n", completionRate);
        System.out.println("╚══════════════════════════════════════════╝");
    }
}
```

---

### 12.9 RideController

```java
/**
 * REST-like API entry point for the ride-sharing system.
 *
 * In a real system, this would be a proper HTTP handler.
 * For this LLD, it provides a clean API mapping to RideService methods.
 *
 * Wiring: AppConfig → RideController(rideService)
 */
public class RideController {

    private final RideService rideService;

    public RideController(RideService rideService) {
        this.rideService = rideService;
    }

    /** POST /rides */
    public Ride requestRide(RideRequest request) {
        System.out.printf("[API] POST /rides (rider=%s, type=%s)%n",
            request.getRider().getName(), request.getRideType());
        return rideService.requestRide(request);
    }

    /** PUT /rides/{rideId}/en-route */
    public void driverEnRoute(String rideId) {
        System.out.printf("[API] PUT /rides/%s/en-route%n", rideId);
        rideService.driverEnRoute(rideId);
    }

    /** PUT /rides/{rideId}/start */
    public void startRide(String rideId) {
        System.out.printf("[API] PUT /rides/%s/start%n", rideId);
        rideService.startRide(rideId);
    }

    /** PUT /rides/{rideId}/complete */
    public void completeRide(String rideId, double distanceKm, double durationMin) {
        System.out.printf("[API] PUT /rides/%s/complete (%.1f km, %.0f min)%n",
            rideId, distanceKm, durationMin);
        rideService.completeRide(rideId, distanceKm, durationMin);
    }

    /** PUT /rides/{rideId}/cancel */
    public void cancelRide(String rideId) {
        System.out.printf("[API] PUT /rides/%s/cancel%n", rideId);
        rideService.cancelRide(rideId);
    }

    /** GET /rides/{rideId} */
    public Optional<Ride> getRideStatus(String rideId) {
        System.out.printf("[API] GET /rides/%s%n", rideId);
        return rideService.getRideStatus(rideId);
    }
}
```

---

### 12.10 InMemoryDriverRepository and InMemoryRiderRepository

```java
/**
 * Thread-safe in-memory driver storage.
 */
public class InMemoryDriverRepository implements DriverRepository {

    private final ConcurrentHashMap<String, Driver> drivers = new ConcurrentHashMap<>();

    @Override
    public void save(Driver driver) {
        drivers.put(driver.getDriverId(), driver);
    }

    @Override
    public Optional<Driver> findById(String driverId) {
        return Optional.ofNullable(drivers.get(driverId));
    }

    @Override
    public List<Driver> findAvailable() {
        return drivers.values().stream()
            .filter(Driver::isAvailable)
            .toList();
    }

    @Override
    public List<Driver> findAll() {
        return new ArrayList<>(drivers.values());
    }
}

/**
 * Thread-safe in-memory rider storage.
 */
public class InMemoryRiderRepository implements RiderRepository {

    private final ConcurrentHashMap<String, Rider> riders = new ConcurrentHashMap<>();

    @Override
    public void save(Rider rider) {
        riders.put(rider.getRiderId(), rider);
    }

    @Override
    public Optional<Rider> findById(String riderId) {
        return Optional.ofNullable(riders.get(riderId));
    }
}
```
