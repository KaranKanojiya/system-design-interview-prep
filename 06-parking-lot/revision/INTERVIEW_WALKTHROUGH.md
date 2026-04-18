# Interview Walkthrough -- Parking Lot System

> LLD interview is different from HLD. More focus on **OOP, class design, and design patterns**.
> Less focus on distributed systems, databases, and scaling.

---

## Phase 1: Clarify Requirements (2-3 min)

Ask these questions before writing anything:

- "How many vehicle types? Car, motorcycle, bus?"
- "Multiple floors? How many spots per floor?"
- "Pricing model? Hourly? Flat rate? Per vehicle type?"
- "Payment methods? Cash, card, both?"
- "Display board showing availability?"
- "Concurrency? Multiple entry/exit points?"
- "Reservations? Or walk-in only?"

**What you signal:** You don't jump to code. You think about scope first.

---

## Phase 2: Core Entities (5-7 min) -- START HERE

This is LLD. Start with the class hierarchy, not the architecture diagram.

### Vehicle Hierarchy

```
Vehicle (abstract)
  - licensePlate: String
  - type: VehicleType
  |
  |-- Car
  |-- Motorcycle
  |-- Bus
```

**Say:** "Vehicle is abstract because we never instantiate a generic Vehicle. Each subclass has its own type. Abstract class over interface because there's shared state -- licensePlate, type."

### ParkingSpot Hierarchy

```
ParkingSpot (abstract)
  - spotNumber: String
  - floor: ParkingFloor
  - vehicle: Vehicle (null if empty)
  - spotType: SpotType
  + canFitVehicle(Vehicle): boolean  <-- Template Method
  + park(Vehicle): boolean           <-- synchronized
  + vacate(): Vehicle
  |
  |-- CompactSpot       canFit: Car, Motorcycle
  |-- LargeSpot         canFit: Car, Motorcycle, Bus
  |-- MotorcycleSpot    canFit: Motorcycle only
  |-- HandicappedSpot   canFit: Car, Motorcycle (with permit)
```

### Spot Compatibility Matrix

| Spot \ Vehicle | Car | Motorcycle | Bus |
|----------------|-----|------------|-----|
| CompactSpot | YES | YES | no |
| LargeSpot | YES | YES | YES |
| MotorcycleSpot | no | YES | no |
| HandicappedSpot | YES | YES | no |

**Say:** "`canFitVehicle()` is a Template Method. The base class defines the flow -- check if spot is empty, then delegate to subclass for type compatibility. Each subclass overrides just the compatibility check."

---

## Phase 3: ParkingFloor and ParkingLot (3-4 min)

### ParkingFloor

```java
class ParkingFloor {
    String floorId;
    List<ParkingSpot> spots;
    DisplayBoard displayBoard;

    Map<SpotType, Integer> availableCount;  // fast lookup
}
```

### ParkingLot (Singleton)

```java
class ParkingLot {
    private static ParkingLot instance;
    List<ParkingFloor> floors;
    List<EntryPanel> entryPanels;
    List<ExitPanel> exitPanels;

    public static synchronized ParkingLot getInstance() { ... }
}
```

**Say:** "Singleton because there's one physical lot. I know DI is often preferred, but for this domain, Singleton maps directly to reality. In tests, I'd use a reset method or package-private constructor."

---

## Phase 4: Strategies (5-7 min) -- Show Pattern Mastery

This is where you differentiate from junior candidates.

### ParkingStrategy -- How to Find a Spot

```java
interface ParkingStrategy {
    ParkingSpot findSpot(Vehicle vehicle, List<ParkingFloor> floors);
}

class NearestFirstStrategy implements ParkingStrategy { ... }
class FloorCompactStrategy implements ParkingStrategy { ... }
```

### PricingStrategy -- How to Calculate Fee

```java
interface PricingStrategy {
    double calculateFee(ParkingTicket ticket);
}

class HourlyPricingStrategy implements PricingStrategy { ... }
class FlatRatePricingStrategy implements PricingStrategy { ... }
```

### PaymentProcessor -- How to Process Payment

```java
interface PaymentProcessor {
    PaymentResult processPayment(double amount, PaymentMethod method);
}

class CashPayment implements PaymentProcessor { ... }
class CreditCardPayment implements PaymentProcessor { ... }
```

**THE KEY LINE:** "I use Strategy pattern in three places because each dimension varies independently. I can change how we find spots without touching pricing. I can add a new payment method without touching spot selection. Each axis of change is isolated."

---

## Phase 5: ParkingService Facade (3-4 min)

```java
class ParkingService {
    ParkingLot lot;
    ParkingStrategy parkingStrategy;
    PricingStrategy pricingStrategy;
    PaymentProcessor paymentProcessor;
    TicketRepository ticketRepo;

    public ParkingTicket parkVehicle(Vehicle vehicle) {
        ParkingSpot spot = parkingStrategy.findSpot(vehicle, lot.getFloors());
        if (spot == null) throw new ParkingFullException();
        spot.park(vehicle);                    // synchronized inside
        ParkingTicket ticket = new ParkingTicket(vehicle, spot);
        ticketRepo.save(ticket);
        spot.getFloor().getDisplayBoard().update();  // Observer
        return ticket;
    }

    public double unparkVehicle(String ticketId, PaymentMethod method) {
        ParkingTicket ticket = ticketRepo.findById(ticketId);
        double fee = pricingStrategy.calculateFee(ticket);
        paymentProcessor.processPayment(fee, method);
        ticket.getSpot().vacate();
        ticket.getSpot().getFloor().getDisplayBoard().update();
        return fee;
    }
}
```

**Say:** "ParkingService is a Facade. The client -- whether it's an entry panel, exit panel, or mobile app -- calls one method. All the orchestration is hidden."

---

## Phase 6: Ticketing and Payment Flow (3-4 min)

### Entry Flow

```
Vehicle arrives
  -> EntryPanel scans/inputs vehicle info
  -> ParkingService.parkVehicle(vehicle)
    -> ParkingStrategy finds best spot
    -> ParkingSpot.park(vehicle)  [synchronized]
    -> Create ParkingTicket (entry time, spot, vehicle)
    -> Update DisplayBoard
  -> Print/display ticket
  -> Gate opens
```

### Exit Flow

```
Driver inserts ticket
  -> ParkingService.unparkVehicle(ticketId, paymentMethod)
    -> PricingStrategy calculates fee (exit - entry time)
    -> PaymentProcessor charges fee
    -> ParkingSpot.vacate()
    -> Update DisplayBoard
  -> Gate opens
```

---

## Phase 7: Concurrency (2-3 min)

```java
// In ParkingSpot
public synchronized boolean park(Vehicle vehicle) {
    if (this.vehicle != null) return false;  // already occupied
    if (!canFitVehicle(vehicle)) return false;
    this.vehicle = vehicle;
    return true;
}
```

**Say:** "Two cars enter simultaneously. Both threads call `parkingStrategy.findSpot()` and get the same spot. First thread acquires the lock on `park()`, sets the vehicle, returns true. Second thread acquires the lock, sees `this.vehicle != null`, returns false. The strategy then finds the next available spot for the second car."

**Follow-up anticipation:** "For higher throughput, I could use `ReentrantLock` with `tryLock()` to avoid blocking, or optimistic locking with a version field if backed by a database."

---

## Phase 8: SOLID Walkthrough (2-3 min) -- If Time

Walk through each with a concrete example from your design:

| Principle | Example | Why It Matters |
|-----------|---------|---------------|
| **SRP** | `ParkingSpot` only manages spot state. Pricing is separate. | Change pricing without touching spots |
| **OCP** | Add `EVChargingSpot` -- zero changes to existing code | New spot type = new class, not if/else |
| **LSP** | Any `ParkingSpot` subclass works in `ParkingStrategy` | Strategy doesn't care which spot subclass |
| **ISP** | Three small interfaces, not one `ParkingOperations` mega-interface | Implementations only depend on what they need |
| **DIP** | `ParkingService` depends on `PricingStrategy` interface | Swap hourly for flat rate via config |

**Say:** "Every SOLID principle has a concrete example in this design. That's not by accident -- SOLID drives the class structure."

---

## Red Flags (What NOT to Do)

- Writing a giant `ParkingLot` class with all logic inside
- Using `switch(vehicleType)` instead of polymorphism
- No Strategy pattern -- hardcoding pricing logic
- Ignoring concurrency entirely
- Making everything public
- No clear separation between spot types

## Green Flags (What Impresses)

- Clean class hierarchies with abstract base classes
- Strategy pattern with clear justification ("each axis varies independently")
- Explaining WHY abstract class vs interface
- Mentioning thread safety without being asked
- Template Method for `canFitVehicle()`
- Observer for DisplayBoard
- Knowing when Singleton is appropriate vs DI

---

## 30-Second Elevator Pitch

> "I'll model this with two hierarchies: Vehicle and ParkingSpot, both abstract classes with
> shared state. ParkingLot is a Singleton containing floors of spots. I use Strategy pattern
> in three places -- spot selection, pricing, and payment -- because each varies independently.
> ParkingService is a Facade that orchestrates everything. Thread safety comes from synchronized
> on the park() method -- no double-booking. The design follows SOLID: adding a new vehicle type
> or spot type requires zero changes to existing code."

**Time: Under 30 seconds. Covers: hierarchies, patterns, concurrency, extensibility.**

---

## Timing Guide (35-40 min total)

| Phase | Time | Priority |
|-------|------|----------|
| 1. Clarify Requirements | 2-3 min | Must do |
| 2. Core Entities | 5-7 min | **Critical -- start here** |
| 3. Floor + Lot | 3-4 min | Must do |
| 4. Strategies | 5-7 min | **High -- shows pattern mastery** |
| 5. Facade | 3-4 min | Must do |
| 6. Ticketing Flow | 3-4 min | Important |
| 7. Concurrency | 2-3 min | Important |
| 8. SOLID | 2-3 min | Bonus -- if time |

If short on time, skip Phase 8 and shorten Phase 6. Never skip Phases 2 and 4.
