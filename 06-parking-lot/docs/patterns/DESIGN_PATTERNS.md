# Design Patterns in the Parking Lot System

> Interview-ready reference for a Senior Java developer.
> This is THE classic LLD interview problem -- it showcases more design patterns than any other system.
> For each pattern: what it is, why it's here, ASCII diagram, code snippet, SOLID principle, and a 30-second interview one-liner.

---

## Table of Contents

| # | Pattern | GoF Category | Key Class(es) | One-Liner |
|---|---------|-------------|---------------|-----------|
| 1 | Strategy | Behavioral | `ParkingStrategy`, `PricingStrategy`, `PaymentProcessor` | Swap parking/pricing/payment algorithms at runtime |
| 2 | Singleton | Creational | `ParkingLot` | One lot instance -- physically there is only one |
| 3 | Factory | Creational | `VehicleFactory`, `AppConfig` | Create correct Vehicle/Spot subclass from enum |
| 4 | Facade | Structural | `ParkingService` | Single entry point hiding 5+ subsystems |
| 5 | State | Behavioral | `SpotStatus`, `ParkingSpot` | Spot transitions: AVAILABLE -> OCCUPIED -> AVAILABLE |
| 6 | Observer | Behavioral | `DisplayBoard` | Display refreshes when availability changes |
| 7 | Template Method | Behavioral | `ParkingSpot.canFitVehicle()` | Base class defines flow, subclasses define fit rules |
| 8 | Builder | Creational | `ParkingTicket` | Construct tickets with many optional fields cleanly |
| 9 | Repository | N/A (DDD) | `TicketRepository`, `SpotRepository` | Decouple storage from business logic |

---

## 1. Strategy Pattern (x3)

### What

Define a family of algorithms, encapsulate each behind a common interface, and make them interchangeable at runtime. This project uses Strategy THREE times -- each for a different responsibility.

### ASCII Diagram -- All 3 Strategy Hierarchies

```
  PARKING ASSIGNMENT            PRICING                    PAYMENT PROCESSING
  ==================            =======                    ==================

  +------------------+          +------------------+       +------------------+
  | <<interface>>    |          | <<interface>>    |       | <<interface>>    |
  | ParkingStrategy  |          | PricingStrategy  |       | PaymentProcessor |
  +------------------+          +------------------+       +------------------+
  | + findSpot(      |          | + calculate(     |       | + processPayment(|
  |   vehicle,       |          |   duration,      |       |   amount,        |
  |   floors): Spot  |          |   spotType):     |       |   method):       |
  +--------+---------+          |   BigDecimal     |       |   PaymentResult  |
           |                    +--------+---------+       +--------+---------+
     +-----+------+                 +----+----+                +----+----+
     |            |                 |         |                |         |
+----+-----+ +---+--------+  +-----+---+ +---+-----+   +-----+--+ +---+-------+
| Nearest  | | Compact    |  | Hourly  | | FlatRate |   | Cash   | | CreditCard|
| First    | | Floor      |  | Pricing | | Pricing  |   |Processor| | Processor |
| Strategy | | Strategy   |  | Strategy| | Strategy |   +--------+ +----------+
+----------+ +------------+  +---------+ +----------+
```

### Code Snippet

```java
// --- Strategy 1: Parking Assignment ---
public interface ParkingStrategy {
    Optional<ParkingSpot> findSpot(Vehicle vehicle, List<Floor> floors);
}

public class NearestFirstStrategy implements ParkingStrategy {
    @Override
    public Optional<ParkingSpot> findSpot(Vehicle vehicle, List<Floor> floors) {
        // Iterate floor 1, 2, 3... find first available spot that fits
        return floors.stream()
            .flatMap(f -> f.getSpots().stream())
            .filter(ParkingSpot::isAvailable)
            .filter(s -> s.canFitVehicle(vehicle))
            .findFirst();
    }
}

public class CompactFloorStrategy implements ParkingStrategy {
    @Override
    public Optional<ParkingSpot> findSpot(Vehicle vehicle, List<Floor> floors) {
        // Find smallest-possible spot type first (minimize wasted space)
        return floors.stream()
            .flatMap(f -> f.getSpots().stream())
            .filter(ParkingSpot::isAvailable)
            .filter(s -> s.canFitVehicle(vehicle))
            .min(Comparator.comparing(s -> s.getSpotType().ordinal()));
    }
}

// --- Strategy 2: Pricing ---
public interface PricingStrategy {
    BigDecimal calculate(Duration duration, SpotType spotType);
}

public class HourlyPricingStrategy implements PricingStrategy {
    private final Map<SpotType, BigDecimal> hourlyRates;

    @Override
    public BigDecimal calculate(Duration duration, SpotType spotType) {
        long hours = Math.max(1, (long) Math.ceil(duration.toMinutes() / 60.0));
        return hourlyRates.get(spotType).multiply(BigDecimal.valueOf(hours));
    }
}

// --- Strategy 3: Payment Processing ---
public interface PaymentProcessor {
    PaymentResult processPayment(BigDecimal amount, PaymentMethod method);
}
```

### Why Three Separate Interfaces

Three separate Strategy interfaces because each has a **different responsibility**. Merging them into one "ParkingAlgorithm" interface would violate SRP (one reason to change) and ISP (clients forced to depend on methods they don't use).

### Problem Solved

Without Strategy:

```java
// Anti-pattern: if-else in ParkingService
if (assignmentMode.equals("nearest"))  spot = findNearest(vehicle);
else if (assignmentMode.equals("compact")) spot = findCompact(vehicle);
// Every new strategy = modify this method = OCP violation
```

### SOLID Principle

**Open/Closed Principle** -- add new strategies without modifying existing code. Also **Interface Segregation** -- three small interfaces instead of one fat one.

### Interview One-Liner

> "We use Strategy three times because parking assignment, pricing, and payment are all independently variable algorithms. Each is a separate interface -- SRP plus OCP."

---

## 2. Singleton Pattern

### What

Ensure a class has only one instance and provide a global point of access to it.

### Why Here

There is physically **ONE parking lot**. Having two `ParkingLot` instances would mean two separate availability counts, two separate floor lists -- a recipe for inconsistency.

### ASCII Diagram

```
  +--------------------------------+
  |         ParkingLot             |
  +--------------------------------+
  | - static instance: ParkingLot  |
  | - floors: List<Floor>          |
  | - capacity: int                |
  +--------------------------------+
  | - ParkingLot()          [pvt]  |
  | + getInstance(): ParkingLot    |
  | + getAvailableCount(): int     |
  | + getFloors(): List<Floor>     |
  +--------------------------------+
           |
           | Only ONE instance
           v
    [All services share this]
```

### Code Snippet -- Double-Checked Locking

```java
public class ParkingLot {
    private static volatile ParkingLot instance;
    private final List<Floor> floors;
    private final String name;

    private ParkingLot(String name, List<Floor> floors) {
        this.name = name;
        this.floors = Collections.unmodifiableList(floors);
    }

    public static ParkingLot getInstance() {
        if (instance == null) {                    // 1st check (no lock)
            synchronized (ParkingLot.class) {
                if (instance == null) {            // 2nd check (with lock)
                    instance = buildDefault();
                }
            }
        }
        return instance;
    }
}
```

### Thread-Safety Considerations

| Approach | Pros | Cons |
|----------|------|------|
| Eager initialization (`static final`) | Simple, thread-safe | Created even if never used |
| Synchronized method | Simple | Lock on every call |
| **Double-checked locking** | Lock only on first creation | Needs `volatile` (Java 5+) |
| Enum singleton | Serialization-safe | Awkward for complex init |

### Interview Question: "Is Singleton an Anti-Pattern?"

**Answer**: For domain objects representing physically single resources (one parking lot, one print spooler), Singleton is appropriate. It becomes an anti-pattern when used as a lazy global variable for things that could have multiple instances. The key test: "Would having two instances cause a bug?" If yes, Singleton is warranted.

### SOLID Principle

**Single Responsibility** -- ParkingLot manages lot state, nothing else. Singleton is about lifecycle, not responsibility.

### Interview One-Liner

> "ParkingLot is Singleton because there is physically one lot. Two instances would mean two availability counts -- a data integrity bug."

---

## 3. Factory Pattern

### What

Define an interface for creating objects, letting the factory decide which class to instantiate. The client says "I need a CAR" and gets a `Car` instance without knowing the constructor.

### ASCII Diagram

```
  Client code                    VehicleFactory
  ===========                    ==============
                                 +----------------------------+
  VehicleFactory                 | + create(VehicleType): Vehicle |
    .create(VehicleType.CAR)     +-------------+--------------+
         |                                     |
         v                       +-------------+-------------+
  Returns: Car instance          |             |             |
                           VehicleType.    VehicleType.  VehicleType.
                           MOTORCYCLE      CAR           BUS
                               |             |             |
                               v             v             v
                          Motorcycle()    Car()        Bus()
```

### Code Snippet

```java
public class VehicleFactory {

    public static Vehicle create(VehicleType type, String licensePlate) {
        return switch (type) {
            case MOTORCYCLE -> new Motorcycle(licensePlate);
            case CAR        -> new Car(licensePlate);
            case BUS        -> new Bus(licensePlate);
        };
        // No default needed -- exhaustive enum switch (Java 21)
        // Adding a new VehicleType forces a compile error here
    }
}

// Similarly for spots:
public class SpotFactory {

    public static ParkingSpot create(SpotType type, String spotId, int floor) {
        return switch (type) {
            case MOTORCYCLE_SPOT -> new MotorcycleSpot(spotId, floor);
            case COMPACT         -> new CompactSpot(spotId, floor);
            case LARGE           -> new LargeSpot(spotId, floor);
            case HANDICAPPED     -> new HandicappedSpot(spotId, floor);
        };
    }
}
```

### Why Here

- Vehicle and ParkingSpot hierarchies each have multiple subclasses.
- Construction logic (which class for which enum) lives in one place.
- Adding `VehicleType.ELECTRIC_CAR` means adding one factory case + one class -- no other code changes.

### SOLID Principle

**Open/Closed Principle** -- new types extend the system; existing code is untouched (besides the factory switch, which is the *one* place that knows about concrete classes).

### Interview One-Liner

> "VehicleFactory hides construction logic. The client says 'create CAR' and gets a Car. Adding new vehicle types is one new class plus one factory case."

---

## 4. Facade Pattern

### What

Provide a unified interface to a set of interfaces in a subsystem. Facade defines a higher-level interface that makes the subsystem easier to use.

### ASCII Diagram

```
                        Client (ParkingLotApp)
                                |
                                v
                  +----------------------------+
                  |       ParkingService       |  <-- FACADE
                  |       (single entry point) |
                  +----------------------------+
                  | + parkVehicle(vehicle)      |
                  | + unparkVehicle(ticketId)   |
                  | + getAvailability()         |
                  +---+----+----+----+----+----+
                      |    |    |    |    |
            +---------+    |    |    |    +----------+
            |              |    |    |               |
            v              v    v    v               v
     +----------+   +------++ +--+---+  +---------+ +--------+
     | Parking  |   |Pricing| |Payment| | Ticket  | |Display |
     | Strategy |   |Strategy| |Processor| |Service | | Board  |
     +----------+   +--------+ +--------+ +---------+ +--------+
```

### Code Snippet

```java
public class ParkingService {
    private final ParkingLot parkingLot;
    private final ParkingStrategy parkingStrategy;
    private final PricingStrategy pricingStrategy;
    private final PaymentProcessor paymentProcessor;
    private final TicketService ticketService;
    private final DisplayBoard displayBoard;

    // Client calls ONE method -- doesn't know about 5 subsystems
    public ParkingTicket parkVehicle(Vehicle vehicle) {
        // 1. Find spot (strategy)
        ParkingSpot spot = parkingStrategy
            .findSpot(vehicle, parkingLot.getFloors())
            .orElseThrow(() -> new ParkingFullException("No spot for " + vehicle));

        // 2. Mark spot occupied
        spot.park(vehicle);

        // 3. Create ticket
        ParkingTicket ticket = ticketService.issueTicket(vehicle, spot);

        // 4. Update display
        displayBoard.update(parkingLot);

        return ticket;
    }

    public BigDecimal unparkVehicle(String ticketId, PaymentMethod method) {
        ParkingTicket ticket = ticketService.getTicket(ticketId);

        // 1. Calculate price
        BigDecimal amount = pricingStrategy.calculate(
            ticket.getDuration(), ticket.getSpot().getSpotType());

        // 2. Process payment
        paymentProcessor.processPayment(amount, method);

        // 3. Vacate spot
        ticket.getSpot().vacate();

        // 4. Update display
        displayBoard.update(parkingLot);

        return amount;
    }
}
```

### Why Here

The `parkVehicle()` operation involves 5 steps across 5 subsystems. Without the Facade, the client code would need to orchestrate spot-finding, ticket creation, display updates, etc. The Facade encapsulates this workflow.

### SOLID Principle

**Single Responsibility** -- ParkingService's one job is orchestrating the parking workflow. Each subsystem has its own responsibility.

### Interview One-Liner

> "ParkingService is a Facade. The client calls parkVehicle() and doesn't care about spot assignment, ticket creation, or display updates -- the Facade coordinates all five subsystems."

---

## 5. State Pattern

### What

Allow an object to alter its behavior when its internal state changes. The object appears to change its class.

### State Transition Diagram

```
                     park(vehicle)
     AVAILABLE -----------------------> OCCUPIED
        ^                                  |
        |          vacate()                |
        +----------------------------------+
        |
        |  markOutOfOrder()          repair()
        +-------------------> OUT_OF_ORDER
                              (maintenance)

        +-------------------> RESERVED
        |  reserve()              |
        AVAILABLE <---------------+
                    cancelReserve()
```

### Code Snippet

```java
public enum SpotStatus {
    AVAILABLE("[ ]"),
    OCCUPIED("[X]"),
    RESERVED("[R]"),
    OUT_OF_ORDER("[!]");
}

public abstract class ParkingSpot {
    private SpotStatus status = SpotStatus.AVAILABLE;

    public void park(Vehicle vehicle) {
        if (status != SpotStatus.AVAILABLE) {
            throw new SpotNotAvailableException("Spot " + spotId + " is " + status);
        }
        if (!canFitVehicle(vehicle)) {
            throw new VehicleTooLargeException(vehicle + " cannot fit in " + spotType);
        }
        this.currentVehicle = vehicle;
        this.status = SpotStatus.OCCUPIED;   // State transition
    }

    public void vacate() {
        if (status != SpotStatus.OCCUPIED) {
            throw new IllegalStateException("Cannot vacate: spot is " + status);
        }
        this.currentVehicle = null;
        this.status = SpotStatus.AVAILABLE;  // State transition
    }
}
```

### Why Here

Parking spots have well-defined states with strict transition rules. You cannot vacate an AVAILABLE spot. You cannot park in an OCCUPIED spot. The State pattern enforces these invariants.

### SOLID Principle

**Single Responsibility** -- each state transition is validated in one place. No scattered if-checks.

### Interview One-Liner

> "ParkingSpot uses State pattern with strict transitions. AVAILABLE -> OCCUPIED on park, OCCUPIED -> AVAILABLE on vacate. Invalid transitions throw exceptions."

---

## 6. Observer Pattern

### What

Define a one-to-many dependency between objects so that when one object changes state, all its dependents are notified and updated automatically.

### ASCII Diagram

```
  +-------------------+        notifies        +-------------------+
  |   ParkingService  | ---------------------> |   DisplayBoard    |
  | (Subject/Source)  |                        | (Observer)        |
  +-------------------+                        +-------------------+
  | parkVehicle()     |                        | update(lot)       |
  | unparkVehicle()   |                        | render()          |
  +-------------------+                        +-------------------+
                                                       |
                                               +-------+-------+
                                               |               |
                                          Floor 1 LED     Floor 2 LED
                                          "Cars: 8/50"    "Cars: 12/50"
```

### Code Snippet

```java
// Observer interface
public interface ParkingObserver {
    void onAvailabilityChanged(ParkingLot lot);
}

// Concrete observer
public class DisplayBoard implements ParkingObserver {
    @Override
    public void onAvailabilityChanged(ParkingLot lot) {
        for (Floor floor : lot.getFloors()) {
            System.out.printf("Floor %d: %d/%d spots available%n",
                floor.getFloorNumber(),
                floor.getAvailableCount(),
                floor.getTotalCount());
        }
    }
}

// In ParkingService (subject):
public ParkingTicket parkVehicle(Vehicle vehicle) {
    // ... park logic ...
    notifyObservers();  // DisplayBoard updates automatically
    return ticket;
}
```

### Why Here

When a vehicle parks or unparks, display boards on every floor must update. Rather than ParkingService knowing about every display board, it notifies registered observers. In production, this would be event-driven (message queue).

### SOLID Principle

**Dependency Inversion** -- ParkingService depends on the `ParkingObserver` interface, not on `DisplayBoard` directly.

### Interview One-Liner

> "DisplayBoard observes availability changes. When a car parks or leaves, all registered display boards refresh automatically."

---

## 7. Template Method Pattern

### What

Define the skeleton of an algorithm in a base class, letting subclasses override specific steps without changing the algorithm's structure.

### ASCII Diagram

```
  +---------------------------+
  |      ParkingSpot          |  <-- abstract base
  +---------------------------+
  | + park(vehicle):          |  <-- template method
  |     if !isAvailable THROW |
  |     if !canFitVehicle THROW|
  |     setOccupied()         |
  | # canFitVehicle(vehicle)  |  <-- abstract hook
  | + isAvailable(): boolean  |
  +-------------+-------------+
                |
    +-----------+-----------+----------+
    |           |           |          |
+---+------+ +-+--------+ ++---------+ ++-----------+
| Compact  | | Large    | |Motorcycle| |Handicapped |
| Spot     | | Spot     | |  Spot    | |   Spot     |
+----------+ +----------+ +----------+ +------------+
| canFit:  | | canFit:  | | canFit:  | | canFit:    |
| CAR,     | | CAR,     | | MOTO     | | CAR, MOTO  |
| MOTO     | | MOTO,BUS | | only     | | (permit)   |
+----------+ +----------+ +----------+ +------------+
```

### Code Snippet

```java
public abstract class ParkingSpot {

    // Template method: defines the algorithm skeleton
    public void park(Vehicle vehicle) {
        if (!isAvailable()) {
            throw new SpotNotAvailableException("Spot is occupied");
        }
        if (!canFitVehicle(vehicle)) {
            throw new VehicleTooLargeException("Vehicle doesn't fit");
        }
        this.currentVehicle = vehicle;
        this.status = SpotStatus.OCCUPIED;
    }

    // Abstract hook: subclasses define which vehicles fit
    protected abstract boolean canFitVehicle(Vehicle vehicle);

    public boolean isAvailable() {
        return status == SpotStatus.AVAILABLE;
    }
}

// Concrete: CompactSpot accepts CAR and MOTORCYCLE
public class CompactSpot extends ParkingSpot {
    @Override
    protected boolean canFitVehicle(Vehicle vehicle) {
        return vehicle.getVehicleType() == VehicleType.CAR
            || vehicle.getVehicleType() == VehicleType.MOTORCYCLE;
    }
}

// Concrete: LargeSpot accepts anything
public class LargeSpot extends ParkingSpot {
    @Override
    protected boolean canFitVehicle(Vehicle vehicle) {
        return true;  // Buses, cars, motorcycles -- all fit
    }
}

// Concrete: MotorcycleSpot accepts MOTORCYCLE only
public class MotorcycleSpot extends ParkingSpot {
    @Override
    protected boolean canFitVehicle(Vehicle vehicle) {
        return vehicle.getVehicleType() == VehicleType.MOTORCYCLE;
    }
}
```

### Why Here

The `park()` algorithm is the same for all spot types: check availability, check fit, mark occupied. Only the "check fit" step varies. Template Method avoids duplicating the algorithm in every subclass.

### SOLID Principle

**Liskov Substitution** -- any `ParkingSpot` subclass can be used wherever `ParkingSpot` is expected. The template guarantees consistent behavior.

### Interview One-Liner

> "ParkingSpot.park() is a template method. The algorithm is fixed -- check available, check fit, occupy. Subclasses only override canFitVehicle() to define their size rules."

---

## 8. Builder Pattern

### What

Separate the construction of a complex object from its representation, allowing the same construction process to create different representations.

### ASCII Diagram

```
  ParkingTicket.builder()
      .ticketId("T-001")
      .vehicle(car)
      .spot(spot)
      .entryTime(now)
      .build()
          |
          v
  +-------------------+
  |   ParkingTicket    |
  +-------------------+
  | ticketId: T-001   |
  | vehicle: Car      |
  | spot: CompactSpot |
  | entryTime: 14:30  |
  | exitTime: null     |  <-- set later on exit
  | amount: null       |  <-- set later on payment
  +-------------------+
```

### Code Snippet

```java
public class ParkingTicket {
    private final String ticketId;
    private final Vehicle vehicle;
    private final ParkingSpot spot;
    private final LocalDateTime entryTime;
    private LocalDateTime exitTime;
    private BigDecimal amount;

    private ParkingTicket(Builder builder) {
        this.ticketId = builder.ticketId;
        this.vehicle = builder.vehicle;
        this.spot = builder.spot;
        this.entryTime = builder.entryTime;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String ticketId;
        private Vehicle vehicle;
        private ParkingSpot spot;
        private LocalDateTime entryTime;

        public Builder ticketId(String ticketId) {
            this.ticketId = ticketId;
            return this;
        }
        public Builder vehicle(Vehicle vehicle) {
            this.vehicle = vehicle;
            return this;
        }
        public Builder spot(ParkingSpot spot) {
            this.spot = spot;
            return this;
        }
        public Builder entryTime(LocalDateTime entryTime) {
            this.entryTime = entryTime;
            return this;
        }
        public ParkingTicket build() {
            Objects.requireNonNull(ticketId, "ticketId required");
            Objects.requireNonNull(vehicle, "vehicle required");
            Objects.requireNonNull(spot, "spot required");
            return new ParkingTicket(this);
        }
    }
}
```

### Why Here

ParkingTicket has many fields. Some are set at entry (vehicle, spot, entryTime), others at exit (exitTime, amount). Builder makes construction readable and enforces required fields via `build()` validation.

### SOLID Principle

**Single Responsibility** -- Builder handles construction; ParkingTicket handles ticket behavior.

### Interview One-Liner

> "ParkingTicket uses Builder because it has many fields set at different lifecycle stages. Builder enforces required fields and keeps construction readable."

---

## 9. Repository Pattern

### What

Mediate between the domain and data mapping layers using a collection-like interface for accessing domain objects. Decouple business logic from storage.

### ASCII Diagram

```
  ParkingService                 <<interface>>
       |                      TicketRepository
       |                    +-------------------+
       +------------------> | + save(ticket)    |
                            | + findById(id)    |
                            | + findActive()    |
                            +---------+---------+
                                      |
                         +------------+------------+
                         |                         |
               +---------+----------+   +----------+---------+
               | InMemoryTicket     |   | JdbcTicket         |
               | Repository         |   | Repository         |
               +--------------------+   +--------------------+
               | HashMap<id,ticket> |   | DataSource, SQL    |
               +--------------------+   +--------------------+
```

### Code Snippet

```java
public interface TicketRepository {
    void save(ParkingTicket ticket);
    Optional<ParkingTicket> findById(String ticketId);
    List<ParkingTicket> findActiveTickets();
}

public class InMemoryTicketRepository implements TicketRepository {
    private final Map<String, ParkingTicket> store = new ConcurrentHashMap<>();

    @Override
    public void save(ParkingTicket ticket) {
        store.put(ticket.getTicketId(), ticket);
    }

    @Override
    public Optional<ParkingTicket> findById(String ticketId) {
        return Optional.ofNullable(store.get(ticketId));
    }
}
```

### Why Here

In an interview, you start with `InMemoryRepository` (HashMap). When the interviewer asks "what about persistence?", you swap in `JdbcTicketRepository` -- zero changes to ParkingService. Same interface, different implementation.

### SOLID Principle

**Dependency Inversion** -- ParkingService depends on `TicketRepository` (interface), not `InMemoryTicketRepository` (concrete class).

### Interview One-Liner

> "Repository pattern lets us swap HashMap for PostgreSQL without touching business logic. In an interview, start in-memory and mention the JDBC swap."

---

## Pattern Interaction Map

How the patterns work together in a single `parkVehicle()` call:

```
  Client
    |
    v
  ParkingService  [FACADE]
    |
    +-- ParkingLot.getInstance()  [SINGLETON]
    |
    +-- ParkingStrategy.findSpot()  [STRATEGY]
    |       |
    |       +-- ParkingSpot.canFitVehicle()  [TEMPLATE METHOD]
    |       +-- ParkingSpot.isAvailable()  [STATE]
    |
    +-- ParkingSpot.park(vehicle)  [STATE transition: AVAILABLE -> OCCUPIED]
    |
    +-- ParkingTicket.builder()...build()  [BUILDER]
    |
    +-- TicketRepository.save(ticket)  [REPOSITORY]
    |
    +-- DisplayBoard.onAvailabilityChanged()  [OBSERVER]
```

> **Interview tip**: Walk through this flow to show how 7+ patterns collaborate. Interviewers love seeing pattern interaction, not just isolated definitions.

---

## SOLID Principles Deep Dive

### Mapping Each Principle to Concrete Code

| Principle | Full Name | Example in Code | How It's Demonstrated |
|-----------|-----------|-----------------|----------------------|
| **S** - SRP | Single Responsibility | `Vehicle`, `ParkingSpot`, `PricingStrategy`, `PaymentProcessor` | Each class has ONE reason to change. Vehicle knows about vehicles. PricingStrategy knows about pricing. They never overlap. |
| **O** - OCP | Open/Closed | New `VehicleType` -> new class, no changes to existing | Add `ElectricCar extends Vehicle` + one factory case. `ParkingStrategy`, `ParkingService`, `PricingStrategy` -- none modified. |
| **L** - LSP | Liskov Substitution | `CompactSpot` used wherever `ParkingSpot` expected | `ParkingStrategy.findSpot()` returns `ParkingSpot`. Caller doesn't know or care if it's Compact, Large, or Motorcycle. All honor the `park()`/`vacate()` contract. |
| **I** - ISP | Interface Segregation | `ParkingStrategy`, `PricingStrategy`, `PaymentProcessor` | Three small focused interfaces instead of one `IParkingOperations` with 10 methods. A payment client only depends on `PaymentProcessor`, not on spot-finding logic. |
| **D** - DIP | Dependency Inversion | `ParkingService` -> `ParkingStrategy` (interface) | ParkingService constructor takes interfaces, not concrete classes. Swap `NearestFirstStrategy` for `CompactFloorStrategy` without touching service code. |

### SRP -- One Class, One Reason to Change

```
  Vehicle          -> models a vehicle (license plate, type, entry time)
  ParkingSpot      -> models a spot (status, capacity, occupant)
  PricingStrategy  -> calculates price (rate, duration, spot type)
  PaymentProcessor -> processes payment (charge, refund, receipt)
  TicketService    -> manages tickets (issue, lookup, close)
  DisplayBoard     -> renders availability (format, display, refresh)
```

If pricing rules change, only `PricingStrategy` implementations change. `ParkingSpot`, `Vehicle`, `PaymentProcessor` are untouched.

### OCP -- Adding a New Vehicle Type

```
  Step 1: Create class      ->  public class ElectricCar extends Vehicle { ... }
  Step 2: Add enum value     ->  VehicleType.ELECTRIC_CAR
  Step 3: Add factory case   ->  case ELECTRIC_CAR -> new ElectricCar(plate);
  Step 4: (Optional) New spot ->  public class EVSpot extends ParkingSpot { ... }

  NOT modified: ParkingService, ParkingStrategy, PricingStrategy, DisplayBoard
```

### LSP -- Subtypes Are Substitutable

```java
// This code works with ANY ParkingSpot subclass:
public void parkVehicleInSpot(ParkingSpot spot, Vehicle vehicle) {
    spot.park(vehicle);  // Works for CompactSpot, LargeSpot, MotorcycleSpot...
}
// No instanceof checks, no type-specific branches. That's LSP.
```

### ISP -- Small Focused Interfaces

```
  BAD (fat interface):
  +-------------------------------+
  | IParkingOperations            |
  +-------------------------------+
  | + findSpot()                  |
  | + calculatePrice()            |  <-- Why does a DisplayBoard need this?
  | + processPayment()            |
  | + updateDisplay()             |
  +-------------------------------+

  GOOD (segregated):
  +----------------+  +----------------+  +------------------+
  | ParkingStrategy|  | PricingStrategy|  | PaymentProcessor |
  +----------------+  +----------------+  +------------------+
  | + findSpot()   |  | + calculate()  |  | + processPayment()|
  +----------------+  +----------------+  +------------------+
```

### DIP -- Depend on Abstractions

```java
public class ParkingService {
    // Depends on INTERFACES (abstractions), not concrete classes
    private final ParkingStrategy parkingStrategy;    // not NearestFirstStrategy
    private final PricingStrategy pricingStrategy;    // not HourlyPricingStrategy
    private final PaymentProcessor paymentProcessor;  // not CashPaymentProcessor
    private final TicketRepository ticketRepository;  // not InMemoryTicketRepository

    // Injected via constructor -- swap implementations freely
    public ParkingService(ParkingStrategy ps, PricingStrategy pr,
                          PaymentProcessor pp, TicketRepository tr) { ... }
}
```

---

## Quick-Reference Cheat Sheet

| Pattern | GoF Category | Classes | When Interviewer Asks... |
|---------|-------------|---------|-------------------------|
| Strategy (x3) | Behavioral | ParkingStrategy, PricingStrategy, PaymentProcessor | "How would you change the pricing algorithm?" |
| Singleton | Creational | ParkingLot | "Can there be multiple lots?" |
| Factory | Creational | VehicleFactory, SpotFactory | "How do you create vehicles from user input?" |
| Facade | Structural | ParkingService | "Walk me through the parkVehicle flow." |
| State | Behavioral | SpotStatus, ParkingSpot | "What happens if you try to park in an occupied spot?" |
| Observer | Behavioral | DisplayBoard | "How do display boards stay updated?" |
| Template Method | Behavioral | ParkingSpot.canFitVehicle() | "How do you decide if a vehicle fits?" |
| Builder | Creational | ParkingTicket | "How is a ticket constructed?" |
| Repository | DDD | TicketRepository, SpotRepository | "How would you switch from in-memory to a database?" |
