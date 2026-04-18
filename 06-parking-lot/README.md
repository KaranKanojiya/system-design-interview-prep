# Parking Lot System

## Problem Summary

Design a **multi-floor parking lot** with vehicle type management, ticketing, payment, and availability tracking. THE classic LLD interview problem. Tests OOP design, design patterns, and SOLID principles -- not distributed systems.

---

## 1-Minute Interview Revision

- **Vehicle hierarchy:** `Vehicle` (abstract) -> `Car`, `Motorcycle`, `Bus`
- **Spot hierarchy:** `ParkingSpot` (abstract) -> `CompactSpot`, `LargeSpot`, `MotorcycleSpot`, `HandicappedSpot`
- **Spot compatibility:** Compact fits Car+Motorcycle, Large fits all, Motorcycle fits only Motorcycle
- **ParkingLot:** Singleton (one physical lot). Contains floors, each has spots.
- **3 Strategy interfaces:** `ParkingStrategy` (spot selection), `PricingStrategy` (fee calc), `PaymentProcessor` (payment)
- **Facade:** `ParkingService` wraps everything -- single entry point for park/unpark
- **Thread safety:** `synchronized` on `ParkingSpot.park()` -- no double-booking
- **Pricing:** Hourly (per vehicle type) or Flat Rate
- **CAP:** CP -- consistency critical (no double-booking)

---

## Class Hierarchy

```
Vehicle (abstract)                    ParkingSpot (abstract)
  |-- Car                               |-- CompactSpot      (Car, Motorcycle)
  |-- Motorcycle                        |-- LargeSpot        (Car, Motorcycle, Bus)
  |-- Bus                               |-- MotorcycleSpot   (Motorcycle only)
                                        |-- HandicappedSpot  (Car, Motorcycle)

ParkingStrategy (interface)           PricingStrategy (interface)
  |-- NearestFirstStrategy              |-- HourlyPricingStrategy
  |-- FloorCompactStrategy              |-- FlatRatePricingStrategy

PaymentProcessor (interface)          ParkingService (Facade)
  |-- CashPayment                       |-- parkVehicle()
  |-- CreditCardPayment                 |-- unparkVehicle()

ParkingLot (Singleton)
  |-- List<ParkingFloor>
       |-- List<ParkingSpot>
```

---

## Key Components

| Component | Role |
|-----------|------|
| `Vehicle` | Abstract base with type, license plate |
| `ParkingSpot` | Abstract base with `canFitVehicle()` template method |
| `ParkingFloor` | Holds spots, tracks availability per type |
| `ParkingLot` | Singleton, holds floors, entry/exit points |
| `ParkingTicket` | Issued at entry, tracks time + spot |
| `ParkingStrategy` | Strategy for spot selection algorithm |
| `PricingStrategy` | Strategy for fee calculation |
| `PaymentProcessor` | Strategy for payment method |
| `ParkingService` | Facade orchestrating all operations |
| `DisplayBoard` | Observer -- updates when spots change |

---

## Key Tradeoffs

| Decision | Option A | Option B | This Design |
|----------|----------|----------|-------------|
| Vehicle/Spot base | Abstract class | Interface | **Abstract class** -- shared state (plate, occupied) |
| Spot assignment | Nearest-first | Compact-floor | **Nearest-first** -- better UX |
| Pricing model | Hourly | Flat rate / Dynamic | **Strategy** -- supports all |
| Concurrency | Pessimistic lock | Optimistic lock | **Pessimistic** (`synchronized`) -- simpler, correct |
| ParkingLot access | Singleton | Dependency injection | **Singleton** -- one physical lot |

---

## SOLID Principles

| Principle | Example |
|-----------|---------|
| **S** -- Single Responsibility | `ParkingSpot` manages spot state only, `PricingStrategy` handles fees only |
| **O** -- Open/Closed | Add `EVChargingSpot` without modifying existing spots |
| **L** -- Liskov Substitution | Any `ParkingSpot` subclass works wherever `ParkingSpot` is expected |
| **I** -- Interface Segregation | `ParkingStrategy`, `PricingStrategy`, `PaymentProcessor` are separate interfaces |
| **D** -- Dependency Inversion | `ParkingService` depends on `PricingStrategy` interface, not `HourlyPricing` class |

---

## Design Patterns

| Pattern | Where | Why |
|---------|-------|-----|
| **Strategy** (x3) | ParkingStrategy, PricingStrategy, PaymentProcessor | Each dimension varies independently |
| **Singleton** | ParkingLot | One physical lot |
| **Factory** | VehicleFactory, SpotFactory | Encapsulate creation logic |
| **Facade** | ParkingService | Single entry point for clients |
| **State** | ParkingSpot (Available/Occupied) | Spot behavior changes with state |
| **Observer** | DisplayBoard observes spot changes | Real-time availability updates |
| **Template Method** | `canFitVehicle()` in ParkingSpot | Base defines flow, subclasses define rules |
| **Builder** | ParkingLot.Builder | Complex lot construction |
| **Repository** | TicketRepository | Data access abstraction |

---

## CAP Summary

**CP (Consistency + Partition Tolerance)**

This is NOT a distributed systems problem. The "CAP" concern here is **thread safety**:
- Two cars arrive simultaneously targeting the same spot
- `synchronized` on `ParkingSpot.park()` guarantees no double-booking
- Consistency > availability -- better to reject than double-book

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 17+ |
| Build | Gradle |
| Testing | JUnit 5 |
| Concurrency | `synchronized` / `ReentrantLock` |
| Data (interview) | In-memory collections |
| Data (production) | PostgreSQL |

---

## Common Interview Follow-Up Questions

1. **How to handle concurrent parking requests?**
   `synchronized` on `ParkingSpot.park()`. Two threads race -- one wins, other gets next available spot.

2. **Can a motorcycle park in a compact spot?**
   Yes. `CompactSpot.canFitVehicle()` accepts Car and Motorcycle.

3. **How would you add EV charging spots?**
   New `EVChargingSpot extends ParkingSpot`. Override `canFitVehicle()`. Open/Closed principle -- zero changes to existing code.

4. **How to implement dynamic pricing?**
   New `DynamicPricingStrategy implements PricingStrategy`. Factor in occupancy %, time of day, events.

5. **Why abstract class instead of interface for Vehicle?**
   Shared state (licensePlate, type) and common behavior. Interface would force duplicating fields in every subclass.

6. **How does the display board update?**
   Observer pattern. `DisplayBoard` observes `ParkingFloor`. Spot state change -> notify -> board refreshes counts.

7. **What if a car doesn't pay and leaves?**
   Gate stays closed until payment confirmed. If forced exit, ticket flagged as unpaid, license plate captured by camera.

8. **How to support reservations?**
   Add `ReservationService`. Spot gets `RESERVED` state. Reserved spots excluded from `ParkingStrategy` search.

9. **How to handle a bus that needs multiple spots?**
   `LargeSpot` fits a bus in one spot. OR: `BusStrategy` finds N consecutive compact spots. Depends on requirements.

10. **How to extend for a multi-lot chain?**
    Remove Singleton. `ParkingLotManager` holds multiple `ParkingLot` instances. Add location-based routing.

11. **Singleton vs static methods for ParkingLot?**
    Singleton allows implementing interfaces, lazy initialization, and can be replaced in tests. Static methods cannot.

12. **How to add monthly subscriptions?**
    New `SubscriptionService`. Subscriber vehicles bypass payment on exit. `PricingStrategy` returns $0 for subscribers.

13. **How to handle handicapped priority?**
    `HandicappedSpot` is checked first for vehicles with handicapped permit. If full, fall back to regular spots.

14. **What happens when all spots of a type are full but other types have space?**
    Depends on policy. A motorcycle CAN park in compact. A car CANNOT park in motorcycle. Strategy handles upgrade logic.

---

## How to Run

```bash
cd 06-parking-lot && ../gradlew run
```

---

## What to Improve Later

- [ ] Reservation system with time slots
- [ ] EV charging spot with charge-time tracking
- [ ] Dynamic pricing based on occupancy
- [ ] REST API layer (Spring Boot)
- [ ] Event sourcing for audit trail
- [ ] Multi-lot support with location routing
- [ ] License plate recognition (camera integration)
- [ ] Monthly subscription management
