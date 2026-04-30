# Design Patterns in the Ride-Sharing System (Uber/Lyft)

> Interview-ready reference for a Senior Java developer.
> A ride-sharing platform is a pattern goldmine -- it uses 9 GoF patterns across all three categories.
> For each pattern: ugly anti-pattern code, clean pattern-based code, numbered call chain, and interview one-liner.

---

## Table of Contents

| # | Pattern | GoF Category | Key Class(es) | One-Liner |
|---|---------|-------------|---------------|-----------|
| 1 | Strategy (x2) | Behavioral | `MatchingStrategy` (Nearest, ETA-based), `PricingStrategy` (Standard, Surge) | Swap matching/pricing algorithms without changing service code |
| 2 | Builder | Creational | `Ride.Builder` (12+ fields, complex lifecycle) | Many fields with optional values and lifecycle states -- Builder prevents arg confusion |
| 3 | Factory | Creational | `AppConfig` creates all objects and wires dependencies | Centralized object creation, only class that says `new ConcreteClass()` |
| 4 | Repository | Structural (DDD) | `RideRepository`, `DriverRepository`, `RiderRepository` | Decouple domain from storage (swap to Postgres/Redis) |
| 5 | Facade | Structural | `RideService` orchestrates matching + pricing + payment + notification | One entry point for all ride operations |
| 6 | Observer | Behavioral | `NotificationService` observes ride state changes | Decouple notifications from business logic |
| 7 | State | Behavioral | Ride state machine (REQUESTED -> MATCHED -> EN_ROUTE -> IN_PROGRESS -> COMPLETED) | Ride behavior changes with its state -- no giant if-else chains |
| 8 | Decorator | Structural | `SurgePricingStrategy` wraps `StandardPricingStrategy` | Add surge multiplier transparently without modifying base pricing |
| 9 | Singleton | Creational | `QuadTree` (one spatial index per `LocationService`) | One spatial index to rule them all -- shared across matching strategies |

---

## 1. Strategy Pattern (x2)

### What

Define a family of algorithms, encapsulate each behind a common interface, and make them interchangeable at runtime. This project uses Strategy TWICE -- once for driver matching, once for fare pricing.

### ASCII Diagram -- Both Strategy Hierarchies

```
  MATCHING STRATEGY                          PRICING STRATEGY
  =================                          ================

  +-------------------------+                +-------------------------+
  | <<interface>>           |                | <<interface>>           |
  | MatchingStrategy        |                | PricingStrategy         |
  +-------------------------+                +-------------------------+
  | + findDriver(request,   |                | + calculateFare(        |
  |   availableDrivers):    |                |   pickup, dropoff,      |
  |   Optional<Driver>      |                |   rideType): Money      |
  +----------+--------------+                +----------+--------------+
             |                                          |
       +-----+------+                            +-----+------+
       |            |                             |            |
+------+------+ +---+----------+          +------+------+ +---+----------+
| Nearest     | | ETABased     |          | Standard    | | Surge        |
| Matching    | | Matching     |          | Pricing     | | Pricing      |
| Strategy    | | Strategy     |          | Strategy    | | (DECORATOR)  |
| (Haversine) | | (route+ETA)  |          | (base fare) | | wraps base   |
+-------------+ +--------------+          +-------------+ +--------------+
```

### Ugly Code -- Without Strategy

```java
// ANTI-PATTERN: if-else chain in RideService
// Every new matching algorithm = modify this method = OCP violation
public class RideService {

    private String matchingMode = "NEAREST"; // magic string
    private String pricingMode = "STANDARD"; // another magic string

    public Ride requestRide(RideRequest request) {
        // Matching logic EMBEDDED in the service
        Driver driver;
        if (matchingMode.equals("NEAREST")) {
            double minDist = Double.MAX_VALUE;
            Driver nearest = null;
            for (Driver d : getAllAvailableDrivers()) {
                double dist = haversine(request.getPickup(), d.getLocation());
                if (dist < minDist) {
                    minDist = dist;
                    nearest = d;
                }
            }
            driver = nearest;
        } else if (matchingMode.equals("ETA_BASED")) {
            // 40 lines of ETA calculation inline...
            // Consider traffic, road conditions, driver rating...
            driver = findByETA(request);
        } else if (matchingMode.equals("PRIORITY")) {
            // Priority-based matching for premium riders...
            driver = findByPriority(request);
        }
        // Adding pool matching? Scheduled ride matching? -- more else-if...

        // Same problem for pricing:
        double fare;
        if (pricingMode.equals("STANDARD")) {
            fare = 2.50 + (distance * 1.50) + (duration * 0.25);
        } else if (pricingMode.equals("SURGE")) {
            double multiplier = getSurgeMultiplier(request.getPickup());
            fare = (2.50 + (distance * 1.50) + (duration * 0.25)) * multiplier;
        } else if (pricingMode.equals("FLAT_RATE")) {
            fare = lookupFlatRate(request.getPickup(), request.getDropoff());
        }
        // Adding subscription pricing? Airport flat rate? -- more else-if...

        return createRide(driver, fare);
    }
}
```

**Problems with this approach:**
- `RideService` knows about every matching algorithm's internals (SRP violation)
- Adding a new algorithm requires modifying `RideService` (OCP violation)
- Cannot unit-test matching or pricing in isolation
- Magic strings for mode selection -- no compile-time safety
- Pricing and matching are tangled together in one giant method

### Clean Code -- With Strategy

```java
// --- Strategy 1: Matching ---
public interface MatchingStrategy {
    Optional<Driver> findDriver(RideRequest request, List<Driver> availableDrivers);
}

public class NearestDriverStrategy implements MatchingStrategy {
    private final LocationService locationService;       // uses QuadTree

    @Override
    public Optional<Driver> findDriver(RideRequest request,
                                        List<Driver> availableDrivers) {
        Location pickup = request.getPickupLocation();
        // (1) Query QuadTree for drivers within radius
        List<Driver> nearby = locationService.findNearby(
            pickup.getLatitude(), pickup.getLongitude(), 5.0); // 5km radius
        // (2) Sort by Haversine distance
        return nearby.stream()
            .filter(availableDrivers::contains)
            .min(Comparator.comparingDouble(d ->
                locationService.calculateDistance(pickup, d.getLocation())));
    }
}

public class ETABasedMatchingStrategy implements MatchingStrategy {
    private final LocationService locationService;
    private final ETACalculator etaCalculator;

    @Override
    public Optional<Driver> findDriver(RideRequest request,
                                        List<Driver> availableDrivers) {
        Location pickup = request.getPickupLocation();
        // (1) Query QuadTree for nearby drivers
        List<Driver> nearby = locationService.findNearby(
            pickup.getLatitude(), pickup.getLongitude(), 10.0);
        // (2) Calculate ETA for each, pick smallest
        return nearby.stream()
            .filter(availableDrivers::contains)
            .min(Comparator.comparingDouble(d ->
                etaCalculator.estimateArrival(d.getLocation(), pickup)));
    }
}

// --- Strategy 2: Pricing ---
public interface PricingStrategy {
    Money calculateFare(Location pickup, Location dropoff, RideType rideType);
}

public class StandardPricingStrategy implements PricingStrategy {
    private final LocationService locationService;

    @Override
    public Money calculateFare(Location pickup, Location dropoff,
                                RideType rideType) {
        double distanceKm = locationService.calculateDistance(pickup, dropoff);
        double baseFare = rideType.getBaseFare();          // e.g., $2.50
        double perKmRate = rideType.getPerKmRate();        // e.g., $1.50/km
        double perMinRate = rideType.getPerMinuteRate();    // e.g., $0.25/min
        double estimatedMinutes = distanceKm * 2.0;        // simple estimate

        double total = baseFare + (distanceKm * perKmRate)
                                + (estimatedMinutes * perMinRate);
        return Money.of(total);
    }
}
```

### RideService -- Uses Strategy (Doesn't Know the Algorithm)

```java
public class RideService {
    private final MatchingStrategy matchingStrategy;     // injected
    private final PricingStrategy pricingStrategy;       // injected
    private final RideRepository rideRepository;
    private final NotificationService notificationService;

    public Ride requestRide(RideRequest request) {
        // (1) Calculate fare estimate
        Money fare = pricingStrategy.calculateFare(
            request.getPickupLocation(),
            request.getDropoffLocation(),
            request.getRideType());

        // (2) Find a driver -- we don't know HOW
        List<Driver> available = driverRepository.findAvailable();
        Optional<Driver> driver = matchingStrategy.findDriver(request, available);

        if (driver.isEmpty()) {
            throw new NoDriverAvailableException(request.getPickupLocation());
        }

        // (3) Build the ride
        Ride ride = Ride.builder()
            .rider(request.getRider())
            .driver(driver.get())
            .pickup(request.getPickupLocation())
            .dropoff(request.getDropoffLocation())
            .fare(fare)
            .status(RideStatus.MATCHED)
            .build();

        // (4) Persist and notify
        rideRepository.save(ride);
        notificationService.onRideStatusChanged(ride);

        return ride;
    }
}
```

### Numbered Call Chain -- requestRide() with Nearest Matching

```
  Rider          RideService          NearestDriverStrategy      LocationService       QuadTree
    |                 |                        |                       |                   |
    | (1) requestRide |                        |                       |                   |
    |   (request)     |                        |                       |                   |
    |---------------->|                        |                       |                   |
    |                 | (2) calculateFare      |                       |                   |
    |                 |   (pickup, dropoff,    |                       |                   |
    |                 |    STANDARD)           |                       |                   |
    |                 |----> pricingStrategy   |                       |                   |
    |                 |      returns $23.50    |                       |                   |
    |                 |                        |                       |                   |
    |                 | (3) findDriver         |                       |                   |
    |                 |   (request, available) |                       |                   |
    |                 |----------------------->|                       |                   |
    |                 |                        | (4) findNearby        |                   |
    |                 |                        |   (lat, lng, 5km)     |                   |
    |                 |                        |---------------------->|                   |
    |                 |                        |                       | (5) quadTree      |
    |                 |                        |                       |   .query(bounds)  |
    |                 |                        |                       |------------------>|
    |                 |                        |                       |   [D1, D3, D7]    |
    |                 |                        |                       |<------------------|
    |                 |                        |  nearby=[D1,D3,D7]    |                   |
    |                 |                        |<----------------------|                   |
    |                 |                        |                       |                   |
    |                 |                        | (6) sort by haversine |                   |
    |                 |                        |   distance, pick D3   |                   |
    |                 |                        |                       |                   |
    |                 |  driver=D3             |                       |                   |
    |                 |<-----------------------|                       |                   |
    |                 |                        |                       |                   |
    |                 | (7) Ride.builder()     |                       |                   |
    |                 |   .rider(R1).driver(D3)|                       |                   |
    |                 |   .fare($23.50)        |                       |                   |
    |                 |   .status(MATCHED)     |                       |                   |
    |                 |   .build()             |                       |                   |
    |                 |                        |                       |                   |
    |                 | (8) rideRepo.save(ride)|                       |                   |
    |                 |                        |                       |                   |
    |                 | (9) notificationService|                       |                   |
    |                 |   .onRideStatusChanged |                       |                   |
    |                 |                        |                       |                   |
    |  ride (MATCHED) |                        |                       |                   |
    |<----------------|                        |                       |                   |
```

### Interview One-Liner

> "We use Strategy twice: MatchingStrategy lets us swap Nearest/ETA-based driver matching without touching RideService, and PricingStrategy lets us swap Standard/Surge pricing. Each is independently variable -- classic OCP. During peak hours we can switch to ETABasedMatching + SurgePricing without any code change."

### Cross-Reference

| Project | Strategy Used For |
|---------|------------------|
| 01 - URL Shortener | `EncodingStrategy` (Base62, MD5) |
| 02 - Rate Limiter | `RateLimitStrategy` (Fixed Window, Sliding Window, Token Bucket) |
| 06 - Parking Lot | `ParkingStrategy`, `PricingStrategy`, `PaymentProcessor` (x3) |
| 07 - Distributed Cache | `EvictionStrategy` (LRU, LFU, TTL), `HashingStrategy` (Consistent, Mod) |
| **08 - Ride Sharing** | **`MatchingStrategy` (Nearest, ETA), `PricingStrategy` (Standard, Surge)** |

---

## 2. Builder Pattern

### What

Separate the construction of a complex object from its representation. A `Ride` has 12+ fields, optional values, and a lifecycle that evolves over time. Builder prevents telescoping constructors and makes construction self-documenting.

### ASCII Diagram

```
  +----------------------------+
  | Ride                       |
  +----------------------------+
  | - id: String               |
  | - rider: Rider             |
  | - driver: Driver           |
  | - pickup: Location         |
  | - dropoff: Location        |
  | - fare: Money              |
  | - status: RideStatus       |
  | - rideType: RideType       |
  | - requestTime: Instant     |
  | - matchTime: Instant       |
  | - pickupTime: Instant      |
  | - dropoffTime: Instant     |
  | - rating: Integer          |
  | - surgeMultiplier: double  |
  +----------------------------+
            |
            | uses
            v
  +----------------------------+
  | Ride.Builder               |
  +----------------------------+
  | + rider(Rider): Builder    |
  | + driver(Driver): Builder  |
  | + pickup(Location): Builder|
  | + dropoff(Location):Builder|
  | + fare(Money): Builder     |
  | + status(RideStatus): Bldr |
  | + rideType(RideType): Bldr |
  | + rating(int): Builder     |
  | + build(): Ride            |
  +----------------------------+
```

### Ugly Code -- Without Builder

```java
// ANTI-PATTERN: telescoping constructor with 12+ parameters
// Which parameter is which? What's the order? What's optional?
public class Ride {
    public Ride(String id, Rider rider, Driver driver, Location pickup,
                Location dropoff, double fare, String status, String rideType,
                Instant requestTime, Instant matchTime, Instant pickupTime,
                Instant dropoffTime, Integer rating, double surgeMultiplier) {
        this.id = id;
        this.rider = rider;
        this.driver = driver;
        // ... 10 more assignments
    }
}

// Caller -- which double is fare? which is surge? which Instant is which?
Ride ride = new Ride(
    UUID.randomUUID().toString(),
    rider,
    null,           // driver not assigned yet -- null!
    pickupLoc,
    dropoffLoc,
    23.50,          // is this fare or surge multiplier?
    "REQUESTED",    // magic string -- should be MATCHED?
    "STANDARD",     // another magic string
    Instant.now(),
    null,           // match time -- null!
    null,           // pickup time -- null!
    null,           // dropoff time -- null!
    null,           // rating -- null!
    1.0             // surge multiplier
);
```

**Problems with this approach:**
- 14 parameters -- impossible to remember order
- `null` values scattered for unset optional fields
- Magic strings for status and ride type
- Easy to swap `fare` and `surgeMultiplier` (both are `double`)
- No validation at construction time

### Clean Code -- With Builder

```java
public class Ride {
    private final String id;
    private final Rider rider;
    private final Driver driver;
    private final Location pickupLocation;
    private final Location dropoffLocation;
    private final Money fare;
    private final RideStatus status;
    private final RideType rideType;
    private final Instant requestTime;
    private final Instant matchTime;
    private final Instant pickupTime;
    private final Instant dropoffTime;
    private final Integer rating;
    private final double surgeMultiplier;

    private Ride(Builder builder) {
        this.id = builder.id;
        this.rider = builder.rider;
        // ... all field assignments from builder
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id = UUID.randomUUID().toString();
        private Rider rider;
        private Driver driver;                     // null until matched
        private Location pickupLocation;
        private Location dropoffLocation;
        private Money fare;
        private RideStatus status = RideStatus.REQUESTED;  // sensible default
        private RideType rideType = RideType.STANDARD;     // sensible default
        private Instant requestTime = Instant.now();       // auto-set
        private Instant matchTime;
        private Instant pickupTime;
        private Instant dropoffTime;
        private Integer rating;
        private double surgeMultiplier = 1.0;              // default: no surge

        public Builder rider(Rider rider) {
            this.rider = rider;
            return this;
        }

        public Builder driver(Driver driver) {
            this.driver = driver;
            return this;
        }

        public Builder pickup(Location pickup) {
            this.pickupLocation = pickup;
            return this;
        }

        public Builder dropoff(Location dropoff) {
            this.dropoffLocation = dropoff;
            return this;
        }

        public Builder fare(Money fare) {
            this.fare = fare;
            return this;
        }

        public Builder status(RideStatus status) {
            this.status = status;
            return this;
        }

        public Builder rideType(RideType rideType) {
            this.rideType = rideType;
            return this;
        }

        public Builder rating(int rating) {
            this.rating = rating;
            return this;
        }

        public Builder surgeMultiplier(double multiplier) {
            this.surgeMultiplier = multiplier;
            return this;
        }

        public Ride build() {
            // Validation: required fields
            Objects.requireNonNull(rider, "Rider is required");
            Objects.requireNonNull(pickupLocation, "Pickup location is required");
            Objects.requireNonNull(dropoffLocation, "Dropoff location is required");
            if (rating != null && (rating < 1 || rating > 5)) {
                throw new IllegalArgumentException("Rating must be 1-5");
            }
            return new Ride(this);
        }
    }
}
```

### Numbered Call Chain -- Building a Ride at Request Time

```
  RideService                  Ride.Builder                    Ride
      |                            |                            |
      | (1) Ride.builder()         |                            |
      |--------------------------->| new Builder()               |
      |                            | id=UUID, status=REQUESTED  |
      |                            | time=now(), surge=1.0      |
      |                            |                            |
      | (2) .rider(riderObj)       |                            |
      |--------------------------->| rider = riderObj            |
      |                            |                            |
      | (3) .pickup(pickupLoc)     |                            |
      |--------------------------->| pickupLocation = pickupLoc  |
      |                            |                            |
      | (4) .dropoff(dropoffLoc)   |                            |
      |--------------------------->| dropoffLocation = dropoffLoc|
      |                            |                            |
      | (5) .rideType(POOL)        |                            |
      |--------------------------->| rideType = POOL             |
      |                            |                            |
      | (6) .build()               |                            |
      |--------------------------->|                            |
      |                            | (7) validate: rider!=null  |
      |                            |     pickup!=null           |
      |                            |     dropoff!=null          |
      |                            |                            |
      |                            | (8) new Ride(this)         |
      |                            |--------------------------->|
      |                            |                            |
      |  ride (immutable)          |                            |
      |<--------------------------------------------------------|
```

### Interview One-Liner

> "Ride has 12+ fields with optional values (driver is null until matched, rating is null until completed). Builder gives us named parameters, sensible defaults, validation at build-time, and a self-documenting API -- no more guessing which `double` is fare vs surge multiplier."

### Cross-Reference

| Project | Builder Used For |
|---------|-----------------|
| 01 - URL Shortener | `ShortUrl.Builder` (URL + metadata) |
| 07 - Distributed Cache | `CacheEntry.Builder`, `CacheConfig.Builder` |
| **08 - Ride Sharing** | **`Ride.Builder` (12+ fields, lifecycle states, validation)** |

---

## 3. Factory Pattern

### What

Centralize object creation in one place. `AppConfig` is the only class that knows about concrete implementations. This acts as a "poor man's DI container" -- suitable for interviews where Spring is overkill.

### ASCII Diagram

```
  +---------------------------+
  |       AppConfig           |     <--- The ONLY class that says
  |       (Factory)           |          "new ConcreteX()"
  +---------------------------+
  | + createLocationService() |---> LocationService(new QuadTree())
  | + createMatchingStrategy()|---> NearestDriverStrategy(locationSvc)
  | + createPricingStrategy() |---> SurgePricingStrategy(new StandardPricingStrategy())
  | + createRideRepository()  |---> InMemoryRideRepository()
  | + createDriverRepository()|---> InMemoryDriverRepository()
  | + createRiderRepository() |---> InMemoryRiderRepository()
  | + createNotificationSvc() |---> ConsoleNotificationService()
  | + createRideService()     |---> RideService(matching, pricing, repos, notif)
  | + createPaymentService()  |---> PaymentService(paymentProcessor)
  +---------------------------+
```

### Ugly Code -- Without Factory

```java
// ANTI-PATTERN: "new" scattered everywhere -- tight coupling
public class RideService {

    // Hard-coded concrete classes -- cannot swap implementations
    private final InMemoryRideRepository rideRepo = new InMemoryRideRepository();
    private final InMemoryDriverRepository driverRepo = new InMemoryDriverRepository();
    private final QuadTree quadTree = new QuadTree(-90, -180, 90, 180, 4);
    private final LocationService locationService = new LocationService(quadTree);

    // Want to switch to PostgresRideRepository? Change THIS class.
    // Want to use ETABasedMatching? Change THIS class.
    // Want to mock for tests? Can't -- concrete types everywhere.

    public Ride requestRide(RideRequest request) {
        // Matching algorithm hard-coded
        NearestDriverStrategy matching = new NearestDriverStrategy(locationService);
        // Pricing hard-coded
        StandardPricingStrategy pricing = new StandardPricingStrategy(locationService);
        // Notification hard-coded
        ConsoleNotificationService notifier = new ConsoleNotificationService();
        // ...
    }
}
```

**Problems with this approach:**
- `RideService` is coupled to 6+ concrete classes
- Cannot swap `InMemoryRideRepository` for `PostgresRideRepository` without editing `RideService`
- Cannot unit-test with mocks -- concrete types are hard-coded
- Violates Dependency Inversion Principle (depends on concretions, not abstractions)

### Clean Code -- With Factory

```java
public class AppConfig {

    public LocationService createLocationService() {
        QuadTree quadTree = new QuadTree(-90, -180, 90, 180, 4);
        return new LocationService(quadTree);
    }

    public MatchingStrategy createMatchingStrategy(LocationService locationService) {
        return new NearestDriverStrategy(locationService);
        // Swap: return new ETABasedMatchingStrategy(locationService, etaCalc);
    }

    public PricingStrategy createPricingStrategy(LocationService locationService) {
        PricingStrategy base = new StandardPricingStrategy(locationService);
        // Wrap with surge (Decorator pattern)
        return new SurgePricingStrategy(base, createSurgeService());
    }

    public RideRepository createRideRepository() {
        return new InMemoryRideRepository();
        // Production: return new PostgresRideRepository(dataSource);
    }

    public DriverRepository createDriverRepository() {
        return new InMemoryDriverRepository();
    }

    public RiderRepository createRiderRepository() {
        return new InMemoryRiderRepository();
    }

    public NotificationService createNotificationService() {
        return new ConsoleNotificationService();
        // Production: return new PushNotificationService(firebaseClient);
    }

    public RideService createRideService() {
        LocationService locationService = createLocationService();
        return new RideService(
            createMatchingStrategy(locationService),
            createPricingStrategy(locationService),
            createRideRepository(),
            createDriverRepository(),
            createNotificationService()
        );
    }
}
```

### Numbered Call Chain -- Application Bootstrap

```
  main()                AppConfig              RideService        Dependencies
    |                       |                       |                  |
    | (1) new AppConfig()   |                       |                  |
    |---------------------->|                       |                  |
    |                       |                       |                  |
    | (2) createRideService |                       |                  |
    |---------------------->|                       |                  |
    |                       | (3) createLocationService()              |
    |                       |   -> new QuadTree()                      |
    |                       |   -> new LocationService(quadTree)       |
    |                       |                       |                  |
    |                       | (4) createMatchingStrategy(locSvc)       |
    |                       |   -> new NearestDriverStrategy(locSvc)   |
    |                       |                       |                  |
    |                       | (5) createPricingStrategy(locSvc)        |
    |                       |   -> new StandardPricingStrategy(locSvc) |
    |                       |   -> new SurgePricingStrategy(base,surge)|
    |                       |                       |                  |
    |                       | (6) createRideRepository()               |
    |                       |   -> new InMemoryRideRepository()        |
    |                       |                       |                  |
    |                       | (7) createNotificationService()          |
    |                       |   -> new ConsoleNotificationService()    |
    |                       |                       |                  |
    |                       | (8) new RideService(  |                  |
    |                       |   matching, pricing,  |                  |
    |                       |   rideRepo, driverRepo|                  |
    |                       |   notification)       |                  |
    |                       |---------------------->|                  |
    |                       |                       |                  |
    |  rideService          |                       |                  |
    |<----------------------|                       |                  |
```

### Interview One-Liner

> "AppConfig is our composition root -- the only class that knows about concrete implementations. RideService depends on interfaces (MatchingStrategy, PricingStrategy, RideRepository). To switch from NearestDriver to ETABased matching, we change ONE line in AppConfig."

### Cross-Reference

| Project | Factory Used For |
|---------|-----------------|
| 01 - URL Shortener | `AppConfig` creates encoding strategy + repository |
| 02 - Rate Limiter | `AppConfig` creates rate limit strategy + repository |
| 06 - Parking Lot | `AppConfig` creates parking/pricing/payment strategies |
| 07 - Distributed Cache | `AppConfig` creates eviction/hashing strategies + store |
| **08 - Ride Sharing** | **`AppConfig` creates matching + pricing + repos + services** |

---

## 4. Repository Pattern

### What

Mediate between the domain and data mapping layers using a collection-like interface. The domain layer talks to `RideRepository` (interface), not `InMemoryRideRepository` or `PostgresRideRepository` (implementations). This is DDD's Repository pattern, mapped to GoF's Structural category.

### ASCII Diagram

```
  +-------------------+        +-------------------+        +---------------------+
  | RideService       |------->| <<interface>>     |<-------| InMemoryRide        |
  | (domain logic)    |        | RideRepository    |        | Repository          |
  +-------------------+        +-------------------+        | (HashMap<String,    |
                               | + save(Ride)      |        |  Ride>)             |
  +-------------------+        | + findById(id)    |        +---------------------+
  | MatchingStrategy  |------->| + findByRider(id) |
  | (domain logic)    |        | + findByDriver(id)|        +---------------------+
  +-------------------+        | + findActive()    |<-------| PostgresRide        |
                               +-------------------+        | Repository          |
                                                            | (JDBC/JPA)          |
  Same pattern for:                                         +---------------------+
  - DriverRepository
  - RiderRepository
```

### Ugly Code -- Without Repository

```java
// ANTI-PATTERN: data access logic mixed with business logic
public class RideService {
    private final Map<String, Ride> rides = new ConcurrentHashMap<>();
    private final Map<String, List<Ride>> riderRides = new ConcurrentHashMap<>();
    private final Map<String, List<Ride>> driverRides = new ConcurrentHashMap<>();

    public Ride requestRide(RideRequest request) {
        // Business logic
        Driver driver = matchingStrategy.findDriver(request, available).get();
        Ride ride = buildRide(request, driver);

        // Storage logic MIXED IN -- HashMap indexing details
        rides.put(ride.getId(), ride);
        riderRides.computeIfAbsent(ride.getRider().getId(),
            k -> new ArrayList<>()).add(ride);
        driverRides.computeIfAbsent(ride.getDriver().getId(),
            k -> new ArrayList<>()).add(ride);

        return ride;
    }

    public Ride completeRide(String rideId) {
        Ride ride = rides.get(rideId);           // storage detail
        if (ride == null) throw new RuntimeException("Not found");
        ride.setStatus(RideStatus.COMPLETED);     // business logic
        ride.setDropoffTime(Instant.now());
        rides.put(rideId, ride);                  // storage detail again
        return ride;
    }

    // Want to switch to PostgreSQL? Rewrite EVERY method in RideService.
}
```

**Problems with this approach:**
- Storage logic (HashMap indexing) tangled with business logic (ride lifecycle)
- Cannot swap to PostgreSQL without rewriting business methods
- Cannot test business logic without a real data store
- Multiple index maps (by rider, by driver) are storage concerns leaking into domain

### Clean Code -- With Repository

```java
public interface RideRepository {
    void save(Ride ride);
    Optional<Ride> findById(String id);
    List<Ride> findByRiderId(String riderId);
    List<Ride> findByDriverId(String driverId);
    List<Ride> findByStatus(RideStatus status);
    List<Ride> findAll();
}

public class InMemoryRideRepository implements RideRepository {
    private final Map<String, Ride> store = new ConcurrentHashMap<>();

    @Override
    public void save(Ride ride) {
        store.put(ride.getId(), ride);
    }

    @Override
    public Optional<Ride> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Ride> findByRiderId(String riderId) {
        return store.values().stream()
            .filter(r -> r.getRider().getId().equals(riderId))
            .collect(Collectors.toList());
    }

    @Override
    public List<Ride> findByDriverId(String driverId) {
        return store.values().stream()
            .filter(r -> r.getDriver() != null
                      && r.getDriver().getId().equals(driverId))
            .collect(Collectors.toList());
    }

    @Override
    public List<Ride> findByStatus(RideStatus status) {
        return store.values().stream()
            .filter(r -> r.getStatus() == status)
            .collect(Collectors.toList());
    }
}

// DriverRepository and RiderRepository follow the same pattern
public interface DriverRepository {
    void save(Driver driver);
    Optional<Driver> findById(String id);
    List<Driver> findAvailable();
    List<Driver> findAll();
}
```

### Interview One-Liner

> "Three repositories (Ride, Driver, Rider) decouple domain logic from storage. RideService talks to `RideRepository` interface -- our in-memory HashMap implementation can be swapped for Postgres without touching any business logic. The secondary indexes (by rider, by driver, by status) are storage concerns, hidden behind the interface."

### Cross-Reference

| Project | Repository Used For |
|---------|-------------------|
| 01 - URL Shortener | `UrlRepository` (URL storage) |
| 02 - Rate Limiter | `RateLimitRepository` (counter storage) |
| 06 - Parking Lot | `ParkingLotRepository`, `TicketRepository` |
| 07 - Distributed Cache | `CacheRepository` (cache store) |
| **08 - Ride Sharing** | **`RideRepository`, `DriverRepository`, `RiderRepository`** |

---

## 5. Facade Pattern

### What

Provide a unified interface to a set of interfaces in a subsystem. `RideService` is the facade -- it orchestrates matching, pricing, payment, notification, and persistence. Callers (the API layer) interact with ONE class, not six.

### ASCII Diagram

```
  +------------------+
  | Rider / API      |
  +--------+---------+
           |
           | requestRide(), completeRide(), cancelRide()
           v
  +--------------------------------------------------+
  |                RideService (FACADE)               |
  |                                                    |
  |  Orchestrates 6 subsystems:                        |
  |  +----------------+  +-------------------+         |
  |  | Matching       |  | Pricing           |         |
  |  | Strategy       |  | Strategy          |         |
  |  +----------------+  +-------------------+         |
  |  +----------------+  +-------------------+         |
  |  | Ride           |  | Driver            |         |
  |  | Repository     |  | Repository        |         |
  |  +----------------+  +-------------------+         |
  |  +----------------+  +-------------------+         |
  |  | Notification   |  | Payment           |         |
  |  | Service        |  | Service           |         |
  |  +----------------+  +-------------------+         |
  +--------------------------------------------------+
```

### Ugly Code -- Without Facade

```java
// ANTI-PATTERN: caller must orchestrate all subsystems
public class RideController {
    private final LocationService locationService;
    private final NearestDriverStrategy matching;
    private final StandardPricingStrategy pricing;
    private final InMemoryRideRepository rideRepo;
    private final InMemoryDriverRepository driverRepo;
    private final ConsoleNotificationService notifier;
    private final PaymentService payment;

    public Ride handleRideRequest(RideRequest request) {
        // Caller coordinates everything -- 7 steps, 7 dependencies
        List<Driver> nearby = locationService.findNearby(
            request.getPickupLocation().getLatitude(),
            request.getPickupLocation().getLongitude(), 5.0);
        List<Driver> available = driverRepo.findAvailable();
        nearby.retainAll(available);

        Optional<Driver> driver = matching.findDriver(request, nearby);
        if (driver.isEmpty()) throw new RuntimeException("No driver");

        Money fare = pricing.calculateFare(
            request.getPickupLocation(),
            request.getDropoffLocation(),
            request.getRideType());

        Ride ride = Ride.builder()
            .rider(request.getRider())
            .driver(driver.get())
            .fare(fare)
            .build();

        rideRepo.save(ride);
        driver.get().setAvailable(false);
        driverRepo.save(driver.get());
        notifier.onRideStatusChanged(ride);
        // If any step fails, partial state corruption

        return ride;
    }
}
```

**Problems with this approach:**
- Controller depends on 7 classes -- knows too much
- Orchestration logic duplicated across every endpoint (cancel, complete, rate)
- No transaction boundary -- partial failures leave inconsistent state
- Controller is doing business logic, not just HTTP handling

### Clean Code -- With Facade

```java
public class RideService {  // FACADE
    private final MatchingStrategy matchingStrategy;
    private final PricingStrategy pricingStrategy;
    private final RideRepository rideRepository;
    private final DriverRepository driverRepository;
    private final NotificationService notificationService;

    // Facade method: one call does it all
    public Ride requestRide(RideRequest request) {
        Money fare = pricingStrategy.calculateFare(
            request.getPickupLocation(),
            request.getDropoffLocation(),
            request.getRideType());

        List<Driver> available = driverRepository.findAvailable();
        Optional<Driver> driver = matchingStrategy.findDriver(request, available);

        if (driver.isEmpty()) {
            throw new NoDriverAvailableException(request.getPickupLocation());
        }

        Ride ride = Ride.builder()
            .rider(request.getRider())
            .driver(driver.get())
            .pickup(request.getPickupLocation())
            .dropoff(request.getDropoffLocation())
            .fare(fare)
            .status(RideStatus.MATCHED)
            .build();

        driver.get().setAvailable(false);
        driverRepository.save(driver.get());
        rideRepository.save(ride);
        notificationService.onRideStatusChanged(ride);

        return ride;
    }

    // Another facade method
    public Ride completeRide(String rideId) {
        Ride ride = rideRepository.findById(rideId)
            .orElseThrow(() -> new RideNotFoundException(rideId));

        ride.setStatus(RideStatus.COMPLETED);
        ride.setDropoffTime(Instant.now());

        ride.getDriver().setAvailable(true);
        driverRepository.save(ride.getDriver());
        rideRepository.save(ride);
        notificationService.onRideStatusChanged(ride);

        return ride;
    }

    // Another facade method
    public Ride cancelRide(String rideId) {
        Ride ride = rideRepository.findById(rideId)
            .orElseThrow(() -> new RideNotFoundException(rideId));

        ride.setStatus(RideStatus.CANCELLED);

        if (ride.getDriver() != null) {
            ride.getDriver().setAvailable(true);
            driverRepository.save(ride.getDriver());
        }
        rideRepository.save(ride);
        notificationService.onRideStatusChanged(ride);

        return ride;
    }
}
```

### Numbered Call Chain -- completeRide() Flow

```
  Rider           RideService           RideRepository      DriverRepository     NotificationSvc
    |                  |                      |                    |                    |
    | (1) complete     |                      |                    |                    |
    |   Ride("ride-1") |                      |                    |                    |
    |----------------->|                      |                    |                    |
    |                  | (2) findById         |                    |                    |
    |                  |   ("ride-1")         |                    |                    |
    |                  |--------------------->|                    |                    |
    |                  |  ride                |                    |                    |
    |                  |<---------------------|                    |                    |
    |                  |                      |                    |                    |
    |                  | (3) ride.setStatus   |                    |                    |
    |                  |   (COMPLETED)        |                    |                    |
    |                  | (4) ride.setDropoff  |                    |                    |
    |                  |   Time(now())        |                    |                    |
    |                  |                      |                    |                    |
    |                  | (5) driver.set       |                    |                    |
    |                  |   Available(true)    |                    |                    |
    |                  | (6) driverRepo       |                    |                    |
    |                  |   .save(driver)      |                    |                    |
    |                  |----------------------------------------->|                    |
    |                  |                      |                    |                    |
    |                  | (7) rideRepo         |                    |                    |
    |                  |   .save(ride)        |                    |                    |
    |                  |--------------------->|                    |                    |
    |                  |                      |                    |                    |
    |                  | (8) notificationSvc  |                    |                    |
    |                  |   .onRideStatus      |                    |                    |
    |                  |   Changed(ride)      |                    |                    |
    |                  |---------------------------------------------------------->|
    |                  |                      |                    |                    |
    |  ride(COMPLETED) |                      |                    |                    |
    |<-----------------|                      |                    |                    |
```

### Interview One-Liner

> "RideService is the facade -- it orchestrates matching, pricing, persistence, driver state management, and notifications behind three simple methods: requestRide(), completeRide(), cancelRide(). The API layer calls one method instead of coordinating six subsystems."

### Cross-Reference

| Project | Facade Used For |
|---------|----------------|
| 01 - URL Shortener | `UrlShortenerService` wraps encoding + storage + validation |
| 02 - Rate Limiter | `RateLimiterService` wraps strategy + storage |
| 06 - Parking Lot | `ParkingService` wraps spot allocation + ticketing + payment |
| 07 - Distributed Cache | `CacheService` wraps store + eviction + stats + hashing |
| **08 - Ride Sharing** | **`RideService` wraps matching + pricing + repos + notification** |

---

## 6. Observer Pattern

### What

Define a one-to-many dependency between objects so that when one object changes state, all its dependents are notified automatically. `NotificationService` observes ride state changes -- when a ride moves to MATCHED, EN_ROUTE, COMPLETED, etc., the appropriate parties are notified.

### ASCII Diagram

```
  +--------------------+        notifies        +------------------------+
  | RideService        |----------------------->| <<interface>>          |
  | (Subject)          |                        | NotificationService    |
  +--------------------+                        | (Observer)             |
  | requestRide()      |                        +------------------------+
  | completeRide()     |                        | + onRideStatusChanged  |
  | cancelRide()       |                        |   (Ride ride)          |
  +--------------------+                        +----------+-------------+
                                                           |
                                                     +-----+------+
                                                     |            |
                                              +------+------+ +---+------------------+
                                              | Console      | | Push                 |
                                              | Notification | | Notification         |
                                              | Service      | | Service              |
                                              | (println)    | | (Firebase/APNs)      |
                                              +-------------+ +----------------------+
```

### Ugly Code -- Without Observer

```java
// ANTI-PATTERN: notification logic scattered across every method
public class RideService {

    public Ride requestRide(RideRequest request) {
        // ... matching and ride creation ...
        ride.setStatus(RideStatus.MATCHED);

        // Notification logic EMBEDDED in business method
        System.out.println("Sending push to rider: Driver is on the way!");
        System.out.println("Sending push to driver: New ride request!");
        System.out.println("Sending SMS to rider: " + ride.getDriver().getName());
        System.out.println("Sending email receipt estimate: $" + ride.getFare());
        // 4 notification channels, hard-coded in business method
    }

    public Ride completeRide(String rideId) {
        // ... completion logic ...
        ride.setStatus(RideStatus.COMPLETED);

        // Same notification mess, duplicated
        System.out.println("Sending push to rider: Ride complete!");
        System.out.println("Sending push to driver: Ride complete!");
        System.out.println("Sending email receipt: $" + ride.getFare());
        // Add analytics tracking? Add ETA notifications? Keep adding here...
    }

    public Ride cancelRide(String rideId) {
        // ... cancellation logic ...
        ride.setStatus(RideStatus.CANCELLED);

        // AGAIN -- duplicated notification code
        System.out.println("Sending push to rider: Ride cancelled");
        if (ride.getDriver() != null) {
            System.out.println("Sending push to driver: Ride cancelled");
        }
    }
}
```

**Problems with this approach:**
- Notification logic duplicated across 3+ methods
- Adding a new notification channel (SMS, analytics) requires modifying every business method
- Cannot disable notifications for testing
- SRP violation -- RideService handles both ride logic AND notification logic

### Clean Code -- With Observer

```java
public interface NotificationService {
    void onRideStatusChanged(Ride ride);
}

public class ConsoleNotificationService implements NotificationService {

    @Override
    public void onRideStatusChanged(Ride ride) {
        switch (ride.getStatus()) {
            case MATCHED:
                notifyRiderDriverAssigned(ride);
                notifyDriverNewRide(ride);
                break;
            case EN_ROUTE:
                notifyRiderDriverEnRoute(ride);
                break;
            case IN_PROGRESS:
                notifyRiderTripStarted(ride);
                break;
            case COMPLETED:
                notifyBothRideComplete(ride);
                break;
            case CANCELLED:
                notifyCancellation(ride);
                break;
        }
    }

    private void notifyRiderDriverAssigned(Ride ride) {
        System.out.printf("[NOTIFICATION] Rider %s: Driver %s is on the way! ETA: 5 min%n",
            ride.getRider().getName(), ride.getDriver().getName());
    }

    private void notifyDriverNewRide(Ride ride) {
        System.out.printf("[NOTIFICATION] Driver %s: New ride from %s to %s%n",
            ride.getDriver().getName(),
            ride.getPickupLocation(), ride.getDropoffLocation());
    }

    private void notifyBothRideComplete(Ride ride) {
        System.out.printf("[NOTIFICATION] Rider %s: Trip complete! Fare: %s%n",
            ride.getRider().getName(), ride.getFare());
        System.out.printf("[NOTIFICATION] Driver %s: Trip complete! Earnings: %s%n",
            ride.getDriver().getName(), ride.getFare());
    }

    private void notifyCancellation(Ride ride) {
        System.out.printf("[NOTIFICATION] Ride %s cancelled%n", ride.getId());
    }
}
```

### Numbered Call Chain -- Notification on Ride Completion

```
  RideService            NotificationService          Console/Push/SMS
      |                         |                           |
      | (1) completeRide()      |                           |
      |   ride.setStatus        |                           |
      |   (COMPLETED)           |                           |
      |                         |                           |
      | (2) onRideStatusChanged |                           |
      |   (ride)                |                           |
      |------------------------>|                           |
      |                         | (3) switch(COMPLETED)     |
      |                         |                           |
      |                         | (4) notifyRider           |
      |                         |   "Trip complete!         |
      |                         |    Fare: $23.50"          |
      |                         |-------------------------->|
      |                         |                           |
      |                         | (5) notifyDriver          |
      |                         |   "Trip complete!         |
      |                         |    Earnings: $18.80"      |
      |                         |-------------------------->|
      |                         |                           |
```

### Interview One-Liner

> "NotificationService is an observer of ride state changes. RideService calls `onRideStatusChanged()` after every state transition -- the notification service decides what to send based on the new state. Adding analytics tracking or SMS alerts means adding another observer, not modifying RideService."

### Cross-Reference

| Project | Observer Used For |
|---------|------------------|
| 03 - Notification System | Core pattern -- `NotificationObserver` for all channels |
| 06 - Parking Lot | `ParkingEventListener` for lot full/available events |
| 07 - Distributed Cache | `CacheStats` observes hit/miss/eviction |
| **08 - Ride Sharing** | **`NotificationService` observes ride state transitions** |

---

## 7. State Pattern

### What

Allow an object to alter its behavior when its internal state changes. The object will appear to change its class. A `Ride` goes through 5 states: REQUESTED -> MATCHED -> EN_ROUTE -> IN_PROGRESS -> COMPLETED (or CANCELLED from any state). Each state has different allowed transitions and behaviors.

### ASCII Diagram -- State Machine

```
                                    +-------------+
                                    |             |
                          +-------->| CANCELLED   |
                          |         |             |
                          |         +-------------+
                          |               ^
                          |               |
                          |         (cancel from any)
                          |               |
  +----------+     +------+---+     +-----+-----+     +-------------+     +-----------+
  |          |     |          |     |           |     |             |     |           |
  | REQUESTED|---->| MATCHED  |---->| EN_ROUTE  |---->| IN_PROGRESS |---->| COMPLETED |
  |          |     |          |     |           |     |             |     |           |
  +----------+     +----------+     +-----------+     +-------------+     +-----------+
       |                |                |                  |
       |  find driver   | driver arrives | pickup rider     | reach destination
       |                |                |                  |

  Valid transitions:
  REQUESTED  -> MATCHED     (driver found)
  REQUESTED  -> CANCELLED   (rider cancels before match)
  MATCHED    -> EN_ROUTE    (driver starts heading to pickup)
  MATCHED    -> CANCELLED   (rider or driver cancels)
  EN_ROUTE   -> IN_PROGRESS (rider picked up)
  EN_ROUTE   -> CANCELLED   (rider cancels, driver cancels)
  IN_PROGRESS -> COMPLETED  (reached destination)
  IN_PROGRESS -> CANCELLED  (emergency cancel)
```

### Ugly Code -- Without State Pattern

```java
// ANTI-PATTERN: status checks scattered everywhere
public class RideService {

    public void startRide(String rideId) {
        Ride ride = rideRepo.findById(rideId).get();

        // Giant if-else checking current state
        if (ride.getStatus().equals("EN_ROUTE")) {
            ride.setStatus("IN_PROGRESS");
            ride.setPickupTime(Instant.now());
        } else if (ride.getStatus().equals("REQUESTED")) {
            throw new RuntimeException("Can't start -- no driver matched yet!");
        } else if (ride.getStatus().equals("IN_PROGRESS")) {
            throw new RuntimeException("Already in progress!");
        } else if (ride.getStatus().equals("COMPLETED")) {
            throw new RuntimeException("Already completed!");
        } else if (ride.getStatus().equals("CANCELLED")) {
            throw new RuntimeException("Ride was cancelled!");
        }
        // Forgot MATCHED? Bug -- rider can't start ride after match.
    }

    public void cancelRide(String rideId) {
        Ride ride = rideRepo.findById(rideId).get();

        // ANOTHER giant if-else
        if (ride.getStatus().equals("COMPLETED")) {
            throw new RuntimeException("Can't cancel completed ride");
        } else if (ride.getStatus().equals("CANCELLED")) {
            throw new RuntimeException("Already cancelled");
        } else if (ride.getStatus().equals("IN_PROGRESS")) {
            // Partial refund logic here
            ride.setStatus("CANCELLED");
            // charge cancellation fee
        } else {
            ride.setStatus("CANCELLED");
        }
    }

    // Every new method needs the same state-checking if-else forest
}
```

**Problems with this approach:**
- Magic strings for state names -- no compile-time safety
- State validation logic duplicated across every method
- Easy to forget a state (MATCHED case missing in startRide)
- Adding a new state (e.g., WAITING_FOR_PICKUP) requires modifying every method
- No clear picture of valid state transitions

### Clean Code -- With State (Enum-based)

```java
public enum RideStatus {
    REQUESTED {
        @Override
        public Set<RideStatus> allowedTransitions() {
            return Set.of(MATCHED, CANCELLED);
        }
    },
    MATCHED {
        @Override
        public Set<RideStatus> allowedTransitions() {
            return Set.of(EN_ROUTE, CANCELLED);
        }
    },
    EN_ROUTE {
        @Override
        public Set<RideStatus> allowedTransitions() {
            return Set.of(IN_PROGRESS, CANCELLED);
        }
    },
    IN_PROGRESS {
        @Override
        public Set<RideStatus> allowedTransitions() {
            return Set.of(COMPLETED, CANCELLED);
        }
    },
    COMPLETED {
        @Override
        public Set<RideStatus> allowedTransitions() {
            return Set.of(); // terminal state
        }
    },
    CANCELLED {
        @Override
        public Set<RideStatus> allowedTransitions() {
            return Set.of(); // terminal state
        }
    };

    public abstract Set<RideStatus> allowedTransitions();

    public boolean canTransitionTo(RideStatus next) {
        return allowedTransitions().contains(next);
    }

    public RideStatus transitionTo(RideStatus next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateTransitionException(
                "Cannot transition from " + this + " to " + next);
        }
        return next;
    }
}

// Usage in Ride:
public class Ride {
    private RideStatus status;

    public void transitionTo(RideStatus newStatus) {
        this.status = this.status.transitionTo(newStatus);
    }
}

// Usage in RideService:
public Ride startRide(String rideId) {
    Ride ride = rideRepository.findById(rideId)
        .orElseThrow(() -> new RideNotFoundException(rideId));

    ride.transitionTo(RideStatus.IN_PROGRESS);  // throws if invalid
    ride.setPickupTime(Instant.now());

    rideRepository.save(ride);
    notificationService.onRideStatusChanged(ride);

    return ride;
}
```

### Numbered Call Chain -- State Transition (EN_ROUTE -> IN_PROGRESS)

```
  Driver          RideService            Ride            RideStatus(EN_ROUTE)    NotificationSvc
    |                  |                   |                    |                      |
    | (1) startRide    |                   |                    |                      |
    |   ("ride-1")     |                   |                    |                      |
    |----------------->|                   |                    |                      |
    |                  | (2) findById      |                    |                      |
    |                  |   ("ride-1")      |                    |                      |
    |                  |   -> ride found   |                    |                      |
    |                  |                   |                    |                      |
    |                  | (3) ride          |                    |                      |
    |                  |   .transitionTo   |                    |                      |
    |                  |   (IN_PROGRESS)   |                    |                      |
    |                  |------------------>|                    |                      |
    |                  |                   | (4) status         |                      |
    |                  |                   |  .transitionTo     |                      |
    |                  |                   |  (IN_PROGRESS)     |                      |
    |                  |                   |------------------>|                      |
    |                  |                   |                    | (5) canTransitionTo? |
    |                  |                   |                    |  EN_ROUTE ->         |
    |                  |                   |                    |  IN_PROGRESS = YES   |
    |                  |                   |                    |                      |
    |                  |                   | status=IN_PROGRESS |                      |
    |                  |                   |<------------------|                      |
    |                  |                   |                    |                      |
    |                  | (6) ride          |                    |                      |
    |                  |  .setPickupTime   |                    |                      |
    |                  |  (now())          |                    |                      |
    |                  |                   |                    |                      |
    |                  | (7) rideRepo      |                    |                      |
    |                  |  .save(ride)      |                    |                      |
    |                  |                   |                    |                      |
    |                  | (8) notifySvc     |                    |                      |
    |                  |  .onRideStatus    |                    |                      |
    |                  |  Changed(ride)    |                    |                      |
    |                  |---------------------------------------------------->|
    |                  |                   |                    |                      |
    |  ride(IN_PROGRESS)                   |                    |                      |
    |<-----------------|                   |                    |                      |
```

### Interview One-Liner

> "RideStatus is a state machine with 6 states and explicit transition rules. Each enum value knows its allowed next states. Calling `transitionTo()` on an invalid transition throws -- no more magic strings or forgotten edge cases. The state machine is the single source of truth for ride lifecycle."

### Cross-Reference

| Project | State Used For |
|---------|---------------|
| 06 - Parking Lot | `SpotStatus` (AVAILABLE, OCCUPIED, RESERVED) |
| **08 - Ride Sharing** | **`RideStatus` (REQUESTED -> MATCHED -> EN_ROUTE -> IN_PROGRESS -> COMPLETED)** |

---

## 8. Decorator Pattern

### What

Attach additional responsibilities to an object dynamically. `SurgePricingStrategy` wraps `StandardPricingStrategy` -- it calculates the base fare using the standard strategy, then applies a surge multiplier. The decorator implements the same `PricingStrategy` interface, so callers can't tell the difference.

### ASCII Diagram

```
  +---------------------+
  | <<interface>>       |
  | PricingStrategy     |
  | + calculateFare()   |
  +----------+----------+
             |
     +-------+--------+
     |                 |
  +--+-------------+ +-+------------------+
  | Standard       | | Surge              |
  | Pricing        | | Pricing            |
  | Strategy       | | Strategy           |
  |                | | (DECORATOR)        |
  | baseFare +     | |                    |
  | dist*rate +    | | wraps: Standard    |
  | time*rate      | | adds: multiplier   |
  +----------------+ +----+---------------+
                          |
                          | delegates to
                          v
                    +----------------+
                    | Standard       |
                    | Pricing        |
                    | Strategy       |
                    +----------------+

  Call flow:
  SurgePricing.calculateFare()
    -> base = standardPricing.calculateFare()   // delegate
    -> return base * surgeMultiplier             // enhance
```

### Ugly Code -- Without Decorator

```java
// ANTI-PATTERN: surge logic crammed into StandardPricingStrategy
public class StandardPricingStrategy implements PricingStrategy {

    @Override
    public Money calculateFare(Location pickup, Location dropoff,
                                RideType rideType) {
        double distanceKm = locationService.calculateDistance(pickup, dropoff);
        double baseFare = rideType.getBaseFare();
        double total = baseFare + (distanceKm * rideType.getPerKmRate());

        // Surge logic MIXED into base pricing -- SRP violation
        double surgeMultiplier = 1.0;
        if (isHighDemand(pickup)) {
            surgeMultiplier = calculateSurge(pickup);
        }
        total *= surgeMultiplier;

        // Discount logic ALSO mixed in
        if (hasPromoCode(/* where does this come from? */)) {
            total *= 0.8; // 20% off
        }

        // Toll logic ALSO mixed in
        total += calculateTolls(pickup, dropoff);

        // This class now handles: base fare + surge + discounts + tolls
        // Adding peak hour pricing? Airport surcharge? -- keep growing
        return Money.of(total);
    }
}
```

**Problems with this approach:**
- StandardPricingStrategy has 4 responsibilities (base fare, surge, discounts, tolls)
- Cannot use base pricing WITHOUT surge
- Cannot stack decorators flexibly (surge + discount but NOT tolls)
- Testing surge means testing everything together

### Clean Code -- With Decorator

```java
// Base strategy: clean, single responsibility
public class StandardPricingStrategy implements PricingStrategy {
    private final LocationService locationService;

    @Override
    public Money calculateFare(Location pickup, Location dropoff,
                                RideType rideType) {
        double distanceKm = locationService.calculateDistance(pickup, dropoff);
        double baseFare = rideType.getBaseFare();
        double perKmRate = rideType.getPerKmRate();
        double perMinRate = rideType.getPerMinuteRate();
        double estimatedMinutes = distanceKm * 2.0;

        double total = baseFare + (distanceKm * perKmRate)
                                + (estimatedMinutes * perMinRate);
        return Money.of(total);
    }
}

// Decorator: wraps any PricingStrategy, adds surge
public class SurgePricingStrategy implements PricingStrategy {
    private final PricingStrategy delegate;              // wrapped strategy
    private final SurgeService surgeService;

    public SurgePricingStrategy(PricingStrategy delegate,
                                 SurgeService surgeService) {
        this.delegate = delegate;
        this.surgeService = surgeService;
    }

    @Override
    public Money calculateFare(Location pickup, Location dropoff,
                                RideType rideType) {
        // (1) Delegate to base strategy
        Money baseFare = delegate.calculateFare(pickup, dropoff, rideType);

        // (2) Apply surge multiplier
        double multiplier = surgeService.getSurgeMultiplier(pickup);
        return baseFare.multiply(multiplier);
    }
}

// Another decorator: tolls
public class TollPricingStrategy implements PricingStrategy {
    private final PricingStrategy delegate;
    private final TollService tollService;

    @Override
    public Money calculateFare(Location pickup, Location dropoff,
                                RideType rideType) {
        Money baseFare = delegate.calculateFare(pickup, dropoff, rideType);
        Money tolls = tollService.calculateTolls(pickup, dropoff);
        return baseFare.add(tolls);
    }
}

// Composing decorators in AppConfig:
public PricingStrategy createPricingStrategy(LocationService locationService) {
    PricingStrategy base = new StandardPricingStrategy(locationService);
    PricingStrategy withSurge = new SurgePricingStrategy(base, surgeService);
    PricingStrategy withTolls = new TollPricingStrategy(withSurge, tollService);
    return withTolls; // Standard -> Surge -> Tolls
}
```

### Numbered Call Chain -- Surge Pricing Calculation

```
  RideService      SurgePricingStrategy    StandardPricingStrategy    SurgeService
      |                    |                        |                      |
      | (1) calculateFare  |                        |                      |
      |   (pickup, dropoff,|                        |                      |
      |    STANDARD)       |                        |                      |
      |------------------>|                        |                      |
      |                    | (2) delegate            |                      |
      |                    |   .calculateFare()     |                      |
      |                    |----------------------->|                      |
      |                    |                        | (3) haversine dist   |
      |                    |                        |   = 12.5 km          |
      |                    |                        | (4) baseFare=$2.50   |
      |                    |                        |   + 12.5*$1.50       |
      |                    |                        |   + 25min*$0.25      |
      |                    |                        |   = $27.50           |
      |                    |  baseFare=$27.50       |                      |
      |                    |<-----------------------|                      |
      |                    |                        |                      |
      |                    | (5) surgeService       |                      |
      |                    |   .getSurgeMultiplier  |                      |
      |                    |   (pickup)             |                      |
      |                    |--------------------------------------------->|
      |                    |                        |    multiplier=1.8x   |
      |                    |<---------------------------------------------|
      |                    |                        |                      |
      |                    | (6) $27.50 * 1.8       |                      |
      |                    |   = $49.50             |                      |
      |                    |                        |                      |
      |  fare=$49.50       |                        |                      |
      |<------------------|                        |                      |
```

### Interview One-Liner

> "SurgePricingStrategy is a decorator that wraps StandardPricingStrategy. It delegates to the base for fare calculation, then multiplies by the surge factor. Since both implement PricingStrategy, RideService can't tell if it's using base pricing or surge pricing -- decorators are transparent. We can stack them: Standard -> Surge -> Tolls -> Discounts."

### Cross-Reference

| Project | Decorator Used For |
|---------|-------------------|
| 02 - Rate Limiter | `LoggingRateLimiter` wraps any strategy with logging |
| **08 - Ride Sharing** | **`SurgePricingStrategy` wraps `StandardPricingStrategy` with surge multiplier** |

---

## 9. Singleton Pattern

### What

Ensure a class has only one instance and provide a global point of access to it. The `QuadTree` is a spatial index that must be shared across all components that need location queries. Having multiple QuadTree instances with different data would return inconsistent nearest-driver results.

### ASCII Diagram

```
  +-------------------+
  | LocationService   |
  | (owns one         |
  |  QuadTree)        |
  +---------+---------+
            |
            | one instance shared by:
            v
  +-------------------+
  | QuadTree          |  <--- Singleton per LocationService
  | (spatial index)   |
  +-------------------+
  | - root: Node      |
  | - maxDepth: int   |
  | - capacity: int   |
  +-------------------+
  | + insert(point)   |
  | + query(bounds)   |
  | + remove(point)   |
  | + update(old,new) |
  +-------------------+
        ^       ^       ^
        |       |       |
  +-----+--+ +-+------+ +--+----------+
  | Nearest | | ETA    | | Location   |
  | Driver  | | Based  | | Update     |
  | Matching| | Match  | | Handler    |
  +---------+ +--------+ +------------+

  All three use the SAME QuadTree instance via LocationService
```

### Ugly Code -- Without Singleton

```java
// ANTI-PATTERN: multiple QuadTree instances with different data
public class NearestDriverStrategy implements MatchingStrategy {
    // Creates its OWN QuadTree
    private final QuadTree quadTree = new QuadTree(-90, -180, 90, 180, 4);

    public Optional<Driver> findDriver(RideRequest request, List<Driver> available) {
        // This QuadTree has NO data -- nobody inserted drivers into THIS one
        List<Driver> nearby = quadTree.query(bounds); // always empty!
    }
}

public class LocationUpdateHandler {
    // Creates ANOTHER QuadTree
    private final QuadTree quadTree = new QuadTree(-90, -180, 90, 180, 4);

    public void updateDriverLocation(String driverId, double lat, double lng) {
        // Inserts into THIS QuadTree -- but NearestDriverStrategy has a DIFFERENT one
        quadTree.insert(new Point(lat, lng, driverId));
    }
}
// Result: location updates go to one tree, queries go to another -- nothing works
```

**Problems with this approach:**
- Multiple QuadTree instances with different data
- Location updates and queries hit different instances
- No consistency -- driver appears "nearby" in one component but not another
- Memory waste -- duplicate spatial indexes

### Clean Code -- With Singleton (via LocationService)

```java
public class LocationService {
    private final QuadTree quadTree;  // ONE instance, injected by Factory

    public LocationService(QuadTree quadTree) {
        this.quadTree = quadTree;    // singleton QuadTree
    }

    public void updateDriverLocation(String driverId, double lat, double lng) {
        // Remove old position
        quadTree.remove(driverId);
        // Insert new position
        quadTree.insert(new Point(lat, lng, driverId));
    }

    public List<Point> findNearby(double lat, double lng, double radiusKm) {
        Bounds searchArea = Bounds.fromCenter(lat, lng, radiusKm);
        return quadTree.query(searchArea);
    }

    public double calculateDistance(Location a, Location b) {
        return Haversine.distance(
            a.getLatitude(), a.getLongitude(),
            b.getLatitude(), b.getLongitude());
    }
}

// AppConfig ensures single instance:
public class AppConfig {
    public LocationService createLocationService() {
        QuadTree quadTree = new QuadTree(-90, -180, 90, 180, 4);  // ONE
        return new LocationService(quadTree);  // shared
    }

    public MatchingStrategy createMatchingStrategy(LocationService locationService) {
        return new NearestDriverStrategy(locationService);
        // Uses the SAME QuadTree via LocationService
    }
}
```

### Interview One-Liner

> "QuadTree is a singleton per LocationService -- one spatial index for all components. The Factory (AppConfig) creates one QuadTree, wraps it in LocationService, and injects that into both matching strategies and location update handlers. Everyone queries and updates the same tree."

### Cross-Reference

| Project | Singleton Used For |
|---------|-------------------|
| 02 - Rate Limiter | `RateLimiterConfig` (one config per limiter) |
| 07 - Distributed Cache | `CacheConfig` (one config per cache cluster) |
| **08 - Ride Sharing** | **`QuadTree` (one spatial index per LocationService)** |

---

## Pattern Interaction Map

All 9 patterns work together in a single ride request:

```
  Rider
    |
    | (1) requestRide(request)
    v
  +-----------------------------------------------------------+
  |  RideService (FACADE)                                      |
  |                                                            |
  |  (2) pricingStrategy.calculateFare()       [STRATEGY]      |
  |      |                                                     |
  |      +-- SurgePricingStrategy              [DECORATOR]     |
  |          |                                                 |
  |          +-- StandardPricingStrategy.calculateFare()        |
  |          +-- surgeService.getMultiplier()                   |
  |                                                            |
  |  (3) driverRepository.findAvailable()      [REPOSITORY]    |
  |                                                            |
  |  (4) matchingStrategy.findDriver()         [STRATEGY]      |
  |      |                                                     |
  |      +-- locationService.findNearby()                      |
  |          |                                                 |
  |          +-- quadTree.query()              [SINGLETON]      |
  |                                                            |
  |  (5) Ride.builder()                        [BUILDER]       |
  |      .rider(r).driver(d).fare(f)                           |
  |      .status(MATCHED)                      [STATE]         |
  |      .build()                                              |
  |                                                            |
  |  (6) rideRepository.save(ride)             [REPOSITORY]    |
  |                                                            |
  |  (7) notificationService                   [OBSERVER]      |
  |      .onRideStatusChanged(ride)                            |
  |                                                            |
  +-----------------------------------------------------------+
  |                                                            |
  |  All created by AppConfig                  [FACTORY]       |
  +-----------------------------------------------------------+
```

---

## Quick Reference: When to Mention Each Pattern in Interviews

| Interview Question | Lead With | Mention Also |
|-------------------|-----------|-------------|
| "How do you match riders to drivers?" | Strategy (MatchingStrategy) | QuadTree (Singleton), LocationService |
| "How do you handle surge pricing?" | Decorator (SurgePricingStrategy wraps Standard) | Strategy (PricingStrategy interface) |
| "How do you construct a Ride object?" | Builder (12+ fields, lifecycle states) | State (RideStatus enum with transitions) |
| "How do you manage dependencies?" | Factory (AppConfig composition root) | DI principle, interface-based design |
| "How do you decouple storage?" | Repository (Ride, Driver, Rider repos) | Swap InMemory for Postgres |
| "How does the client interact?" | Facade (RideService orchestrates 6 subsystems) | Strategy + Repository + Observer behind it |
| "How do you handle ride lifecycle?" | State (RideStatus enum with transition rules) | Observer (notifications on state change) |
| "How do you notify riders/drivers?" | Observer (NotificationService) | State (triggers notifications) |
| "How do you ensure one spatial index?" | Singleton (QuadTree via LocationService) | Factory creates and injects it |

---

## Summary Table

| Pattern | GoF Category | Problem Solved | SOLID Principle |
|---------|-------------|---------------|----------------|
| Strategy (x2) | Behavioral | Matching/pricing locked into service code | Open/Closed |
| Builder | Creational | Constructor with 12+ params, lifecycle states | SRP (construction separate from use) |
| Factory | Creational | `new ConcreteClass()` scattered everywhere | Dependency Inversion |
| Repository | Structural (DDD) | Domain coupled to storage implementation | Dependency Inversion |
| Facade | Structural | Client must coordinate 6 subsystems | SRP (one entry point) |
| Observer | Behavioral | Notifications mixed with ride logic | SRP (monitoring separate) |
| State | Behavioral | Status if-else chains in every method | Open/Closed (new states don't change service) |
| Decorator | Structural | Surge/toll/discount logic crammed into one class | Open/Closed + SRP |
| Singleton | Creational | Multiple QuadTree instances with different data | Single source of truth |
