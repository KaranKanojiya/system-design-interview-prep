# Low-Level Design: Parking Lot System

> **Difficulty**: MEDIUM-HARD | **Target**: Senior Java Developer (7+ years) | **Focus**: OOP, SOLID Principles, Inheritance, Interfaces, Design Patterns
> This is THE classic OOP/LLD interview question. Every concept here maps directly to what interviewers evaluate.

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
| **Model (Vehicle)** | `model/vehicle/` | Vehicle hierarchy: abstract Vehicle with concrete Car, Motorcycle, Bus. Rich OOP with abstract methods and type-specific behavior. |
| **Model (Spot)** | `model/spot/` | ParkingSpot hierarchy: abstract ParkingSpot with concrete CompactSpot, LargeSpot, MotorcycleSpot, HandicappedSpot. Each overrides `canFitVehicle()` (Template Method). |
| **Model (Ticket)** | `model/ticket/` | ParkingTicket with Builder pattern. Captures entry/exit times, spot, vehicle, and payment status. |
| **Model (Payment)** | `model/payment/` | Payment entity, PaymentMethod enum, PaymentStatus enum. Tracks financial transactions. |
| **Model (Core)** | `model/` | ParkingFloor, ParkingLot (Singleton), EntryGate, ExitGate, DisplayBoard, all enums (VehicleType, SpotType, SpotStatus). |
| **Strategy** | `strategy/` | Pluggable algorithms for spot assignment (ParkingStrategy) and fee calculation (PricingStrategy). Open-Closed at its finest. |
| **Payment** | `payment/` | PaymentProcessor interface with Cash and CreditCard implementations. Strategy pattern for payment processing. |
| **Service** | `service/` | Business logic layer: ParkingService (Facade), TicketService, PaymentService, AvailabilityService. Orchestrates all subsystems. |
| **Display** | `display/` | DisplayBoard management. Observer pattern for real-time availability updates. |
| **Repository** | `repository/` | Data access abstraction. Interface + InMemory implementations for all entities. Swappable for DB-backed stores. |
| **Controller** | `controller/` | REST API entry point. Maps HTTP requests to service calls. |
| **Config** | `config/` | Application configuration, bean wiring, strategy selection. |
| **Exception** | `exception/` | Domain-specific exceptions: ParkingFullException, InvalidTicketException, SpotNotAvailableException, PaymentFailedException. |

### Why Parking Lot Is THE Classic LLD Question

```
Interviewer's checklist when evaluating your answer:

  1. Do you use abstract classes for Vehicle and ParkingSpot?     --> Inheritance
  2. Do spots override canFitVehicle() differently?               --> Polymorphism
  3. Is spot assignment pluggable?                                --> Strategy Pattern
  4. Is ParkingLot a Singleton?                                   --> Singleton Pattern
  5. Can you add new vehicle/spot types without changing code?    --> Open-Closed
  6. Is your ParkingService a Facade?                             --> Facade Pattern
  7. Do you handle concurrency (two cars, one spot)?              --> Threading
  8. Are your interfaces small and focused?                       --> Interface Segregation
```

---

## 2. Package Structure

```
com.systemdesign.parking
│
├── model/
│   ├── vehicle/
│   │   ├── Vehicle.java                -- Abstract base: licensePlate, vehicleType, entryTime
│   │   ├── Car.java                    -- Extends Vehicle, type = CAR
│   │   ├── Motorcycle.java            -- Extends Vehicle, type = MOTORCYCLE
│   │   └── Bus.java                    -- Extends Vehicle, type = BUS, needsMultipleSpots() = true
│   │
│   ├── spot/
│   │   ├── ParkingSpot.java           -- Abstract base: spotId, floorNumber, status, vehicle
│   │   ├── CompactSpot.java           -- canFitVehicle: CAR, MOTORCYCLE
│   │   ├── LargeSpot.java            -- canFitVehicle: CAR, MOTORCYCLE, BUS (any)
│   │   ├── MotorcycleSpot.java        -- canFitVehicle: MOTORCYCLE only
│   │   └── HandicappedSpot.java       -- canFitVehicle: CAR (handicapped flag)
│   │
│   ├── ticket/
│   │   └── ParkingTicket.java         -- Builder pattern: ticketId, vehicle, spot, times, amount
│   │
│   ├── payment/
│   │   ├── Payment.java               -- paymentId, ticketId, amount, method, status, timestamp
│   │   ├── PaymentMethod.java (enum)  -- CASH, CREDIT_CARD, DEBIT_CARD
│   │   └── PaymentStatus.java (enum)  -- PENDING, COMPLETED, FAILED, REFUNDED
│   │
│   ├── ParkingFloor.java              -- floorNumber, Map<SpotType, List<ParkingSpot>>
│   ├── ParkingLot.java                -- Singleton: name, floors, gates, capacity management
│   ├── EntryGate.java                 -- gateId, gateNumber, issues tickets
│   ├── ExitGate.java                  -- gateId, gateNumber, processes payments
│   ├── DisplayBoard.java              -- Available spots per floor per type
│   ├── VehicleType.java (enum)        -- MOTORCYCLE, CAR, BUS with spotType mapping
│   ├── SpotType.java (enum)           -- MOTORCYCLE_SPOT, COMPACT, LARGE, HANDICAPPED
│   └── SpotStatus.java (enum)         -- AVAILABLE, OCCUPIED, RESERVED, OUT_OF_ORDER
│
├── strategy/
│   ├── ParkingStrategy.java           -- Interface: findSpot(lot, vehicle) -> Optional<ParkingSpot>
│   ├── NearestFirstStrategy.java      -- Scan floors 1→N, first available spot
│   ├── CompactFloorStrategy.java      -- Fill one floor completely before next
│   ├── PricingStrategy.java           -- Interface: calculateFee(ticket) -> double
│   ├── HourlyPricingStrategy.java     -- Per-hour rates by vehicle type
│   └── FlatRatePricingStrategy.java   -- Fixed daily rate by vehicle type
│
├── payment/
│   ├── PaymentProcessor.java          -- Interface: processPayment(amount, method) -> Payment
│   ├── CashPaymentProcessor.java      -- Simulates cash, always succeeds
│   └── CreditCardPaymentProcessor.java -- Simulates card, 95% success rate
│
├── service/
│   ├── ParkingService.java            -- FACADE: parkVehicle, unparkVehicle (main entry point)
│   ├── TicketService.java             -- Generate, find, calculate duration
│   ├── PaymentService.java            -- Process payment via PaymentProcessor strategy
│   └── AvailabilityService.java       -- Availability per floor/type, display formatting
│
├── display/
│   └── DisplayBoardManager.java       -- Observer: listens to park/unpark, updates display
│
├── repository/
│   ├── SpotRepository.java            -- Interface: find, save, update spots
│   ├── TicketRepository.java          -- Interface: save, find tickets
│   ├── VehicleRepository.java         -- Interface: track active vehicles
│   ├── InMemorySpotRepository.java    -- ConcurrentHashMap-backed
│   ├── InMemoryTicketRepository.java  -- ConcurrentHashMap-backed
│   └── InMemoryVehicleRepository.java -- ConcurrentHashMap-backed
│
├── factory/
│   ├── VehicleFactory.java            -- Create correct Vehicle subclass from VehicleType
│   └── SpotFactory.java               -- Create correct ParkingSpot subclass from SpotType
│
├── controller/
│   └── ParkingController.java         -- REST endpoints: /park, /unpark, /availability
│
├── config/
│   └── AppConfig.java                 -- Bean definitions, strategy selection, lot initialization
│
└── exception/
    ├── ParkingFullException.java      -- Lot has no available spots for vehicle type
    ├── InvalidTicketException.java    -- Ticket ID not found or already used
    ├── SpotNotAvailableException.java -- Specific spot was taken (race condition)
    └── PaymentFailedException.java    -- Payment processing failed
```

---

## 3. Class Diagram

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║                        VEHICLE HIERARCHY (Inheritance)                           ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

                +-----------------------------------------------+
                |        Vehicle  <<abstract>>                   |
                |-----------------------------------------------|
                | - licensePlate: String                         |
                | - vehicleType: VehicleType                     |
                | - entryTime: LocalDateTime                     |
                | - handicapped: boolean                         |
                |-----------------------------------------------|
                | + getLicensePlate(): String                     |
                | + getVehicleType(): VehicleType                 |
                | + getEntryTime(): LocalDateTime                 |
                | + isHandicapped(): boolean                      |
                | + needsMultipleSpots(): boolean {return false}  |
                | + toString(): String                            |
                +-----------------------------------------------+
                       ^              ^              ^
                       |              |              |
            extends    |   extends    |   extends    |
                       |              |              |
          +------------+--+  +--------+------+  +---+-----------+
          |     Car       |  |  Motorcycle   |  |     Bus       |
          |---------------|  |---------------|  |---------------|
          | type = CAR    |  | type = MOTOR  |  | type = BUS    |
          |---------------|  |   CYCLE       |  |---------------|
          | + Car(plate)  |  |---------------|  | + Bus(plate)  |
          | needsMultiple |  | + Motorcycle  |  | needsMultiple |
          |  Spots:false  |  |   (plate)     |  |  Spots: TRUE  |
          +---------------+  | needsMultiple |  +---------------+
                             |  Spots:false  |
                             +---------------+

╔═══════════════════════════════════════════════════════════════════════════════════╗
║                     PARKING SPOT HIERARCHY (Inheritance)                          ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

                +-----------------------------------------------+
                |      ParkingSpot  <<abstract>>                 |
                |-----------------------------------------------|
                | - spotId: String                               |
                | - floorNumber: int                             |
                | - spotNumber: int                              |
                | - spotType: SpotType                           |
                | - status: SpotStatus                           |
                | - currentVehicle: Vehicle                      |
                |-----------------------------------------------|
                | + park(vehicle: Vehicle): void                  |
                | + vacate(): void                               |
                | + canFitVehicle(v: Vehicle): boolean <<abstract>> |
                | + isAvailable(): boolean                       |
                | + getSpotId(): String                          |
                | + getStatus(): SpotStatus                      |
                | + getCurrentVehicle(): Vehicle                  |
                +-----------------------------------------------+
                  ^            ^            ^             ^
                  |            |            |             |
       extends    |  extends   |  extends   |  extends    |
                  |            |            |             |
       +----------+--+ +------+------+ +---+--------+ +-+------------+
       | CompactSpot  | | LargeSpot   | | Motorcycle | | Handicapped |
       |              | |             | |    Spot    | |    Spot     |
       |--------------| |-------------| |------------| |-------------|
       | type=COMPACT | | type=LARGE  | | type=MOTO  | | type=HANDI  |
       |              | |             | | RCYCLE_SPOT| |  CAPPED     |
       |--------------| |-------------| |------------| |-------------|
       | canFitVehicle| | canFitVehicle| | canFitVeh | | canFitVeh  |
       |  CAR: true   | |  CAR: true  | |  MOTOR    | |  CAR: true |
       |  MOTO: true  | |  MOTO: true | |  CYCLE:   | |  (handi-   |
       |  BUS: false  | |  BUS: true  | |  true     | |  capped    |
       +--------------+ |  (any)      | |  else:    | |  only)     |
                        +-------------+ |  false    | +------------+
                                        +-----------+

╔═══════════════════════════════════════════════════════════════════════════════════╗
║                     STRATEGY INTERFACES + IMPLEMENTATIONS                        ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

  +-------------------------------+    +-------------------------------+
  |  <<interface>>                 |    |  <<interface>>                |
  |  ParkingStrategy               |    |  PricingStrategy             |
  |-------------------------------|    |-------------------------------|
  | + findSpot(lot: ParkingLot,    |    | + calculateFee(ticket:       |
  |     vehicle: Vehicle):         |    |     ParkingTicket): double   |
  |     Optional<ParkingSpot>      |    +-------------------------------+
  +-------------------------------+            ^              ^
          ^              ^                     |              |
          |              |          implements |   implements |
   impl   |       impl  |                     |              |
          |              |         +-----------+---+ +--------+-----------+
  +-------+--------+ +--+----------------+  | HourlyPricing | | FlatRatePricing |
  | NearestFirst   | | CompactFloor      |  |   Strategy    | |   Strategy      |
  |   Strategy     | |   Strategy        |  |---------------| |-----------------|
  |----------------| |--------------------|  | -rates: Map   | | -dailyRates:   |
  | +findSpot():   | | +findSpot():       |  |  <VehicleType | |  Map<Vehicle   |
  | scan floors    | | fill floor before  |  |  ,Double>     | |  Type,Double>  |
  | 1->N, first    | | moving to next     |  |---------------| |-----------------|
  | available      | |                    |  | +calculateFee | | +calculateFee  |
  +----------------+ +--------------------+  +---------------+ +-----------------+

  +-------------------------------+
  |  <<interface>>                 |
  |  PaymentProcessor              |
  |-------------------------------|
  | + processPayment(amount:       |
  |     double, method:            |
  |     PaymentMethod): Payment    |
  +-------------------------------+
          ^              ^
          |              |
   impl   |       impl  |
          |              |
  +-------+--------+ +--+------------------+
  | CashPayment    | | CreditCardPayment   |
  |   Processor    | |   Processor         |
  |----------------| |---------------------|
  | +process:      | | +process:           |
  |  always succeed| |  95% success rate   |
  |  print [CASH]  | |  print [CARD]       |
  +----------------+ +---------------------+

╔═══════════════════════════════════════════════════════════════════════════════════╗
║                    CORE MODEL + SERVICE + REPOSITORY                             ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

  +-----------------------------------------------+
  |      ParkingLot  <<Singleton>>                 |
  |-----------------------------------------------|
  | - INSTANCE: ParkingLot      [static]           |
  | - name: String                                 |
  | - address: String                              |
  | - floors: List<ParkingFloor>                   |
  | - entryGates: List<EntryGate>                  |
  | - exitGates: List<ExitGate>                    |
  |-----------------------------------------------|
  | - ParkingLot()              [private ctor]     |
  | + getInstance(): ParkingLot [static sync]      |
  | + addFloor(floor): void                        |
  | + getFloors(): List<ParkingFloor>              |
  | + getTotalCapacity(): int                      |
  | + getAvailableCount(): int                     |
  | + isFull(): boolean                            |
  +-----------------------------------------------+
           |  contains 1..*
           v
  +-----------------------------------------------+
  |           ParkingFloor                         |
  |-----------------------------------------------|
  | - floorNumber: int                             |
  | - spotsByType: Map<SpotType, List<ParkingSpot>>|
  | - displayBoard: DisplayBoard                   |
  |-----------------------------------------------|
  | + addSpot(spot: ParkingSpot): void             |
  | + getAvailableSpots(type: VehicleType):        |
  |     List<ParkingSpot>                          |
  | + getAvailableCount(type: SpotType): int       |
  | + isFull(): boolean                            |
  | + updateDisplay(): void                        |
  +-----------------------------------------------+
           |  contains 1..*
           v
  +-----------------------------------------------+
  |      ParkingSpot  <<abstract>>                 |
  |  (see hierarchy above)                         |
  +-----------------------------------------------+

  +-----------------------------------------------+
  |        ParkingTicket  <<Builder>>              |
  |-----------------------------------------------|
  | - ticketId: String                             |
  | - vehicle: Vehicle                             |
  | - parkingSpot: ParkingSpot                     |
  | - entryTime: LocalDateTime                     |
  | - exitTime: LocalDateTime                      |
  | - amount: double                               |
  | - isPaid: boolean                              |
  |-----------------------------------------------|
  | + builder(): TicketBuilder  [static]           |
  | + calculateDuration(): Duration                |
  | + markPaid(amount: double): void               |
  | + setExitTime(time: LocalDateTime): void       |
  | + toString(): String                           |
  +-----------------------------------------------+

  +------------------+    +------------------+
  |   EntryGate      |    |   ExitGate       |
  |------------------|    |------------------|
  | - gateId: String |    | - gateId: String |
  | - gateNumber: int|    | - gateNumber: int|
  |------------------|    |------------------|
  | + issueTicket    |    | + processExit    |
  |   (vehicle):     |    |   (ticket):      |
  |   ParkingTicket  |    |   Payment        |
  +------------------+    +------------------+

  +-----------------------------------------------+
  |        DisplayBoard                            |
  |-----------------------------------------------|
  | - floorNumber: int                             |
  | - availableSpots: Map<SpotType, Integer>       |
  |-----------------------------------------------|
  | + update(spotType: SpotType, count: int): void |
  | + show(): String                               |
  +-----------------------------------------------+

╔═══════════════════════════════════════════════════════════════════════════════════╗
║                         SERVICE LAYER (Facade)                                   ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

  +-----------------------------------------------------+
  |        ParkingService  <<Facade>>                    |
  |-----------------------------------------------------|
  | - parkingLot: ParkingLot                             |
  | - parkingStrategy: ParkingStrategy     [interface]   |
  | - pricingStrategy: PricingStrategy     [interface]   |
  | - ticketService: TicketService                       |
  | - paymentService: PaymentService                     |
  | - availabilityService: AvailabilityService           |
  |-----------------------------------------------------|
  | + parkVehicle(vehicle: Vehicle): ParkingTicket       |
  | + unparkVehicle(ticketId: String,                    |
  |     method: PaymentMethod): Payment                  |
  | + getAvailability(): Map<Integer, Map<SpotType,Int>> |
  +-----------------------------------------------------+
       |          |            |             |
       | uses     | uses       | uses        | uses
       v          v            v             v
  TicketService  PaymentSvc  AvailSvc   ParkingStrategy
                                         PricingStrategy

╔═══════════════════════════════════════════════════════════════════════════════════╗
║                         REPOSITORY LAYER                                         ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

  +-------------------------------+    +-------------------------------+
  |  <<interface>>                 |    |  <<interface>>                |
  |  SpotRepository                |    |  TicketRepository            |
  |-------------------------------|    |-------------------------------|
  | + findById(id): Optional<Spot>|    | + save(ticket): ParkingTicket|
  | + findAvailable(type):        |    | + findById(id):              |
  |     List<ParkingSpot>         |    |     Optional<ParkingTicket>  |
  | + save(spot): ParkingSpot     |    | + findByVehicle(plate):      |
  | + updateStatus(id, status)    |    |     Optional<ParkingTicket>  |
  +-------------------------------+    +-------------------------------+
           ^                                    ^
           | implements                         | implements
  +--------+------------------+        +--------+------------------+
  | InMemorySpotRepository    |        | InMemoryTicketRepository  |
  |---------------------------|        |---------------------------|
  | -store: ConcurrentHashMap |        | -store: ConcurrentHashMap |
  +---------------------------+        +---------------------------+

  +-------------------------------+
  |  <<interface>>                 |
  |  VehicleRepository             |
  |-------------------------------|
  | + save(vehicle): Vehicle       |
  | + findByPlate(plate):          |
  |     Optional<Vehicle>          |
  | + remove(plate): boolean       |
  | + getActiveVehicles():         |
  |     List<Vehicle>              |
  +-------------------------------+
           ^
           | implements
  +--------+------------------+
  | InMemoryVehicleRepository |
  |---------------------------|
  | -store: ConcurrentHashMap |
  +---------------------------+

RELATIONSHIP SUMMARY
====================
ParkingController    --uses-->  ParkingService (Facade)
ParkingService       --uses-->  ParkingStrategy (interface)
ParkingService       --uses-->  PricingStrategy (interface)
ParkingService       --uses-->  TicketService
ParkingService       --uses-->  PaymentService
ParkingService       --uses-->  AvailabilityService
PaymentService       --uses-->  PaymentProcessor (interface)
TicketService        --uses-->  TicketRepository (interface)
AvailabilityService  --uses-->  SpotRepository (interface)
ParkingLot           --contains--> List<ParkingFloor>
ParkingFloor         --contains--> Map<SpotType, List<ParkingSpot>>
ParkingSpot          --holds-->    Vehicle (when occupied)
ParkingTicket        --refs-->     Vehicle, ParkingSpot
NearestFirstStrategy --implements--> ParkingStrategy
CompactFloorStrategy --implements--> ParkingStrategy
HourlyPricingStrategy  --implements--> PricingStrategy
FlatRatePricingStrategy--implements--> PricingStrategy
CashPaymentProcessor   --implements--> PaymentProcessor
CreditCardPaymentProcessor --implements--> PaymentProcessor
Car, Motorcycle, Bus   --extends-->  Vehicle (abstract)
CompactSpot, LargeSpot, MotorcycleSpot, HandicappedSpot --extends--> ParkingSpot (abstract)
VehicleFactory         --creates-->  Car | Motorcycle | Bus
SpotFactory            --creates-->  CompactSpot | LargeSpot | MotorcycleSpot | HandicappedSpot
```

---

## 4. Entity Design

> This section is THE CORE of the Parking Lot LLD. Every class here demonstrates a specific OOP concept that interviewers evaluate.

### 4.1 Enums (Foundation Types)

#### VehicleType

```java
public enum VehicleType {
    MOTORCYCLE(SpotType.MOTORCYCLE_SPOT),
    CAR(SpotType.COMPACT),
    BUS(SpotType.LARGE);

    private final SpotType defaultSpotType;

    VehicleType(SpotType defaultSpotType) {
        this.defaultSpotType = defaultSpotType;
    }

    /**
     * Returns the "natural" spot type for this vehicle.
     * A CAR naturally fits in COMPACT, but can also fit in LARGE.
     * The actual fitting logic lives in ParkingSpot.canFitVehicle().
     */
    public SpotType getDefaultSpotType() {
        return defaultSpotType;
    }
}
```

**Interview point**: Each enum value carries behavior (spot mapping). This avoids `if-else` chains and follows OCP -- new vehicle types just declare their default spot.

#### SpotType

```java
public enum SpotType {
    MOTORCYCLE_SPOT,
    COMPACT,
    LARGE,
    HANDICAPPED;

    /**
     * Returns the vehicle types that naturally map to this spot type.
     * Note: The authoritative "can fit" logic is in ParkingSpot subclasses.
     */
    public Set<VehicleType> getNaturalVehicleTypes() {
        return switch (this) {
            case MOTORCYCLE_SPOT -> EnumSet.of(VehicleType.MOTORCYCLE);
            case COMPACT         -> EnumSet.of(VehicleType.CAR, VehicleType.MOTORCYCLE);
            case LARGE           -> EnumSet.of(VehicleType.CAR, VehicleType.MOTORCYCLE, VehicleType.BUS);
            case HANDICAPPED     -> EnumSet.of(VehicleType.CAR);
        };
    }
}
```

#### SpotStatus (State Pattern enabler)

```java
public enum SpotStatus {
    AVAILABLE,
    OCCUPIED,
    RESERVED,
    OUT_OF_ORDER;

    /**
     * Valid state transitions:
     *   AVAILABLE   --> OCCUPIED  (vehicle parks)
     *   AVAILABLE   --> RESERVED  (reservation made)
     *   OCCUPIED    --> AVAILABLE (vehicle leaves)
     *   RESERVED    --> OCCUPIED  (reserved vehicle arrives)
     *   RESERVED    --> AVAILABLE (reservation cancelled)
     *   ANY         --> OUT_OF_ORDER (maintenance)
     *   OUT_OF_ORDER--> AVAILABLE (maintenance complete)
     */
    public boolean canTransitionTo(SpotStatus target) {
        return switch (this) {
            case AVAILABLE    -> target == OCCUPIED || target == RESERVED || target == OUT_OF_ORDER;
            case OCCUPIED     -> target == AVAILABLE || target == OUT_OF_ORDER;
            case RESERVED     -> target == OCCUPIED || target == AVAILABLE || target == OUT_OF_ORDER;
            case OUT_OF_ORDER -> target == AVAILABLE;
        };
    }
}
```

#### PaymentMethod and PaymentStatus

```java
public enum PaymentMethod {
    CASH,
    CREDIT_CARD,
    DEBIT_CARD
}

public enum PaymentStatus {
    PENDING,
    COMPLETED,
    FAILED,
    REFUNDED
}
```

---

### 4.2 Vehicle Hierarchy (Abstract --> Concrete)

> **OOP Concept**: Abstract class with concrete subclasses. Vehicle is abstract because different vehicle types have fundamentally different spot requirements and physical characteristics. You cannot instantiate a generic "Vehicle" -- it must be a Car, Motorcycle, or Bus.

#### Vehicle (Abstract Base)

```java
public abstract class Vehicle {
    private final String licensePlate;
    private final VehicleType vehicleType;
    private final LocalDateTime entryTime;
    private boolean handicapped;

    protected Vehicle(String licensePlate, VehicleType vehicleType) {
        Objects.requireNonNull(licensePlate, "licensePlate must not be null");
        Objects.requireNonNull(vehicleType, "vehicleType must not be null");
        this.licensePlate = licensePlate;
        this.vehicleType = vehicleType;
        this.entryTime = LocalDateTime.now();
        this.handicapped = false;
    }

    // --- Getters ---
    public String getLicensePlate()    { return licensePlate; }
    public VehicleType getVehicleType(){ return vehicleType; }
    public LocalDateTime getEntryTime(){ return entryTime; }
    public boolean isHandicapped()     { return handicapped; }

    public void setHandicapped(boolean handicapped) {
        this.handicapped = handicapped;
    }

    /**
     * Does this vehicle need multiple parking spots?
     * Default is false. Bus overrides to return true.
     * This is NOT abstract -- most vehicles need one spot.
     */
    public boolean needsMultipleSpots() {
        return false;
    }

    @Override
    public String toString() {
        return String.format("%s[plate=%s]", vehicleType, licensePlate);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Vehicle vehicle = (Vehicle) o;
        return licensePlate.equals(vehicle.licensePlate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(licensePlate);
    }
}
```

**Why abstract?** You never create `new Vehicle(...)`. A vehicle MUST be a specific type. The constructor is `protected`, forcing callers to use subclasses.

#### Car

```java
public class Car extends Vehicle {

    public Car(String licensePlate) {
        super(licensePlate, VehicleType.CAR);
    }

    /**
     * Convenience constructor for handicapped car parking.
     */
    public Car(String licensePlate, boolean handicapped) {
        super(licensePlate, VehicleType.CAR);
        setHandicapped(handicapped);
    }

    // needsMultipleSpots() inherited as false -- a car takes one spot
}
```

#### Motorcycle

```java
public class Motorcycle extends Vehicle {

    public Motorcycle(String licensePlate) {
        super(licensePlate, VehicleType.MOTORCYCLE);
    }

    // needsMultipleSpots() inherited as false
}
```

#### Bus

```java
public class Bus extends Vehicle {

    public Bus(String licensePlate) {
        super(licensePlate, VehicleType.BUS);
    }

    /**
     * A bus requires multiple large spots (typically 3-5 contiguous spots).
     * Overrides the default Vehicle behavior.
     */
    @Override
    public boolean needsMultipleSpots() {
        return true;
    }
}
```

**Interview point**: `needsMultipleSpots()` is a concrete method with a default return value, not an abstract method. This is deliberate -- most vehicles need one spot. Only Bus overrides it. This avoids forcing every subclass to implement something that is almost always `false`.

---

### 4.3 ParkingSpot Hierarchy (Abstract --> Concrete, Template Method)

> **OOP Concept**: Abstract class with Template Method pattern. `canFitVehicle()` is the abstract method that each concrete spot type overrides differently. This is the purest example of polymorphism in the Parking Lot problem.

#### ParkingSpot (Abstract Base)

```java
public abstract class ParkingSpot {
    private final String spotId;
    private final int floorNumber;
    private final int spotNumber;
    private final SpotType spotType;
    private SpotStatus status;
    private Vehicle currentVehicle;

    protected ParkingSpot(String spotId, int floorNumber, int spotNumber, SpotType spotType) {
        this.spotId = spotId;
        this.floorNumber = floorNumber;
        this.spotNumber = spotNumber;
        this.spotType = spotType;
        this.status = SpotStatus.AVAILABLE;
        this.currentVehicle = null;
    }

    /**
     * TEMPLATE METHOD: Each subclass defines its own vehicle compatibility rules.
     *
     * CompactSpot  -> accepts CAR, MOTORCYCLE
     * LargeSpot    -> accepts CAR, MOTORCYCLE, BUS (any vehicle)
     * MotorcycleSpot -> accepts MOTORCYCLE only
     * HandicappedSpot -> accepts CAR with handicapped flag
     *
     * This is THE key polymorphic method in the Parking Lot problem.
     */
    public abstract boolean canFitVehicle(Vehicle vehicle);

    /**
     * Parks a vehicle in this spot.
     * State transition: AVAILABLE -> OCCUPIED
     *
     * @throws IllegalStateException if spot is not available
     * @throws IllegalArgumentException if vehicle cannot fit
     */
    public synchronized void park(Vehicle vehicle) {
        if (!isAvailable()) {
            throw new IllegalStateException(
                "Spot " + spotId + " is not available. Current status: " + status);
        }
        if (!canFitVehicle(vehicle)) {
            throw new IllegalArgumentException(
                "Vehicle " + vehicle + " cannot fit in " + spotType + " spot " + spotId);
        }
        this.status = SpotStatus.OCCUPIED;
        this.currentVehicle = vehicle;
    }

    /**
     * Vacates this spot.
     * State transition: OCCUPIED -> AVAILABLE
     *
     * @throws IllegalStateException if spot is not occupied
     */
    public synchronized void vacate() {
        if (status != SpotStatus.OCCUPIED) {
            throw new IllegalStateException(
                "Cannot vacate spot " + spotId + ". Current status: " + status);
        }
        this.status = SpotStatus.AVAILABLE;
        this.currentVehicle = null;
    }

    public boolean isAvailable() {
        return status == SpotStatus.AVAILABLE;
    }

    // --- Getters ---
    public String getSpotId()           { return spotId; }
    public int getFloorNumber()         { return floorNumber; }
    public int getSpotNumber()          { return spotNumber; }
    public SpotType getSpotType()       { return spotType; }
    public SpotStatus getStatus()       { return status; }
    public Vehicle getCurrentVehicle()  { return currentVehicle; }

    public void setStatus(SpotStatus status) {
        if (!this.status.canTransitionTo(status)) {
            throw new IllegalStateException(
                "Invalid transition: " + this.status + " -> " + status);
        }
        this.status = status;
    }

    @Override
    public String toString() {
        return String.format("Spot[id=%s, floor=%d, type=%s, status=%s]",
                spotId, floorNumber, spotType, status);
    }
}
```

**Key design decisions:**
- `park()` and `vacate()` are `synchronized` -- per-spot lock for thread safety (see Section 8).
- `canFitVehicle()` is `abstract` -- forces every spot subclass to declare its rules explicitly.
- `setStatus()` validates transitions using `SpotStatus.canTransitionTo()` -- State pattern.

#### CompactSpot

```java
public class CompactSpot extends ParkingSpot {

    public CompactSpot(String spotId, int floorNumber, int spotNumber) {
        super(spotId, floorNumber, spotNumber, SpotType.COMPACT);
    }

    /**
     * Compact spots accept:
     *  - CAR: fits naturally
     *  - MOTORCYCLE: small enough to fit
     *  - BUS: too large, rejected
     */
    @Override
    public boolean canFitVehicle(Vehicle vehicle) {
        return vehicle.getVehicleType() == VehicleType.CAR
            || vehicle.getVehicleType() == VehicleType.MOTORCYCLE;
    }
}
```

#### LargeSpot

```java
public class LargeSpot extends ParkingSpot {

    public LargeSpot(String spotId, int floorNumber, int spotNumber) {
        super(spotId, floorNumber, spotNumber, SpotType.LARGE);
    }

    /**
     * Large spots accept ANY vehicle type:
     *  - CAR: fits easily
     *  - MOTORCYCLE: fits easily
     *  - BUS: fits (may need multiple large spots, but each individual spot accepts)
     */
    @Override
    public boolean canFitVehicle(Vehicle vehicle) {
        return true;  // Large spots accept all vehicle types
    }
}
```

#### MotorcycleSpot

```java
public class MotorcycleSpot extends ParkingSpot {

    public MotorcycleSpot(String spotId, int floorNumber, int spotNumber) {
        super(spotId, floorNumber, spotNumber, SpotType.MOTORCYCLE_SPOT);
    }

    /**
     * Motorcycle spots accept ONLY motorcycles.
     * Too small for cars or buses.
     */
    @Override
    public boolean canFitVehicle(Vehicle vehicle) {
        return vehicle.getVehicleType() == VehicleType.MOTORCYCLE;
    }
}
```

#### HandicappedSpot

```java
public class HandicappedSpot extends ParkingSpot {

    public HandicappedSpot(String spotId, int floorNumber, int spotNumber) {
        super(spotId, floorNumber, spotNumber, SpotType.HANDICAPPED);
    }

    /**
     * Handicapped spots accept ONLY cars with handicapped flag set.
     * Legally reserved for handicapped permit holders.
     */
    @Override
    public boolean canFitVehicle(Vehicle vehicle) {
        return vehicle.getVehicleType() == VehicleType.CAR
            && vehicle.isHandicapped();
    }
}
```

**Liskov Substitution in action**: Any code that works with `ParkingSpot` will work correctly with any subclass. When `ParkingService` calls `spot.canFitVehicle(vehicle)`, it does not know or care which specific spot type it is dealing with. The polymorphic dispatch handles it.

---

### 4.4 ParkingFloor

```java
public class ParkingFloor {
    private final int floorNumber;
    private final Map<SpotType, List<ParkingSpot>> spotsByType;
    private final DisplayBoard displayBoard;

    public ParkingFloor(int floorNumber) {
        this.floorNumber = floorNumber;
        this.spotsByType = new ConcurrentHashMap<>();
        for (SpotType type : SpotType.values()) {
            spotsByType.put(type, new CopyOnWriteArrayList<>());
        }
        this.displayBoard = new DisplayBoard(floorNumber);
    }

    /**
     * Adds a spot to this floor. Called during lot initialization.
     */
    public void addSpot(ParkingSpot spot) {
        spotsByType.get(spot.getSpotType()).add(spot);
        updateDisplay();
    }

    /**
     * Returns all available spots on this floor that can fit the given vehicle.
     *
     * This iterates over ALL spot types (not just the vehicle's default type)
     * because a CAR can fit in both COMPACT and LARGE spots.
     */
    public List<ParkingSpot> getAvailableSpots(VehicleType vehicleType) {
        List<ParkingSpot> available = new ArrayList<>();
        for (List<ParkingSpot> spots : spotsByType.values()) {
            for (ParkingSpot spot : spots) {
                // Polymorphic call -- each spot type's canFitVehicle is called
                if (spot.isAvailable() && spot.canFitVehicle(
                        createDummyVehicle(vehicleType))) {
                    available.add(spot);
                }
            }
        }
        return available;
    }

    /**
     * Count of available spots for a specific spot type.
     */
    public int getAvailableCount(SpotType spotType) {
        return (int) spotsByType.getOrDefault(spotType, Collections.emptyList())
                .stream()
                .filter(ParkingSpot::isAvailable)
                .count();
    }

    /**
     * Returns true if NO spots of ANY type are available.
     */
    public boolean isFull() {
        return spotsByType.values().stream()
                .flatMap(List::stream)
                .noneMatch(ParkingSpot::isAvailable);
    }

    public void updateDisplay() {
        for (SpotType type : SpotType.values()) {
            displayBoard.update(type, getAvailableCount(type));
        }
    }

    public int getFloorNumber()        { return floorNumber; }
    public DisplayBoard getDisplayBoard(){ return displayBoard; }
    public Map<SpotType, List<ParkingSpot>> getSpotsByType() { return spotsByType; }
}
```

---

### 4.5 ParkingLot (Singleton)

> **OOP Concept**: Singleton pattern. A parking lot system manages exactly ONE physical lot. The private constructor + static `getInstance()` enforces this.

```java
public class ParkingLot {
    // --- Singleton ---
    private static volatile ParkingLot INSTANCE;

    private final String name;
    private final String address;
    private final List<ParkingFloor> floors;
    private final List<EntryGate> entryGates;
    private final List<ExitGate> exitGates;

    /**
     * Private constructor -- cannot be called from outside.
     * Use getInstance() instead.
     */
    private ParkingLot(String name, String address) {
        this.name = name;
        this.address = address;
        this.floors = new CopyOnWriteArrayList<>();
        this.entryGates = new CopyOnWriteArrayList<>();
        this.exitGates = new CopyOnWriteArrayList<>();
    }

    /**
     * Double-checked locking Singleton.
     * Thread-safe and lazy-initialized.
     *
     * WHY double-checked locking?
     *   - synchronized on every call = expensive
     *   - volatile + double null check = sync only on first call
     *   - After initialization, reads are lock-free
     */
    public static ParkingLot getInstance() {
        if (INSTANCE == null) {
            synchronized (ParkingLot.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ParkingLot("Default Parking Lot", "123 Main St");
                }
            }
        }
        return INSTANCE;
    }

    /**
     * For testing: allows resetting the singleton.
     * In production, this would never be called.
     */
    static void resetInstance() {
        synchronized (ParkingLot.class) {
            INSTANCE = null;
        }
    }

    // --- Floor management ---
    public void addFloor(ParkingFloor floor) {
        floors.add(floor);
    }

    public List<ParkingFloor> getFloors() {
        return Collections.unmodifiableList(floors);
    }

    // --- Gate management ---
    public void addEntryGate(EntryGate gate)  { entryGates.add(gate); }
    public void addExitGate(ExitGate gate)    { exitGates.add(gate); }

    // --- Capacity queries ---
    public int getTotalCapacity() {
        return floors.stream()
                .flatMap(f -> f.getSpotsByType().values().stream())
                .mapToInt(List::size)
                .sum();
    }

    public int getAvailableCount() {
        return floors.stream()
                .flatMap(f -> f.getSpotsByType().values().stream())
                .flatMap(List::stream)
                .filter(ParkingSpot::isAvailable)
                .mapToInt(s -> 1)
                .sum();
    }

    public boolean isFull() {
        return floors.stream().allMatch(ParkingFloor::isFull);
    }

    public String getName()    { return name; }
    public String getAddress() { return address; }
}
```

**Interview follow-up**: "What if we need multiple parking lots?" Answer: Replace Singleton with a `ParkingLotManager` that holds a `Map<String, ParkingLot>`. The Singleton is per-lot, not per-system.

---

### 4.6 ParkingTicket (Builder Pattern)

```java
public class ParkingTicket {
    private final String ticketId;
    private final Vehicle vehicle;
    private final ParkingSpot parkingSpot;
    private final LocalDateTime entryTime;
    private LocalDateTime exitTime;
    private double amount;
    private boolean isPaid;

    // --- Private constructor, use Builder ---
    private ParkingTicket(TicketBuilder builder) {
        this.ticketId = builder.ticketId;
        this.vehicle = builder.vehicle;
        this.parkingSpot = builder.parkingSpot;
        this.entryTime = builder.entryTime != null ? builder.entryTime : LocalDateTime.now();
        this.exitTime = null;
        this.amount = 0.0;
        this.isPaid = false;
    }

    public static TicketBuilder builder() {
        return new TicketBuilder();
    }

    /**
     * Calculates how long the vehicle has been parked.
     * Uses exitTime if set, otherwise uses current time.
     */
    public Duration calculateDuration() {
        LocalDateTime end = (exitTime != null) ? exitTime : LocalDateTime.now();
        return Duration.between(entryTime, end);
    }

    public void setExitTime(LocalDateTime exitTime) {
        this.exitTime = exitTime;
    }

    public void markPaid(double amount) {
        this.amount = amount;
        this.isPaid = true;
    }

    // --- Getters ---
    public String getTicketId()         { return ticketId; }
    public Vehicle getVehicle()         { return vehicle; }
    public ParkingSpot getParkingSpot() { return parkingSpot; }
    public LocalDateTime getEntryTime() { return entryTime; }
    public LocalDateTime getExitTime()  { return exitTime; }
    public double getAmount()           { return amount; }
    public boolean isPaid()             { return isPaid; }

    @Override
    public String toString() {
        return String.format("Ticket[id=%s, vehicle=%s, spot=%s, entry=%s, paid=%s]",
                ticketId, vehicle, parkingSpot.getSpotId(), entryTime, isPaid);
    }

    // === Inner Builder ===
    public static class TicketBuilder {
        private String ticketId;
        private Vehicle vehicle;
        private ParkingSpot parkingSpot;
        private LocalDateTime entryTime;

        public TicketBuilder ticketId(String ticketId)        { this.ticketId = ticketId;       return this; }
        public TicketBuilder vehicle(Vehicle vehicle)          { this.vehicle = vehicle;         return this; }
        public TicketBuilder parkingSpot(ParkingSpot spot)     { this.parkingSpot = spot;        return this; }
        public TicketBuilder entryTime(LocalDateTime time)     { this.entryTime = time;          return this; }

        public ParkingTicket build() {
            Objects.requireNonNull(ticketId, "ticketId must not be null");
            Objects.requireNonNull(vehicle, "vehicle must not be null");
            Objects.requireNonNull(parkingSpot, "parkingSpot must not be null");
            return new ParkingTicket(this);
        }
    }
}
```

**Why Builder here?** A ParkingTicket has many fields, some optional (exitTime, amount). The Builder makes construction readable and enforces required fields at build time.

---

### 4.7 Payment

```java
public class Payment {
    private final String paymentId;
    private final String ticketId;
    private final double amount;
    private final PaymentMethod method;
    private PaymentStatus status;
    private final LocalDateTime timestamp;

    public Payment(String paymentId, String ticketId, double amount,
                   PaymentMethod method, PaymentStatus status) {
        this.paymentId = paymentId;
        this.ticketId = ticketId;
        this.amount = amount;
        this.method = method;
        this.status = status;
        this.timestamp = LocalDateTime.now();
    }

    public void markCompleted() { this.status = PaymentStatus.COMPLETED; }
    public void markFailed()    { this.status = PaymentStatus.FAILED; }
    public void markRefunded()  { this.status = PaymentStatus.REFUNDED; }

    // --- Getters ---
    public String getPaymentId()       { return paymentId; }
    public String getTicketId()        { return ticketId; }
    public double getAmount()          { return amount; }
    public PaymentMethod getMethod()   { return method; }
    public PaymentStatus getStatus()   { return status; }
    public LocalDateTime getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return String.format("Payment[id=%s, ticket=%s, amount=%.2f, method=%s, status=%s]",
                paymentId, ticketId, amount, method, status);
    }
}
```

---

### 4.8 EntryGate and ExitGate

```java
public class EntryGate {
    private final String gateId;
    private final int gateNumber;

    public EntryGate(String gateId, int gateNumber) {
        this.gateId = gateId;
        this.gateNumber = gateNumber;
    }

    /**
     * Issues a ticket when a vehicle enters.
     * Delegates actual logic to ParkingService -- this is just the physical gate.
     */
    public ParkingTicket issueTicket(Vehicle vehicle, ParkingService parkingService) {
        System.out.printf("[ENTRY GATE %d] Vehicle %s entering...%n", gateNumber, vehicle);
        return parkingService.parkVehicle(vehicle);
    }

    public String getGateId()  { return gateId; }
    public int getGateNumber() { return gateNumber; }
}

public class ExitGate {
    private final String gateId;
    private final int gateNumber;

    public ExitGate(String gateId, int gateNumber) {
        this.gateId = gateId;
        this.gateNumber = gateNumber;
    }

    /**
     * Processes exit when a vehicle leaves.
     * Delegates actual logic to ParkingService.
     */
    public Payment processExit(String ticketId, PaymentMethod method,
                               ParkingService parkingService) {
        System.out.printf("[EXIT GATE %d] Processing ticket %s...%n", gateNumber, ticketId);
        return parkingService.unparkVehicle(ticketId, method);
    }

    public String getGateId()  { return gateId; }
    public int getGateNumber() { return gateNumber; }
}
```

---

### 4.9 DisplayBoard

```java
public class DisplayBoard {
    private final int floorNumber;
    private final Map<SpotType, Integer> availableSpots;

    public DisplayBoard(int floorNumber) {
        this.floorNumber = floorNumber;
        this.availableSpots = new ConcurrentHashMap<>();
        for (SpotType type : SpotType.values()) {
            availableSpots.put(type, 0);
        }
    }

    /**
     * Update the count for a specific spot type.
     * Called by ParkingFloor.updateDisplay() after each park/unpark.
     */
    public void update(SpotType spotType, int count) {
        availableSpots.put(spotType, count);
    }

    /**
     * Returns a formatted ASCII display of availability.
     */
    public String show() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("╔══════════════════════════════════╗%n"));
        sb.append(String.format("║     FLOOR %d - AVAILABILITY       ║%n", floorNumber));
        sb.append(String.format("╠══════════════════════════════════╣%n"));
        for (Map.Entry<SpotType, Integer> entry : availableSpots.entrySet()) {
            sb.append(String.format("║  %-18s : %4d spots ║%n",
                    entry.getKey(), entry.getValue()));
        }
        sb.append(String.format("╚══════════════════════════════════╝%n"));
        return sb.toString();
    }

    public int getFloorNumber() { return floorNumber; }
    public Map<SpotType, Integer> getAvailableSpots() {
        return Collections.unmodifiableMap(availableSpots);
    }
}
```

---

### 4.10 Factory Classes

#### VehicleFactory

```java
public class VehicleFactory {

    /**
     * Factory Method: Creates the correct Vehicle subclass based on type.
     * Callers do not need to know about Car, Motorcycle, Bus constructors.
     */
    public static Vehicle createVehicle(VehicleType type, String licensePlate) {
        return switch (type) {
            case CAR        -> new Car(licensePlate);
            case MOTORCYCLE -> new Motorcycle(licensePlate);
            case BUS        -> new Bus(licensePlate);
        };
    }

    /**
     * Overload for handicapped vehicles.
     */
    public static Vehicle createVehicle(VehicleType type, String licensePlate,
                                        boolean handicapped) {
        Vehicle vehicle = createVehicle(type, licensePlate);
        vehicle.setHandicapped(handicapped);
        return vehicle;
    }
}
```

#### SpotFactory

```java
public class SpotFactory {

    private static final AtomicInteger spotCounter = new AtomicInteger(0);

    /**
     * Factory Method: Creates the correct ParkingSpot subclass based on type.
     */
    public static ParkingSpot createSpot(SpotType type, int floorNumber, int spotNumber) {
        String spotId = String.format("F%d-S%d-%s", floorNumber, spotNumber,
                type.name().substring(0, 1));
        return switch (type) {
            case COMPACT         -> new CompactSpot(spotId, floorNumber, spotNumber);
            case LARGE           -> new LargeSpot(spotId, floorNumber, spotNumber);
            case MOTORCYCLE_SPOT -> new MotorcycleSpot(spotId, floorNumber, spotNumber);
            case HANDICAPPED     -> new HandicappedSpot(spotId, floorNumber, spotNumber);
        };
    }
}
```

---

## 5. Interface Contracts

> These three interfaces are the backbone of the Strategy pattern in this system. Each defines a single method with a clear contract. This is Interface Segregation (ISP) at work -- small, focused interfaces.

### 5.1 ParkingStrategy

```java
/**
 * Strategy for selecting which parking spot to assign to a vehicle.
 *
 * Different implementations can optimize for different goals:
 *   - NearestFirstStrategy: best customer experience (short walk)
 *   - CompactFloorStrategy: best energy efficiency (empty floors save power)
 *
 * To add a new strategy: implement this interface, no existing code changes.
 * This is the Open-Closed Principle (OCP) in action.
 */
public interface ParkingStrategy {

    /**
     * Finds an available parking spot for the given vehicle in the lot.
     *
     * @param lot     the parking lot to search
     * @param vehicle the vehicle to park
     * @return an Optional containing the best available spot,
     *         or empty if no suitable spot exists
     */
    Optional<ParkingSpot> findSpot(ParkingLot lot, Vehicle vehicle);
}
```

### 5.2 PricingStrategy

```java
/**
 * Strategy for calculating parking fees.
 *
 * Different implementations handle different pricing models:
 *   - HourlyPricingStrategy: per-hour rates (most common)
 *   - FlatRatePricingStrategy: fixed daily rate (airports, events)
 *
 * Easy to extend with:
 *   - WeekendPricingStrategy: higher rates on weekends
 *   - FirstHourFreeStrategy: promotional pricing
 *   - DynamicPricingStrategy: surge pricing based on occupancy
 */
public interface PricingStrategy {

    /**
     * Calculates the parking fee for a ticket.
     *
     * @param ticket the parking ticket (contains vehicle type, entry/exit times)
     * @return the fee in dollars
     */
    double calculateFee(ParkingTicket ticket);
}
```

### 5.3 PaymentProcessor

```java
/**
 * Strategy for processing payments.
 *
 * Each implementation handles a different payment method:
 *   - CashPaymentProcessor: physical cash at kiosk
 *   - CreditCardPaymentProcessor: card swipe/tap
 *
 * Easy to extend with:
 *   - MobilePaymentProcessor: Apple Pay, Google Pay
 *   - WalletPaymentProcessor: prepaid parking wallet
 */
public interface PaymentProcessor {

    /**
     * Processes a payment of the given amount using the given method.
     *
     * @param amount the amount to charge
     * @param method the payment method
     * @return a Payment object with status (COMPLETED or FAILED)
     */
    Payment processPayment(double amount, PaymentMethod method);
}
```

---

## 6. Strategy Implementations

### 6.1 ParkingStrategy Implementations

#### NearestFirstStrategy

```java
/**
 * Scans floors from 1 to N, returns the FIRST available spot that fits the vehicle.
 * Optimizes for customer experience (shortest walk from gate to spot).
 *
 * Time complexity: O(F * S) where F = floors, S = spots per floor
 * In practice, short-circuits as soon as a spot is found.
 */
public class NearestFirstStrategy implements ParkingStrategy {

    @Override
    public Optional<ParkingSpot> findSpot(ParkingLot lot, Vehicle vehicle) {
        // Scan floors in order: floor 1, floor 2, ..., floor N
        for (ParkingFloor floor : lot.getFloors()) {
            // Get all available spots on this floor that can fit the vehicle
            List<ParkingSpot> availableSpots = floor.getAvailableSpots(vehicle.getVehicleType());

            if (!availableSpots.isEmpty()) {
                // Return the first available spot (nearest on this floor)
                // For handicapped vehicles, prefer HandicappedSpot first
                if (vehicle.isHandicapped()) {
                    Optional<ParkingSpot> handicapped = availableSpots.stream()
                            .filter(s -> s.getSpotType() == SpotType.HANDICAPPED)
                            .findFirst();
                    if (handicapped.isPresent()) {
                        return handicapped;
                    }
                }
                return Optional.of(availableSpots.get(0));
            }
        }
        return Optional.empty();  // No spot available anywhere
    }
}
```

#### CompactFloorStrategy

```java
/**
 * Fills one floor COMPLETELY before moving to the next floor.
 * Optimizes for energy efficiency: empty floors can have lights turned off.
 *
 * Algorithm:
 *   1. Find the floor with the MOST occupied spots (highest utilization)
 *   2. If that floor has an available spot, use it
 *   3. Otherwise, try the next most-utilized floor
 *   4. Last resort: any empty floor
 */
public class CompactFloorStrategy implements ParkingStrategy {

    @Override
    public Optional<ParkingSpot> findSpot(ParkingLot lot, Vehicle vehicle) {
        // Sort floors by utilization (descending) -- prefer fullest floor first
        List<ParkingFloor> sortedFloors = lot.getFloors().stream()
                .sorted((f1, f2) -> {
                    int used1 = getOccupiedCount(f1);
                    int used2 = getOccupiedCount(f2);
                    return Integer.compare(used2, used1);  // descending
                })
                .toList();

        for (ParkingFloor floor : sortedFloors) {
            List<ParkingSpot> available = floor.getAvailableSpots(vehicle.getVehicleType());
            if (!available.isEmpty()) {
                return Optional.of(available.get(0));
            }
        }
        return Optional.empty();
    }

    private int getOccupiedCount(ParkingFloor floor) {
        return (int) floor.getSpotsByType().values().stream()
                .flatMap(List::stream)
                .filter(s -> s.getStatus() == SpotStatus.OCCUPIED)
                .count();
    }
}
```

---

### 6.2 PricingStrategy Implementations

#### HourlyPricingStrategy

```java
/**
 * Charges per-hour rates based on vehicle type.
 *
 * Rates:
 *   MOTORCYCLE = $1.00 / hour
 *   CAR        = $2.00 / hour
 *   BUS        = $5.00 / hour
 *
 * Rules:
 *   - Partial hours round UP (2h 15m = 3 hours charged)
 *   - Minimum charge is 1 hour
 */
public class HourlyPricingStrategy implements PricingStrategy {

    private final Map<VehicleType, Double> hourlyRates;

    public HourlyPricingStrategy() {
        this.hourlyRates = Map.of(
            VehicleType.MOTORCYCLE, 1.0,
            VehicleType.CAR,        2.0,
            VehicleType.BUS,        5.0
        );
    }

    /**
     * Allows custom rates (Open-Closed: extend via constructor, not modification).
     */
    public HourlyPricingStrategy(Map<VehicleType, Double> customRates) {
        this.hourlyRates = Map.copyOf(customRates);
    }

    @Override
    public double calculateFee(ParkingTicket ticket) {
        Duration duration = ticket.calculateDuration();
        long totalMinutes = duration.toMinutes();

        // Round up to next hour, minimum 1 hour
        long hours = Math.max(1, (long) Math.ceil(totalMinutes / 60.0));

        double rate = hourlyRates.getOrDefault(
                ticket.getVehicle().getVehicleType(), 2.0);

        double fee = hours * rate;

        System.out.printf("[PRICING] %s parked for %d min (%d hours charged) @ $%.2f/hr = $%.2f%n",
                ticket.getVehicle().getVehicleType(), totalMinutes, hours, rate, fee);

        return fee;
    }
}
```

#### FlatRatePricingStrategy

```java
/**
 * Charges a fixed daily rate regardless of actual duration.
 * Common at airports and event venues.
 *
 * Rates:
 *   MOTORCYCLE = $10.00 / day
 *   CAR        = $20.00 / day
 *   BUS        = $50.00 / day
 */
public class FlatRatePricingStrategy implements PricingStrategy {

    private final Map<VehicleType, Double> dailyRates;

    public FlatRatePricingStrategy() {
        this.dailyRates = Map.of(
            VehicleType.MOTORCYCLE, 10.0,
            VehicleType.CAR,        20.0,
            VehicleType.BUS,        50.0
        );
    }

    public FlatRatePricingStrategy(Map<VehicleType, Double> customRates) {
        this.dailyRates = Map.copyOf(customRates);
    }

    @Override
    public double calculateFee(ParkingTicket ticket) {
        Duration duration = ticket.calculateDuration();
        long days = Math.max(1, (long) Math.ceil(duration.toHours() / 24.0));

        double rate = dailyRates.getOrDefault(
                ticket.getVehicle().getVehicleType(), 20.0);

        double fee = days * rate;

        System.out.printf("[PRICING] %s flat rate: %d day(s) @ $%.2f/day = $%.2f%n",
                ticket.getVehicle().getVehicleType(), days, rate, fee);

        return fee;
    }
}
```

#### Extending Pricing (Interview Talking Point)

```
Adding new pricing strategies requires ZERO changes to existing code:

// Weekend surge pricing
public class WeekendPricingStrategy implements PricingStrategy {
    private final PricingStrategy weekdayStrategy;
    private final double weekendMultiplier;
    
    @Override
    public double calculateFee(ParkingTicket ticket) {
        double baseFee = weekdayStrategy.calculateFee(ticket);
        if (isWeekend(ticket.getEntryTime())) {
            return baseFee * weekendMultiplier;
        }
        return baseFee;
    }
}

// First hour free (Decorator pattern on top of Strategy)
public class FirstHourFreeStrategy implements PricingStrategy {
    private final PricingStrategy baseStrategy;
    
    @Override
    public double calculateFee(ParkingTicket ticket) {
        Duration duration = ticket.calculateDuration();
        if (duration.toMinutes() <= 60) {
            return 0.0;  // First hour free
        }
        return baseStrategy.calculateFee(ticket);
    }
}
```

This is OCP in action: the system is **closed for modification** (no existing code changes) but **open for extension** (new strategy class, plug it in).

---

### 6.3 PaymentProcessor Implementations

#### CashPaymentProcessor

```java
/**
 * Simulates cash payment at a parking kiosk.
 * Cash payments always succeed (you hand over physical money).
 */
public class CashPaymentProcessor implements PaymentProcessor {

    private final AtomicLong paymentIdCounter = new AtomicLong(0);

    @Override
    public Payment processPayment(double amount, PaymentMethod method) {
        String paymentId = "PAY-CASH-" + paymentIdCounter.incrementAndGet();

        Payment payment = new Payment(paymentId, null, amount,
                PaymentMethod.CASH, PaymentStatus.COMPLETED);

        System.out.printf("[CASH] Received $%.2f. Payment %s COMPLETED.%n",
                amount, paymentId);

        return payment;
    }
}
```

#### CreditCardPaymentProcessor

```java
/**
 * Simulates credit/debit card payment.
 * Has a 95% success rate (simulating real-world card declines).
 */
public class CreditCardPaymentProcessor implements PaymentProcessor {

    private final AtomicLong paymentIdCounter = new AtomicLong(0);
    private final Random random = new Random();
    private static final double SUCCESS_RATE = 0.95;

    @Override
    public Payment processPayment(double amount, PaymentMethod method) {
        String paymentId = "PAY-CARD-" + paymentIdCounter.incrementAndGet();

        // Simulate card processing
        boolean success = random.nextDouble() < SUCCESS_RATE;
        PaymentStatus status = success ? PaymentStatus.COMPLETED : PaymentStatus.FAILED;

        Payment payment = new Payment(paymentId, null, amount, method, status);

        if (success) {
            System.out.printf("[CARD] Charged $%.2f to card. Payment %s COMPLETED.%n",
                    amount, paymentId);
        } else {
            System.out.printf("[CARD] Payment %s FAILED. Card declined.%n", paymentId);
        }

        return payment;
    }
}
```

---

## 7. Service Layer Design

### 7.1 ParkingService (Facade)

> **Design Pattern**: Facade. ParkingService is the SINGLE ENTRY POINT for the entire parking subsystem. It coordinates spot finding, ticket creation, payment processing, and display updates. Callers (controllers, gates) never interact directly with TicketService, PaymentService, etc.

```java
/**
 * FACADE: The main entry point for parking operations.
 *
 * Coordinates:
 *   - ParkingStrategy   (find spot)
 *   - TicketService      (create/find tickets)
 *   - PaymentService     (process payments)
 *   - AvailabilityService(update displays)
 *
 * Dependency Inversion: All dependencies are interfaces, not concrete classes.
 *   - parkingStrategy is ParkingStrategy (interface)
 *   - pricingStrategy is PricingStrategy (interface)
 *   - Not NearestFirstStrategy or HourlyPricingStrategy
 */
public class ParkingService {
    private final ParkingLot parkingLot;
    private final ParkingStrategy parkingStrategy;       // Interface dependency
    private final PricingStrategy pricingStrategy;       // Interface dependency
    private final TicketService ticketService;
    private final PaymentService paymentService;
    private final AvailabilityService availabilityService;

    /**
     * Constructor injection -- all dependencies injected.
     * Makes testing easy: pass mock strategies.
     */
    public ParkingService(ParkingLot parkingLot,
                          ParkingStrategy parkingStrategy,
                          PricingStrategy pricingStrategy,
                          TicketService ticketService,
                          PaymentService paymentService,
                          AvailabilityService availabilityService) {
        this.parkingLot = parkingLot;
        this.parkingStrategy = parkingStrategy;
        this.pricingStrategy = pricingStrategy;
        this.ticketService = ticketService;
        this.paymentService = paymentService;
        this.availabilityService = availabilityService;
    }

    /**
     * Parks a vehicle in the lot.
     *
     * Flow:
     *   1. Use ParkingStrategy to find an available spot
     *   2. Park the vehicle in the spot (synchronized)
     *   3. Create a ParkingTicket via TicketService
     *   4. Update display boards via AvailabilityService
     *   5. Return the ticket
     *
     * @throws ParkingFullException if no spot is available
     */
    public ParkingTicket parkVehicle(Vehicle vehicle) {
        // Step 1: Find a spot using the pluggable strategy
        Optional<ParkingSpot> spotOpt = parkingStrategy.findSpot(parkingLot, vehicle);

        if (spotOpt.isEmpty()) {
            throw new ParkingFullException(
                "No available spot for " + vehicle.getVehicleType()
                + " vehicle " + vehicle.getLicensePlate());
        }

        ParkingSpot spot = spotOpt.get();

        // Step 2: Park the vehicle (synchronized inside ParkingSpot.park())
        spot.park(vehicle);

        // Step 3: Create ticket
        ParkingTicket ticket = ticketService.generateTicket(vehicle, spot);

        // Step 4: Update displays
        availabilityService.updateDisplays(parkingLot);

        System.out.printf("[PARK] %s parked at spot %s (Floor %d). Ticket: %s%n",
                vehicle, spot.getSpotId(), spot.getFloorNumber(), ticket.getTicketId());

        return ticket;
    }

    /**
     * Un-parks a vehicle (processes exit).
     *
     * Flow:
     *   1. Find the ticket by ID
     *   2. Set exit time on ticket
     *   3. Calculate fee using PricingStrategy
     *   4. Process payment via PaymentService
     *   5. Vacate the spot
     *   6. Update display boards
     *   7. Return the payment
     *
     * @throws InvalidTicketException if ticket not found
     * @throws PaymentFailedException if payment processing fails
     */
    public Payment unparkVehicle(String ticketId, PaymentMethod paymentMethod) {
        // Step 1: Find ticket
        ParkingTicket ticket = ticketService.findTicket(ticketId)
                .orElseThrow(() -> new InvalidTicketException(
                    "Ticket not found: " + ticketId));

        if (ticket.isPaid()) {
            throw new InvalidTicketException(
                "Ticket " + ticketId + " has already been paid");
        }

        // Step 2: Set exit time
        ticket.setExitTime(LocalDateTime.now());

        // Step 3: Calculate fee using pluggable pricing strategy
        double fee = pricingStrategy.calculateFee(ticket);

        // Step 4: Process payment
        Payment payment = paymentService.processPayment(fee, paymentMethod);

        if (payment.getStatus() == PaymentStatus.FAILED) {
            throw new PaymentFailedException(
                "Payment failed for ticket " + ticketId);
        }

        // Step 5: Mark ticket paid and vacate spot
        ticket.markPaid(fee);
        ticket.getParkingSpot().vacate();

        // Step 6: Update displays
        availabilityService.updateDisplays(parkingLot);

        System.out.printf("[UNPARK] %s left spot %s. Fee: $%.2f. Payment: %s%n",
                ticket.getVehicle(), ticket.getParkingSpot().getSpotId(),
                fee, payment.getStatus());

        return payment;
    }

    /**
     * Returns current availability across all floors.
     */
    public Map<Integer, Map<SpotType, Integer>> getAvailability() {
        return availabilityService.getAvailability(parkingLot);
    }
}
```

---

### 7.2 TicketService

```java
public class TicketService {
    private final TicketRepository ticketRepository;
    private final AtomicLong ticketIdCounter = new AtomicLong(0);

    public TicketService(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    /**
     * Generates a new parking ticket.
     */
    public ParkingTicket generateTicket(Vehicle vehicle, ParkingSpot spot) {
        String ticketId = "TKT-" + ticketIdCounter.incrementAndGet();

        ParkingTicket ticket = ParkingTicket.builder()
                .ticketId(ticketId)
                .vehicle(vehicle)
                .parkingSpot(spot)
                .entryTime(LocalDateTime.now())
                .build();

        ticketRepository.save(ticket);
        return ticket;
    }

    /**
     * Finds a ticket by ID.
     */
    public Optional<ParkingTicket> findTicket(String ticketId) {
        return ticketRepository.findById(ticketId);
    }

    /**
     * Finds a ticket by vehicle license plate (for lookup at exit).
     */
    public Optional<ParkingTicket> findByVehicle(String licensePlate) {
        return ticketRepository.findByVehicle(licensePlate);
    }
}
```

---

### 7.3 PaymentService

```java
public class PaymentService {
    private final Map<PaymentMethod, PaymentProcessor> processors;

    public PaymentService() {
        this.processors = new EnumMap<>(PaymentMethod.class);
        // Default processors -- can be overridden via setter
        processors.put(PaymentMethod.CASH, new CashPaymentProcessor());
        processors.put(PaymentMethod.CREDIT_CARD, new CreditCardPaymentProcessor());
        processors.put(PaymentMethod.DEBIT_CARD, new CreditCardPaymentProcessor());
    }

    /**
     * Register a custom payment processor for a method.
     * Open-Closed: add new methods without changing existing code.
     */
    public void registerProcessor(PaymentMethod method, PaymentProcessor processor) {
        processors.put(method, processor);
    }

    /**
     * Process payment using the appropriate processor for the method.
     *
     * Strategy pattern: the processor is selected at runtime based on
     * the payment method. ParkingService does not know which processor
     * will handle the payment.
     */
    public Payment processPayment(double amount, PaymentMethod method) {
        PaymentProcessor processor = processors.get(method);
        if (processor == null) {
            throw new PaymentFailedException(
                "No payment processor registered for method: " + method);
        }
        return processor.processPayment(amount, method);
    }
}
```

---

### 7.4 AvailabilityService

```java
public class AvailabilityService {

    /**
     * Returns availability map: floorNumber -> (spotType -> availableCount)
     */
    public Map<Integer, Map<SpotType, Integer>> getAvailability(ParkingLot lot) {
        Map<Integer, Map<SpotType, Integer>> availability = new LinkedHashMap<>();

        for (ParkingFloor floor : lot.getFloors()) {
            Map<SpotType, Integer> floorAvailability = new EnumMap<>(SpotType.class);
            for (SpotType type : SpotType.values()) {
                floorAvailability.put(type, floor.getAvailableCount(type));
            }
            availability.put(floor.getFloorNumber(), floorAvailability);
        }

        return availability;
    }

    /**
     * Updates all display boards across all floors.
     * Called after every park/unpark operation.
     *
     * Observer pattern: The display boards "observe" availability changes.
     * In this implementation, the update is push-based (called explicitly).
     * A more sophisticated version would use an event system.
     */
    public void updateDisplays(ParkingLot lot) {
        for (ParkingFloor floor : lot.getFloors()) {
            floor.updateDisplay();
        }
    }

    /**
     * Formats availability for console display.
     */
    public String formatAvailability(ParkingLot lot) {
        StringBuilder sb = new StringBuilder();
        sb.append("╔══════════════════════════════════════════════════╗\n");
        sb.append("║          PARKING LOT AVAILABILITY               ║\n");
        sb.append("╠══════════════════════════════════════════════════╣\n");

        for (ParkingFloor floor : lot.getFloors()) {
            sb.append(floor.getDisplayBoard().show());
        }

        int total = lot.getTotalCapacity();
        int available = lot.getAvailableCount();
        sb.append(String.format("║  TOTAL: %d / %d spots available           ║%n",
                available, total));
        sb.append("╚══════════════════════════════════════════════════╝\n");

        return sb.toString();
    }
}
```

---

## 8. Concurrency Considerations

> This is CRITICAL for parking lot systems. The core race condition: two cars arrive simultaneously and get assigned the same spot. Both try to park, but only one can.

### 8.1 The Problem

```
Thread-1 (Car A)                    Thread-2 (Car B)
─────────────────                   ─────────────────
1. findSpot() -> Spot-42            1. findSpot() -> Spot-42  (SAME SPOT!)
2. spot.isAvailable() -> true       2. spot.isAvailable() -> true
3. spot.park(carA)                  3. spot.park(carB)   <-- CONFLICT!
   status = OCCUPIED                   status = OCCUPIED (overwrites carA!)
   vehicle = carA                      vehicle = carB

RESULT: Car A thinks it's parked at Spot-42, but Car B is actually there.
        Two tickets, one spot. Chaos.
```

### 8.2 Approach 1: Per-Spot Synchronized (Fine-Grained Locking)

This is the approach used in our `ParkingSpot.park()` method:

```java
public abstract class ParkingSpot {
    // ...

    /**
     * synchronized on `this` (the specific ParkingSpot instance).
     * Only one thread can park at THIS spot at a time.
     *
     * Tradeoff:
     *   + High throughput: different spots can be parked simultaneously
     *   + No contention between floors
     *   - Two threads can still both SELECT the same spot,
     *     but only one will successfully park (the other gets exception)
     */
    public synchronized void park(Vehicle vehicle) {
        if (!isAvailable()) {
            throw new IllegalStateException(
                "Spot " + spotId + " is not available. Status: " + status);
        }
        if (!canFitVehicle(vehicle)) {
            throw new IllegalArgumentException(
                "Vehicle " + vehicle + " cannot fit in spot " + spotId);
        }
        this.status = SpotStatus.OCCUPIED;
        this.currentVehicle = vehicle;
    }

    public synchronized void vacate() {
        if (status != SpotStatus.OCCUPIED) {
            throw new IllegalStateException(
                "Cannot vacate spot " + spotId + ". Status: " + status);
        }
        this.status = SpotStatus.AVAILABLE;
        this.currentVehicle = null;
    }
}
```

The caller (ParkingService) must handle the exception and retry:

```java
public ParkingTicket parkVehicle(Vehicle vehicle) {
    int maxRetries = 3;
    for (int attempt = 1; attempt <= maxRetries; attempt++) {
        Optional<ParkingSpot> spotOpt = parkingStrategy.findSpot(parkingLot, vehicle);

        if (spotOpt.isEmpty()) {
            throw new ParkingFullException("No spot available");
        }

        ParkingSpot spot = spotOpt.get();
        try {
            spot.park(vehicle);  // synchronized -- may throw if another thread got here first
            ParkingTicket ticket = ticketService.generateTicket(vehicle, spot);
            availabilityService.updateDisplays(parkingLot);
            return ticket;
        } catch (IllegalStateException e) {
            // Spot was taken between findSpot() and park() -- retry
            System.out.printf("[RETRY] Spot %s taken. Attempt %d/%d%n",
                    spot.getSpotId(), attempt, maxRetries);
        }
    }
    throw new SpotNotAvailableException("Failed to park after " + maxRetries + " retries");
}
```

### 8.3 Approach 2: Floor-Level ReentrantLock (Coarse-Grained Locking)

```java
public class ParkingFloor {
    private final ReentrantLock floorLock = new ReentrantLock();

    /**
     * Atomically finds AND parks a vehicle on this floor.
     * No gap between "find" and "park" -- eliminates the race condition entirely.
     *
     * Tradeoff:
     *   + No retry needed: find + park is atomic
     *   + Simpler code in ParkingService
     *   - Lower throughput: only one vehicle can park on a floor at a time
     *   - Contention on busy floors
     */
    public Optional<ParkingSpot> findAndPark(Vehicle vehicle) {
        floorLock.lock();
        try {
            List<ParkingSpot> available = getAvailableSpots(vehicle.getVehicleType());
            if (available.isEmpty()) {
                return Optional.empty();
            }

            ParkingSpot spot = available.get(0);
            spot.park(vehicle);  // safe: we hold the lock
            return Optional.of(spot);
        } finally {
            floorLock.unlock();
        }
    }
}
```

### 8.4 Tradeoff Comparison

```
┌─────────────────────┬─────────────────────────┬─────────────────────────┐
│                     │ Per-Spot Synchronized   │ Floor-Level Lock        │
├─────────────────────┼─────────────────────────┼─────────────────────────┤
│ Granularity         │ Fine (per spot)         │ Coarse (per floor)      │
│ Throughput          │ HIGH (parallel parking  │ LOWER (one car per      │
│                     │ on different spots)     │ floor at a time)        │
│ Race condition?     │ Possible (retry needed) │ Eliminated (atomic      │
│                     │                         │ find+park)              │
│ Code complexity     │ Higher (retry logic)    │ Simpler                 │
│ Deadlock risk       │ None (single lock)      │ None (single lock)      │
│ Best for            │ High-traffic lots       │ Small-medium lots       │
│ Interview answer    │ Show BOTH approaches    │ Explain tradeoff        │
└─────────────────────┴─────────────────────────┴─────────────────────────┘
```

### 8.5 AtomicInteger for Available Counts

```java
/**
 * For O(1) availability queries, maintain atomic counters.
 * Avoids scanning all spots just to count available ones.
 */
public class ParkingFloor {
    private final Map<SpotType, AtomicInteger> availableCounts;

    public ParkingFloor(int floorNumber) {
        this.availableCounts = new ConcurrentHashMap<>();
        for (SpotType type : SpotType.values()) {
            availableCounts.put(type, new AtomicInteger(0));
        }
    }

    // Called when a spot becomes occupied
    public void decrementAvailable(SpotType type) {
        availableCounts.get(type).decrementAndGet();
    }

    // Called when a spot becomes available
    public void incrementAvailable(SpotType type) {
        availableCounts.get(type).incrementAndGet();
    }

    // O(1) instead of O(n) scan
    public int getAvailableCount(SpotType type) {
        return availableCounts.get(type).get();
    }
}
```

---

## 9. SOLID Principles Applied

> This section maps EACH SOLID principle to concrete code in this project. In interviews, you should be able to point to specific classes and explain why they follow SOLID.

### 9.1 S -- Single Responsibility Principle

```
Each class has ONE reason to change:

  Class               │ Single Responsibility           │ Does NOT do
  ────────────────────┼─────────────────────────────────┼──────────────────────
  Vehicle             │ Holds vehicle identity & type   │ Does NOT find parking spots
  ParkingSpot         │ Manages spot state (park/vacate)│ Does NOT calculate fees
  ParkingTicket       │ Records a parking session       │ Does NOT process payments
  PricingStrategy     │ Calculates parking fees         │ Does NOT manage spots
  PaymentProcessor    │ Processes a payment             │ Does NOT generate tickets
  ParkingService      │ Orchestrates the workflow       │ Does NOT implement pricing
  TicketService       │ Manages ticket lifecycle        │ Does NOT find spots
  DisplayBoard        │ Shows availability              │ Does NOT modify spots

  WHY IT MATTERS:
  "What if we change the pricing model?"
  Answer: Only PricingStrategy implementations change.
  ParkingSpot, Vehicle, Ticket -- NONE of them change.
```

### 9.2 O -- Open-Closed Principle

```
Open for extension, closed for modification:

  Extension Point          │ How to Extend                     │ What Does NOT Change
  ─────────────────────────┼───────────────────────────────────┼─────────────────────
  New vehicle type         │ Create TruckVehicle extends       │ Vehicle base class
    (e.g., Truck)          │ Vehicle. Add TRUCK to VehicleType.│ ParkingSpot, ParkingService
                           │                                   │
  New spot type            │ Create EVChargingSpot extends     │ ParkingSpot base class
    (e.g., EV Charging)    │ ParkingSpot. Override canFit.     │ CompactSpot, LargeSpot
                           │                                   │
  New parking strategy     │ Implement ParkingStrategy.        │ NearestFirstStrategy
    (e.g., random)         │ Inject via config.                │ CompactFloorStrategy
                           │                                   │
  New pricing model        │ Implement PricingStrategy.        │ HourlyPricingStrategy
    (e.g., surge)          │ Inject via config.                │ FlatRatePricingStrategy
                           │                                   │
  New payment method       │ Implement PaymentProcessor.       │ CashPaymentProcessor
    (e.g., Apple Pay)      │ Register in PaymentService.       │ CreditCardPaymentProcessor
```

### 9.3 L -- Liskov Substitution Principle

```
Any subclass can be used wherever its superclass is expected, without
breaking correctness.

  Test 1: Vehicle Substitution
  ─────────────────────────────
  ParkingStrategy.findSpot(lot, Vehicle vehicle)
    - Pass a Car       -> works correctly (finds compact/large spot)
    - Pass a Motorcycle-> works correctly (finds motorcycle/compact/large spot)
    - Pass a Bus       -> works correctly (finds large spot)
    The method NEVER checks instanceof. It uses vehicle.getVehicleType()
    and spot.canFitVehicle(vehicle) -- pure polymorphism.

  Test 2: ParkingSpot Substitution
  ─────────────────────────────────
  ParkingFloor stores List<ParkingSpot> (the abstract type).
  When it calls spot.canFitVehicle(vehicle), it does not know if it's
  a CompactSpot, LargeSpot, etc. Each subclass returns the correct answer.
  The calling code never breaks.

  Test 3: Strategy Substitution
  ─────────────────────────────
  ParkingService depends on ParkingStrategy (interface).
  Whether it's NearestFirstStrategy or CompactFloorStrategy,
  parkVehicle() works identically. Swap strategies via config, no code changes.

  ANTI-PATTERN (violates LSP):
  ─────────────────────────────
  if (spot instanceof CompactSpot) {
      // do compact logic
  } else if (spot instanceof LargeSpot) {
      // do large logic
  }
  --> This BREAKS when you add a new spot type. Use polymorphism instead.
```

### 9.4 I -- Interface Segregation Principle

```
Interfaces are small and focused. No class is forced to implement
methods it does not use.

  Interface           │ Methods  │ Why It's Segregated
  ────────────────────┼──────────┼─────────────────────────────────────
  ParkingStrategy     │ 1 method │ findSpot() only. Does not calculate fees.
  PricingStrategy     │ 1 method │ calculateFee() only. Does not find spots.
  PaymentProcessor    │ 1 method │ processPayment() only. Does not manage tickets.
  SpotRepository      │ 4 methods│ Only spot CRUD. Not ticket CRUD.
  TicketRepository    │ 3 methods│ Only ticket CRUD. Not spot CRUD.

  ANTI-PATTERN (violates ISP):
  ─────────────────────────────
  interface ParkingOperations {
      Optional<ParkingSpot> findSpot(lot, vehicle);
      double calculateFee(ticket);
      Payment processPayment(amount, method);
      void updateDisplay(lot);
  }
  --> CashPaymentProcessor would be FORCED to implement findSpot(),
      calculateFee(), updateDisplay() -- none of which it needs.
```

### 9.5 D -- Dependency Inversion Principle

```
High-level modules depend on abstractions, not concrete implementations.

  High-Level Module    │ Depends On (Interface)   │ NOT On (Concrete)
  ─────────────────────┼──────────────────────────┼───────────────────────
  ParkingService       │ ParkingStrategy          │ NearestFirstStrategy
  ParkingService       │ PricingStrategy          │ HourlyPricingStrategy
  PaymentService       │ PaymentProcessor         │ CashPaymentProcessor
  TicketService        │ TicketRepository         │ InMemoryTicketRepository
  AvailabilityService  │ (ParkingLot -- could be  │ (in-memory lot)
                       │  an interface too)       │

  In the constructor:
  ─────────────────────
  // GOOD: depends on interface
  public ParkingService(ParkingStrategy strategy, ...) {
      this.parkingStrategy = strategy;  // could be ANY implementation
  }

  // BAD: depends on concrete
  public ParkingService() {
      this.parkingStrategy = new NearestFirstStrategy();  // locked in!
  }

  HOW IT'S WIRED (AppConfig):
  ────────────────────────────
  ParkingStrategy strategy = new NearestFirstStrategy();  // choose here
  PricingStrategy pricing = new HourlyPricingStrategy();  // choose here
  ParkingService service = new ParkingService(lot, strategy, pricing, ...);
  // To switch strategies, change ONE line in AppConfig. Zero service changes.
```

---

## 10. Sample Workflows

### 10.1 Vehicle Enters and Parks Successfully

```
  CAR (plate=ABC-123) arrives at Entry Gate 1
  ═══════════════════════════════════════════

  Step 1: Entry Gate receives vehicle
  ┌──────────────┐      ┌──────────────────┐
  │  EntryGate   │─────>│  ParkingService   │   parkVehicle(car)
  │  (Gate 1)    │      │  (Facade)         │
  └──────────────┘      └──────┬───────────┘
                               │
  Step 2: ParkingService delegates to ParkingStrategy
                               │
                        ┌──────▼───────────┐
                        │ NearestFirst     │   findSpot(lot, car)
                        │ Strategy         │
                        └──────┬───────────┘
                               │
  Step 3: Strategy scans floors
                               │
     Floor 1: getAvailableSpots(CAR)
       ├── CompactSpot-1: canFitVehicle(car) = true, isAvailable = true  <-- SELECTED
       ├── CompactSpot-2: canFitVehicle(car) = true, isAvailable = true
       └── LargeSpot-1:   canFitVehicle(car) = true, isAvailable = true
                               │
     Returns: Optional.of(CompactSpot-1)
                               │
  Step 4: Park vehicle in spot (synchronized)
                               │
                        ┌──────▼───────────┐
                        │ CompactSpot-1    │   park(car)
                        │ status: AVAILABLE│──> status: OCCUPIED
                        │ vehicle: null    │──> vehicle: car
                        └──────────────────┘
                               │
  Step 5: Create ticket
                               │
                        ┌──────▼───────────┐
                        │ TicketService    │   generateTicket(car, spot)
                        └──────┬───────────┘
                               │
                        Returns: ParkingTicket[
                          ticketId = "TKT-1",
                          vehicle  = CAR[plate=ABC-123],
                          spot     = CompactSpot-1,
                          entry    = 2026-04-18T10:00:00
                        ]
                               │
  Step 6: Update display boards
                               │
                        ┌──────▼───────────┐
                        │ AvailabilityService│  updateDisplays(lot)
                        └──────┬───────────┘
                               │
     Floor 1 Display: COMPACT: 9 spots  (was 10)
                               │
  Step 7: Return ticket to driver
     [ENTRY GATE 1] Ticket TKT-1 issued. Spot: F1-CompactSpot-1
```

### 10.2 Vehicle Exits with Payment

```
  CAR (plate=ABC-123) exits at Exit Gate 2 after 3 hours 15 minutes
  ═══════════════════════════════════════════════════════════════════

  Step 1: Exit Gate receives ticket ID
  ┌──────────────┐      ┌──────────────────┐
  │  ExitGate    │─────>│  ParkingService   │   unparkVehicle("TKT-1", CREDIT_CARD)
  │  (Gate 2)    │      │  (Facade)         │
  └──────────────┘      └──────┬───────────┘
                               │
  Step 2: Find the ticket
                        ┌──────▼───────────┐
                        │ TicketService    │   findTicket("TKT-1")
                        └──────┬───────────┘
                               │
                        Returns: ParkingTicket[entry=10:00, vehicle=CAR]
                               │
  Step 3: Set exit time
                        ticket.setExitTime(13:15)
                        ticket.calculateDuration() = 3h 15m
                               │
  Step 4: Calculate fee (PricingStrategy)
                        ┌──────▼───────────┐
                        │ HourlyPricing    │   calculateFee(ticket)
                        │ Strategy         │
                        └──────┬───────────┘
                               │
     Rate: CAR = $2.00/hr
     Duration: 3h 15m -> rounds UP to 4 hours
     Fee: 4 * $2.00 = $8.00
                               │
  Step 5: Process payment (PaymentProcessor)
                        ┌──────▼───────────┐
                        │ CreditCard       │   processPayment($8.00, CREDIT_CARD)
                        │ Processor        │
                        └──────┬───────────┘
                               │
     [CARD] Charged $8.00 to card. Payment PAY-CARD-1 COMPLETED.
                               │
  Step 6: Vacate spot (synchronized)
                        ┌──────▼───────────┐
                        │ CompactSpot-1    │   vacate()
                        │ status: OCCUPIED │──> status: AVAILABLE
                        │ vehicle: car     │──> vehicle: null
                        └──────────────────┘
                               │
  Step 7: Update displays
     Floor 1 Display: COMPACT: 10 spots  (was 9)
                               │
  Step 8: Open exit gate
     [EXIT GATE 2] Vehicle ABC-123 exited. Fee: $8.00 paid via CREDIT_CARD.
```

### 10.3 Parking Lot Full -- Deny Entry

```
  BUS (plate=BUS-999) arrives, but ALL large spots are occupied
  ══════════════════════════════════════════════════════════════

  Step 1: ParkingService.parkVehicle(bus)

  Step 2: NearestFirstStrategy.findSpot(lot, bus)
     Floor 1: getAvailableSpots(BUS)
       ├── LargeSpot-1: OCCUPIED  (skip)
       ├── LargeSpot-2: OCCUPIED  (skip)
       └── CompactSpot-1: canFitVehicle(bus) = FALSE  (compact rejects bus)
     Floor 2: getAvailableSpots(BUS)
       ├── LargeSpot-3: OCCUPIED  (skip)
       └── LargeSpot-4: OCCUPIED  (skip)

     Returns: Optional.empty()

  Step 3: ParkingService throws ParkingFullException
     "No available spot for BUS vehicle BUS-999"

  Step 4: Entry Gate denies entry
     [ENTRY GATE 1] DENIED: Parking lot full for BUS vehicles.
     Display shows: LARGE spots available = 0
```

### 10.4 Bus Needs Large Spot -- CompactSpot Rejects, LargeSpot Accepts

```
  Demonstrating polymorphic canFitVehicle():
  ══════════════════════════════════════════

  Bus bus = new Bus("BUS-456");

  CompactSpot compact = new CompactSpot("F1-S1-C", 1, 1);
  LargeSpot   large   = new LargeSpot("F1-S10-L", 1, 10);

  compact.canFitVehicle(bus)   --> false  (CompactSpot rejects BUS)
  large.canFitVehicle(bus)     --> true   (LargeSpot accepts any vehicle)

  The ParkingStrategy iterates through spots:
    for (ParkingSpot spot : floor.getAllSpots()) {
        if (spot.isAvailable() && spot.canFitVehicle(bus)) {
            return Optional.of(spot);  // Only LargeSpot matches
        }
    }

  KEY INSIGHT: The strategy does NOT check instanceof.
  It relies on polymorphic dispatch via canFitVehicle().
  This is Liskov Substitution in action.
```

### 10.5 Display Board Update After Park/Unpark

```
  BEFORE PARKING:
  ╔══════════════════════════════════╗
  ║     FLOOR 1 - AVAILABILITY      ║
  ╠══════════════════════════════════╣
  ║  MOTORCYCLE_SPOT :   20 spots   ║
  ║  COMPACT         :   50 spots   ║
  ║  LARGE           :   10 spots   ║
  ║  HANDICAPPED     :    5 spots   ║
  ╚══════════════════════════════════╝

  --> Car parks in Compact spot

  AFTER PARKING:
  ╔══════════════════════════════════╗
  ║     FLOOR 1 - AVAILABILITY      ║
  ╠══════════════════════════════════╣
  ║  MOTORCYCLE_SPOT :   20 spots   ║
  ║  COMPACT         :   49 spots   ║  <-- Decreased by 1
  ║  LARGE           :   10 spots   ║
  ║  HANDICAPPED     :    5 spots   ║
  ╚══════════════════════════════════╝

  --> Car leaves Compact spot

  AFTER UNPARKING:
  ╔══════════════════════════════════╗
  ║     FLOOR 1 - AVAILABILITY      ║
  ╠══════════════════════════════════╣
  ║  MOTORCYCLE_SPOT :   20 spots   ║
  ║  COMPACT         :   50 spots   ║  <-- Restored
  ║  LARGE           :   10 spots   ║
  ║  HANDICAPPED     :    5 spots   ║
  ╚══════════════════════════════════╝
```

---

## 11. Design Patterns Used

> The Parking Lot system uses MORE design patterns than any other classic LLD problem. This is why it is a favorite interview question.

### 11.1 Strategy Pattern (3 Separate Uses)

```
  Usage 1: SPOT ASSIGNMENT
  ────────────────────────
  Interface:       ParkingStrategy
  Implementations: NearestFirstStrategy, CompactFloorStrategy
  Used by:         ParkingService.parkVehicle()
  Switch at:       AppConfig (constructor injection)
  Benefit:         Change parking algorithm without touching ParkingService

  Usage 2: PRICING
  ────────────────────────
  Interface:       PricingStrategy
  Implementations: HourlyPricingStrategy, FlatRatePricingStrategy
  Used by:         ParkingService.unparkVehicle()
  Switch at:       AppConfig (constructor injection)
  Benefit:         Change pricing model without touching ParkingService

  Usage 3: PAYMENT PROCESSING
  ────────────────────────
  Interface:       PaymentProcessor
  Implementations: CashPaymentProcessor, CreditCardPaymentProcessor
  Used by:         PaymentService.processPayment()
  Switch at:       Runtime (based on PaymentMethod enum)
  Benefit:         Add new payment methods without modifying PaymentService
```

### 11.2 Singleton Pattern

```
  Class:   ParkingLot
  Why:     One physical parking lot = one instance in the system
  How:     Private constructor + static getInstance()
  Thread:  Double-checked locking with volatile
  Caveat:  For multi-lot systems, replace with ParkingLotManager

  private static volatile ParkingLot INSTANCE;

  public static ParkingLot getInstance() {
      if (INSTANCE == null) {                    // First check (no lock)
          synchronized (ParkingLot.class) {      // Lock
              if (INSTANCE == null) {            // Second check (with lock)
                  INSTANCE = new ParkingLot(...);
              }
          }
      }
      return INSTANCE;
  }
```

### 11.3 Factory Pattern

```
  VehicleFactory.createVehicle(VehicleType.CAR, "ABC-123")
    --> returns new Car("ABC-123")

  SpotFactory.createSpot(SpotType.COMPACT, floor=1, spot=5)
    --> returns new CompactSpot("F1-S5-C", 1, 5)

  WHY FACTORY?
  Callers do not need to know about concrete subclass constructors.
  When you add a new vehicle type (e.g., Truck), you add ONE case
  to the factory switch. Callers are unchanged.

  ALTERNATIVE: Abstract Factory
  If you need to create "families" of related objects
  (e.g., a CompactFloor factory that creates compact spots + compact displays),
  use Abstract Factory. Overkill for this problem.
```

### 11.4 Builder Pattern

```
  Class:   ParkingTicket (inner TicketBuilder)
  Why:     ParkingTicket has many fields, some optional.
           Telescoping constructors would be unreadable.

  ParkingTicket ticket = ParkingTicket.builder()
      .ticketId("TKT-42")
      .vehicle(car)
      .parkingSpot(spot)
      .entryTime(LocalDateTime.now())
      .build();

  build() validates required fields (ticketId, vehicle, spot).
  Optional fields (exitTime, amount) are set later.
```

### 11.5 Facade Pattern

```
  Class:   ParkingService
  Hides:   ParkingStrategy + TicketService + PaymentService + AvailabilityService

  Without Facade:
  ─────────────────
  ParkingSpot spot = strategy.findSpot(lot, car);
  spot.park(car);
  ParkingTicket ticket = ticketService.generateTicket(car, spot);
  availabilityService.updateDisplays(lot);
  // 4 steps, 4 different objects

  With Facade:
  ─────────────────
  ParkingTicket ticket = parkingService.parkVehicle(car);
  // 1 step, 1 object. The Facade handles the rest.

  This is critical for controllers and gates -- they call ONE method,
  not orchestrate a multi-step workflow.
```

### 11.6 State Pattern

```
  Implemented via: SpotStatus enum + canTransitionTo()

  State Transitions:
  ──────────────────
  AVAILABLE ──park()──> OCCUPIED ──vacate()──> AVAILABLE
  AVAILABLE ──reserve()──> RESERVED ──arrive()──> OCCUPIED
  ANY ──maintenance()──> OUT_OF_ORDER ──fix()──> AVAILABLE

  Invalid transitions throw IllegalStateException:
    OCCUPIED ──park()──> ERROR (can't park in occupied spot)
    AVAILABLE ──vacate()──> ERROR (can't vacate an empty spot)

  The SpotStatus.canTransitionTo() method enforces valid transitions,
  preventing illegal state changes that could corrupt the system.
```

### 11.7 Observer Pattern

```
  Subject:    ParkingFloor (availability changes)
  Observer:   DisplayBoard (reacts to changes)
  Trigger:    Every park() or vacate() operation

  Flow:
  1. ParkingSpot.park(vehicle) -- spot becomes OCCUPIED
  2. ParkingService calls availabilityService.updateDisplays(lot)
  3. AvailabilityService iterates floors, calls floor.updateDisplay()
  4. ParkingFloor recalculates available counts
  5. DisplayBoard.update(spotType, newCount) -- display refreshes

  In a production system, this would use an event bus:
    EventBus.publish(new SpotOccupiedEvent(spot));
    DisplayBoardListener.onEvent(SpotOccupiedEvent event) { ... }

  For interview purposes, the push-based approach shown here is sufficient.
```

### 11.8 Repository Pattern

```
  Interface:       SpotRepository, TicketRepository, VehicleRepository
  Implementation:  InMemorySpotRepository, InMemoryTicketRepository, etc.

  Abstracts data access:
    - Service layer calls repository.save(ticket)
    - It does not know if data goes to HashMap, MySQL, Redis, or DynamoDB
    - Swap implementations via dependency injection

  For testing: InMemoryRepository (fast, no external dependencies)
  For production: JpaRepository or DynamoDbRepository
```

### 11.9 Template Method Pattern

```
  Class:    ParkingSpot (abstract)
  Template: park() method calls canFitVehicle() -- which is abstract

  public void park(Vehicle vehicle) {
      if (!canFitVehicle(vehicle)) {   // <-- abstract method call
          throw new IllegalArgumentException(...);
      }
      this.status = SpotStatus.OCCUPIED;
      this.currentVehicle = vehicle;
  }

  The park() method defines the TEMPLATE (check fit, then occupy).
  Subclasses provide the VARIANT STEP (canFitVehicle rules).

  CompactSpot:    canFitVehicle -> CAR or MOTORCYCLE
  LargeSpot:      canFitVehicle -> any vehicle
  MotorcycleSpot: canFitVehicle -> MOTORCYCLE only
  HandicappedSpot:canFitVehicle -> CAR with handicapped flag

  The park() template NEVER changes. Only canFitVehicle() varies per subclass.
```

### Pattern Summary Table

```
  ┌──────────────────┬───────────────────────────┬──────────────────────────┐
  │ Pattern          │ Where Applied             │ Why                      │
  ├──────────────────┼───────────────────────────┼──────────────────────────┤
  │ Strategy         │ ParkingStrategy           │ Pluggable spot selection │
  │                  │ PricingStrategy           │ Pluggable fee calc       │
  │                  │ PaymentProcessor          │ Pluggable payment        │
  ├──────────────────┼───────────────────────────┼──────────────────────────┤
  │ Singleton        │ ParkingLot                │ One lot instance         │
  ├──────────────────┼───────────────────────────┼──────────────────────────┤
  │ Factory          │ VehicleFactory            │ Create correct subclass  │
  │                  │ SpotFactory               │ from type enum           │
  ├──────────────────┼───────────────────────────┼──────────────────────────┤
  │ Builder          │ ParkingTicket             │ Complex object creation  │
  ├──────────────────┼───────────────────────────┼──────────────────────────┤
  │ Facade           │ ParkingService            │ Single entry point       │
  ├──────────────────┼───────────────────────────┼──────────────────────────┤
  │ State            │ SpotStatus transitions    │ Valid state management   │
  ├──────────────────┼───────────────────────────┼──────────────────────────┤
  │ Observer         │ DisplayBoard updates      │ React to availability    │
  ├──────────────────┼───────────────────────────┼──────────────────────────┤
  │ Repository       │ SpotRepo, TicketRepo, etc │ Data access abstraction  │
  ├──────────────────┼───────────────────────────┼──────────────────────────┤
  │ Template Method  │ ParkingSpot.canFitVehicle │ Variant step per subtype │
  └──────────────────┴───────────────────────────┴──────────────────────────┘
```

---

## 12. Extensibility Points

> A well-designed Parking Lot system should be easy to extend. Here is how to add common features WITHOUT modifying existing code (OCP).

### 12.1 EV Charging Spots

```java
// New spot type
public enum SpotType {
    MOTORCYCLE_SPOT, COMPACT, LARGE, HANDICAPPED,
    EV_CHARGING   // <-- Add to enum
}

// New spot class
public class EVChargingSpot extends ParkingSpot {
    private boolean chargingActive;

    public EVChargingSpot(String spotId, int floorNumber, int spotNumber) {
        super(spotId, floorNumber, spotNumber, SpotType.EV_CHARGING);
        this.chargingActive = false;
    }

    @Override
    public boolean canFitVehicle(Vehicle vehicle) {
        // Only electric vehicles (could add isElectric() to Vehicle)
        return vehicle.getVehicleType() == VehicleType.CAR;
    }

    public void startCharging() { this.chargingActive = true; }
    public void stopCharging()  { this.chargingActive = false; }
}

// Changes: Add enum value, add new class, add to SpotFactory.
// No changes to: ParkingSpot, CompactSpot, ParkingService, strategies.
```

### 12.2 Valet Parking

```java
// New strategy that assigns "premium" spots near the elevator
public class ValetParkingStrategy implements ParkingStrategy {
    @Override
    public Optional<ParkingSpot> findSpot(ParkingLot lot, Vehicle vehicle) {
        // Valet can park anywhere, prefer spots closest to elevator
        // Implementation scans floors for premium-marked spots first
    }
}

// Changes: New strategy class, wire in config.
// No changes to: ParkingService, existing strategies.
```

### 12.3 Reservation System

```java
// SpotStatus already supports RESERVED:
//   AVAILABLE -> RESERVED -> OCCUPIED -> AVAILABLE

// New service
public class ReservationService {
    public void reserveSpot(ParkingSpot spot, Vehicle vehicle, LocalDateTime time) {
        spot.setStatus(SpotStatus.RESERVED);
        // Store reservation in repository
    }
}

// Changes: New service class. SpotStatus.RESERVED is already defined.
// No changes to: ParkingSpot (status transitions already supported).
```

### 12.4 Dynamic / Surge Pricing

```java
public class DynamicPricingStrategy implements PricingStrategy {
    private final PricingStrategy baseStrategy;
    private final ParkingLot lot;

    @Override
    public double calculateFee(ParkingTicket ticket) {
        double baseFee = baseStrategy.calculateFee(ticket);
        double occupancyRate = 1.0 - ((double) lot.getAvailableCount() / lot.getTotalCapacity());

        // Surge multiplier: 1.0x at 0% full, up to 2.0x at 100% full
        double surgeMultiplier = 1.0 + occupancyRate;

        return baseFee * surgeMultiplier;
    }
}

// Decorator pattern on top of Strategy pattern.
// Changes: New class only.
```

### 12.5 Monthly Subscription / Pass

```java
public class SubscriptionPricingStrategy implements PricingStrategy {
    private final Set<String> activeSubscribers;  // license plates
    private final PricingStrategy fallbackStrategy;

    @Override
    public double calculateFee(ParkingTicket ticket) {
        String plate = ticket.getVehicle().getLicensePlate();
        if (activeSubscribers.contains(plate)) {
            return 0.0;  // Subscriber parks for free
        }
        return fallbackStrategy.calculateFee(ticket);
    }
}
```

### 12.6 Multiple Parking Lots

```java
// Replace Singleton with a manager
public class ParkingLotManager {
    private final Map<String, ParkingLot> lots = new ConcurrentHashMap<>();

    public void registerLot(String lotId, ParkingLot lot) {
        lots.put(lotId, lot);
    }

    public ParkingLot getLot(String lotId) {
        return lots.get(lotId);
    }

    public ParkingLot findNearestLotWithAvailability(VehicleType type) {
        // Iterate lots, find one with available spots for vehicle type
    }
}
```

### 12.7 Handicapped Priority

```java
// Already built into the system:
// 1. HandicappedSpot.canFitVehicle() checks vehicle.isHandicapped()
// 2. NearestFirstStrategy checks isHandicapped() and prefers HandicappedSpots

// To enforce legal requirements (handicapped spots always closest to entrance):
// Place HandicappedSpots on Floor 1, near gates. No code changes needed.
```

### Extensibility Summary

```
  Feature               │ New Classes Needed           │ Existing Code Changed
  ──────────────────────┼──────────────────────────────┼──────────────────────
  EV Charging Spot      │ EVChargingSpot               │ SpotType enum, SpotFactory
  Valet Parking         │ ValetParkingStrategy         │ AppConfig (wire new strategy)
  Reservation           │ ReservationService           │ None (SpotStatus.RESERVED exists)
  Surge Pricing         │ DynamicPricingStrategy       │ AppConfig (wire new strategy)
  Monthly Pass          │ SubscriptionPricingStrategy  │ AppConfig
  Multiple Lots         │ ParkingLotManager            │ Remove Singleton constraint
  New Payment (ApplePay)│ ApplePayProcessor            │ PaymentService.registerProcessor()
  New Vehicle (Truck)   │ Truck extends Vehicle        │ VehicleType enum, VehicleFactory
```

---

> **Interview Tip**: When presenting this design, start with the class hierarchies (Vehicle, ParkingSpot), then the Strategy interfaces, then show how ParkingService (Facade) ties everything together. Mention concurrency and SOLID at each step. The interviewer is evaluating your ability to think in objects, not your ability to write boilerplate.
