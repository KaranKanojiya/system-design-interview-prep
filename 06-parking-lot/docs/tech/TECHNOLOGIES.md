# Technologies & Infrastructure for the Parking Lot System

> Interview-ready reference for a Senior Java developer.
> This is primarily an LLD problem -- technology choices are lighter here than in distributed systems.
> Focus is on OOP and design patterns, but know these for the "how would you build this for real?" follow-up.

---

## Table of Contents

| Technology | Why It's Here | Interview Relevance |
|------------|--------------|---------------------|
| Java 21 OOP | Core language: strong typing, abstract classes, interfaces | HIGH -- this is THE OOP interview |
| PostgreSQL | Transactions, foreign keys, UNIQUE constraints | MEDIUM -- mention for production |
| IoT Sensors | Real-world spot detection | LOW -- mention for awareness |
| Display Boards | LED boards per floor | LOW -- ties to Observer pattern |
| Payment Gateway | Stripe/Square integration | MEDIUM -- ties to Strategy pattern |
| Entry/Exit Hardware | Gates, scanners, ALPR | LOW -- mention for completeness |
| Observability | Metrics, monitoring | LOW -- mention for production maturity |

---

## 1. Java 21 OOP

### Why Java Is Ideal for This Problem

| Java Feature | How It's Used | Example |
|-------------|---------------|---------|
| **Abstract classes** | Base class with shared behavior + abstract hooks | `ParkingSpot` (abstract) with `canFitVehicle()` |
| **Interfaces** | Strategy contracts, Repository contracts | `ParkingStrategy`, `PricingStrategy`, `PaymentProcessor` |
| **Enums** | Type-safe constants with behavior | `VehicleType`, `SpotType`, `SpotStatus`, `PaymentMethod` |
| **Generics** | Type-safe collections | `List<ParkingSpot>`, `Optional<ParkingSpot>`, `Map<String, ParkingTicket>` |
| **synchronized** | Thread safety for concurrent access | `synchronized` on spot-level parking operations |
| **sealed classes** (Java 17+) | Restrict Vehicle hierarchy | `sealed class Vehicle permits Car, Motorcycle, Bus` |
| **switch expressions** (Java 21) | Exhaustive enum matching in factories | `case CAR -> new Car(plate);` -- compiler error if case missing |
| **Records** (Java 16+) | Immutable value objects | Could use for `ParkingTicket` if fully immutable |

### Key Language Choices

```java
// Enum with behavior (not just constants)
public enum SpotStatus {
    AVAILABLE("[ ]"),
    OCCUPIED("[X]"),
    OUT_OF_ORDER("[!]");

    private final String symbol;
    // Constructor, getter, toString...
}

// Abstract class with protected constructor
public abstract class Vehicle {
    protected Vehicle(String licensePlate, VehicleType type) { ... }
    // Only concrete subclasses can instantiate
}

// Interface for strategy injection
public interface ParkingStrategy {
    Optional<ParkingSpot> findSpot(Vehicle vehicle, List<Floor> floors);
}
```

### Interview Note

> "I chose Java because this problem is fundamentally about OOP: inheritance hierarchies (Vehicle, ParkingSpot), polymorphism (Strategy pattern), encapsulation (spot status management), and abstraction (interfaces for all strategies). Java's type system catches design errors at compile time."

---

## 2. Database: PostgreSQL

### Why RDBMS Over NoSQL

| Requirement | RDBMS Strength |
|-------------|---------------|
| No double-booking | `UNIQUE` constraint on `(spot_id) WHERE status = 'OCCUPIED'` |
| Payment transactions | ACID transactions -- charge + update in one commit |
| Relationships | Foreign keys: `ticket -> spot -> floor`, `ticket -> vehicle` |
| Reporting | SQL aggregations: revenue/day, occupancy rate, peak hours |

### Schema (Simplified)

```sql
CREATE TABLE vehicle (
    license_plate  VARCHAR(20) PRIMARY KEY,
    vehicle_type   VARCHAR(20) NOT NULL,       -- MOTORCYCLE, CAR, BUS
    created_at     TIMESTAMP DEFAULT NOW()
);

CREATE TABLE parking_spot (
    spot_id        VARCHAR(20) PRIMARY KEY,    -- "F1-A01"
    floor_number   INT NOT NULL,
    spot_type      VARCHAR(20) NOT NULL,       -- COMPACT, LARGE, MOTORCYCLE
    status         VARCHAR(20) DEFAULT 'AVAILABLE',
    vehicle_plate  VARCHAR(20) REFERENCES vehicle(license_plate)
);

-- Prevents double-booking at the database level
CREATE UNIQUE INDEX uq_one_vehicle_per_spot
    ON parking_spot (spot_id)
    WHERE status = 'OCCUPIED';

CREATE TABLE parking_ticket (
    ticket_id      VARCHAR(36) PRIMARY KEY,    -- UUID
    vehicle_plate  VARCHAR(20) REFERENCES vehicle(license_plate),
    spot_id        VARCHAR(20) REFERENCES parking_spot(spot_id),
    entry_time     TIMESTAMP NOT NULL,
    exit_time      TIMESTAMP,
    amount         DECIMAL(10,2),
    payment_status VARCHAR(20) DEFAULT 'PENDING'
);
```

### Interview Note

> "For the in-memory demo, I use HashMap via the Repository pattern. For production, swap to PostgreSQL -- same interface, different implementation. The database gives us ACID transactions, UNIQUE constraints for no double-booking, and SQL for reporting."

---

## 3. IoT Sensors (Real-World Context)

### How Spot Detection Works in Production

```
  Physical Spot                Sensor                    Backend
  =============                ======                    =======

  [Car parks]  -->  Ultrasonic/Magnetic  -->  MQTT message  -->  SpotService
                    sensor detects                                  |
                    vehicle presence                                v
                                                           spot.setStatus(OCCUPIED)

  [Car leaves] -->  Sensor detects      -->  MQTT message  -->  SpotService
                    absence                                        |
                                                                   v
                                                           spot.setStatus(AVAILABLE)
```

### Sensor Types

| Sensor | Technology | Accuracy | Cost |
|--------|-----------|----------|------|
| Ultrasonic | Sound waves detect object above | ~95% | Low ($20-50) |
| Magnetic | Detects metal disturbance in earth's field | ~98% | Medium ($50-100) |
| Camera + CV | Computer vision detects vehicle + reads plate | ~99% | High ($200-500) |
| Infrared | IR beam break detection at entrance | ~99% | Low ($30-60) |

### Interview Note

> "In production, each spot has a sensor that publishes MQTT events. Our SpotService subscribes and updates status. In the demo, the gate operator calls `parkVehicle()` directly -- same business logic, different trigger."

---

## 4. Display Boards

### Architecture

```
  +----------------+       +----------------+       +----------------+
  |  Floor 1 LED   |       |  Floor 2 LED   |       |  Floor 3 LED   |
  |  Cars:  8/50   |       |  Cars: 12/50   |       |  Cars: 45/50   |
  |  Motor: 3/10   |       |  Motor: 5/10   |       |  Motor: 10/10  |
  |  Large: 2/5    |       |  Large: 0/5    |       |  Large: 4/5    |
  +-------+--------+       +-------+--------+       +-------+--------+
          |                         |                         |
          +------------+------------+-------------------------+
                       |
                       v
              DisplayBoard Service
              (Observer pattern)
              - Subscribed to availability changes
              - Updates on park/unpark events
```

### Update Strategies

| Strategy | Mechanism | Latency | Complexity |
|----------|-----------|---------|------------|
| **Push (Observer)** | Event fired on park/unpark | Real-time | Medium |
| **Pull (Polling)** | Board polls every 5 seconds | 0-5 seconds | Simple |
| **Hybrid** | Push for changes, poll as heartbeat/fallback | Real-time + resilient | Higher |

### Connection Protocols

| Protocol | Use Case |
|----------|----------|
| MQTT | IoT-friendly, pub/sub, low bandwidth |
| HTTP polling | Simple, stateless, firewall-friendly |
| WebSocket | Real-time bidirectional (for mobile app dashboard) |

---

## 5. Payment Gateway

### Integration Architecture

```
  ParkingService                  PaymentProcessor           External
  ==============                  ================           ========

  unparkVehicle()
      |
      v
  pricingStrategy.calculate()
      |
      v (amount = $12.50)
  paymentProcessor.processPayment(amount, CREDIT_CARD)
      |                                  |
      +-- CashPaymentProcessor           +-- CreditCardPaymentProcessor
          |                                   |
          v                                   v
      Opens cash drawer               Stripe API call
      Waits for insertion             POST /v1/charges
      Returns success                 { amount: 1250, currency: "usd" }
                                      Returns success/failure
```

### Payment Gateway Options

| Gateway | Strengths | PCI Compliance |
|---------|-----------|----------------|
| **Stripe** | Best API, webhooks, easy integration | PCI DSS Level 1 |
| **Square** | Good for physical terminals | PCI DSS Level 1 |
| **Adyen** | Enterprise, multi-currency | PCI DSS Level 1 |

### PCI Compliance Note

Card data never touches our server. We use tokenization (Stripe Elements / Square Reader SDK). The PaymentProcessor sends a token, not raw card numbers.

### Interview Note

> "PaymentProcessor is a Strategy interface. CashPaymentProcessor opens the cash drawer. CreditCardPaymentProcessor calls Stripe's API with a tokenized card. Adding Apple Pay means one new class -- no existing code changes."

---

## 6. Entry/Exit Hardware

### Gate Flow

```
  ENTRY                                          EXIT
  =====                                          ====

  [Vehicle approaches]                    [Vehicle approaches]
        |                                       |
        v                                       v
  License Plate Recognition (ALPR)        Ticket/QR Scanner
        |                                       |
        v                                       v
  Ticket Dispenser prints ticket          Calculate amount
  (QR code + ticket ID)                        |
        |                                       v
        v                                 Payment Terminal
  Barrier Gate opens                     (cash/card/contactless)
        |                                       |
        v                                       v
  Vehicle enters                          Barrier Gate opens
  Timer starts                            Vehicle exits
```

### Hardware Components

| Component | Function | Technology |
|-----------|----------|------------|
| ALPR Camera | Read license plate on entry/exit | OpenALPR, Plate Recognizer API |
| Ticket Dispenser | Print ticket with QR code | Thermal printer + QR generator |
| QR Scanner | Read ticket on exit | Barcode reader (Code128, QR) |
| Barrier Gate | Physical access control | Motor-driven arm, IR safety sensor |
| Payment Terminal | Accept payment | Verifone/Ingenico POS terminal |
| Loop Detector | Detect vehicle presence at gate | Inductive loop embedded in road |

---

## 7. Observability

### Key Metrics for a Parking Lot

| Metric | Formula | Business Value |
|--------|---------|---------------|
| **Occupancy Rate** | `occupied / total * 100` | Revenue optimization, staffing |
| **Average Duration** | `avg(exitTime - entryTime)` | Pricing strategy input |
| **Revenue Per Day** | `sum(ticket.amount) per day` | Financial reporting |
| **Peak Hours** | `count(entries) grouped by hour` | Dynamic pricing trigger |
| **Turnover Rate** | `total vehicles / total spots / day` | Efficiency metric |
| **Spot Utilization** | `occupied_hours / total_hours per spot` | Identify underused spots |

### Monitoring Stack (Production)

```
  Application          Metrics          Dashboard         Alerts
  ===========          =======          =========         ======

  ParkingService  -->  Prometheus  -->  Grafana     -->  PagerDuty
  (Micrometer)         (scrape)        (visualize)      (if lot > 95%)

  Application  -->  ELK Stack     -->  Kibana
  (Logback)        (log aggregation)  (search/analyze)
```

### Alerting Rules

| Alert | Condition | Action |
|-------|-----------|--------|
| Lot almost full | Occupancy > 95% | Update entrance signs to "FULL" |
| Payment failure spike | > 5 failures in 1 minute | Page on-call engineer |
| Sensor offline | No heartbeat for 2 minutes | Mark spot OUT_OF_ORDER |
| Gate malfunction | Gate stuck open/closed | Page maintenance + security |

---

## Technology Decision Summary

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Language | Java 21 | Best OOP support, type safety, concurrency primitives |
| Database | PostgreSQL | ACID, FK constraints, UNIQUE for no double-booking |
| In-memory store | ConcurrentHashMap | Interview demo, swap to DB via Repository |
| Communication | MQTT (sensors), HTTP (dashboard) | IoT-standard, simple |
| Payment | Stripe (tokenized) | Best API, PCI compliant |
| Monitoring | Prometheus + Grafana | Industry standard, open source |

### Interview Note

> "For the LLD interview, the focus is Java OOP and design patterns -- not infrastructure. But when asked 'how would you deploy this?': PostgreSQL for persistence, MQTT for sensor events, Stripe for payments, Prometheus for metrics. The Repository pattern means swapping from in-memory to any of these is a one-class change."
