# High-Level Design: Parking Lot Management System

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Scope](#2-scope)
3. [Assumptions](#3-assumptions)
4. [Functional Requirements](#4-functional-requirements)
5. [Non-Functional Requirements](#5-non-functional-requirements)
6. [API Design](#6-api-design)
7. [Data Model](#7-data-model)
8. [High-Level Architecture](#8-high-level-architecture)
9. [Component Deep Dive](#9-component-deep-dive)
10. [Spot Assignment Strategy](#10-spot-assignment-strategy)
11. [Pricing Strategy](#11-pricing-strategy)
12. [Concurrency](#12-concurrency)
13. [Scaling](#13-scaling)
14. [Database Choice](#14-database-choice)
15. [CAP Theorem](#15-cap-theorem)
16. [Cloud Services](#16-cloud-services)
17. [Tradeoffs Summary](#17-tradeoffs-summary)
18. [Interview Talking Points](#18-interview-talking-points)

---

## 1. Problem Statement

Design a **Parking Lot Management System** that handles vehicle parking, ticketing, payment, and real-time availability tracking across multiple floors and vehicle types.

### Why This Is Asked in Interviews

This is THE classic Low-Level Design (LLD) interview question, typically rated **Easy-Medium**. It is a staple for senior Java developer interviews because it tests:

| Skill Tested               | What Interviewers Look For                          |
|-----------------------------|-----------------------------------------------------|
| **OOP Fundamentals**        | Class hierarchies, encapsulation, abstraction        |
| **SOLID Principles**        | SRP in services, OCP via strategy pattern, DIP       |
| **Design Patterns**         | Strategy, Singleton, Factory, Observer               |
| **Concurrency**             | Thread-safe spot allocation, avoiding double-booking |
| **Real-World Modeling**     | Translating physical domain into software objects    |
| **Database Design**         | Relational modeling, ACID guarantees                 |

> **Interview tip**: While the HLD is lighter than a distributed system (e.g., URL shortener, message queue), you should still demonstrate that you can think about APIs, data flow, concurrency, and system boundaries. The bulk of this interview is in the LLD/OOP design.

---

## 2. Scope

### In Scope

- Multi-floor parking lot (5 floors)
- Multiple vehicle types: Motorcycle, Car, Bus/Truck
- Entry and exit gates with ticket kiosks
- Automated parking ticket issuance
- Hourly pricing per vehicle type
- Payment processing (cash, credit card)
- Real-time availability display (per floor, per vehicle type)
- Parking spot assignment (nearest available)
- Handling full parking lot (deny entry)

### Out of Scope

- Advance reservations / online booking
- Valet parking service
- Electric vehicle (EV) charging stations
- Security cameras / surveillance integration
- Mobile app / QR code scanning
- Handicapped / priority spots (can extend later)
- Monthly / subscription passes

---

## 3. Assumptions

### Capacity

| Parameter               | Value                  |
|--------------------------|------------------------|
| Number of floors         | 5                      |
| Car spots per floor      | 100                    |
| Motorcycle spots / floor | 50                     |
| Large spots / floor      | 20 (bus/truck)         |
| **Total spots**          | **850**                |

### Traffic

| Parameter               | Value                  |
|--------------------------|------------------------|
| Peak occupancy           | ~80% (680 spots)       |
| Vehicles per day         | ~2,000                 |
| Average stay duration    | 3 hours                |
| Entry/exit gates         | 2 entry, 2 exit        |
| Peak entry rate          | ~5 vehicles/minute     |

### Business

- Pricing is per-hour, rounded up to the next full hour
- Payment is collected at exit before the barrier opens
- System operates 24/7
- Single physical location (not a chain -- scaling section covers chain scenario)

---

## 4. Functional Requirements

### FR-1: Spot Assignment
Assign the nearest available parking spot based on vehicle type. If no spot of that type is available, deny entry.

### FR-2: Ticket Issuance
Issue a parking ticket upon entry with: ticket ID, vehicle info, assigned spot, entry timestamp.

### FR-3: Fee Calculation
Calculate parking fee based on:
- Duration (entry time to exit time, rounded up to next hour)
- Vehicle type rate

### FR-4: Payment Processing
Accept payment at exit via cash or credit card. Mark ticket as paid.

### FR-5: Spot Release
Free the assigned spot upon vehicle exit so it becomes available for the next vehicle.

### FR-6: Real-Time Availability
Display available spots per floor and per vehicle type on display boards and via API.

### FR-7: Full Lot Handling
When parking lot is full (for a specific vehicle type), deny entry and display "FULL" on entry board.

### FR-8: Pricing Schedule

| Vehicle Type | Rate (per hour) |
|--------------|-----------------|
| Motorcycle   | $1.00           |
| Car          | $2.00           |
| Bus / Truck  | $5.00           |

---

## 5. Non-Functional Requirements

| Requirement            | Target                        | Rationale                                     |
|------------------------|-------------------------------|-----------------------------------------------|
| **Thread Safety**      | No double-booking of spots    | Two vehicles entering simultaneously           |
| **Latency**            | Ticket generation < 100ms     | Gate should open quickly                       |
| **Availability**       | 99.9% uptime                  | Vehicles stuck inside if system is down        |
| **Real-Time Updates**  | Availability refresh < 1s     | Display boards must show current state         |
| **Data Integrity**     | ACID transactions for payment | Money involved -- no lost or duplicate payments |
| **Auditability**       | Full entry/exit/payment log   | Dispute resolution, accounting                 |

---

## 6. API Design

### 6.1 Entry -- Issue Ticket

```
POST /api/entry
```

**Request:**
```json
{
  "vehicleType": "CAR",
  "licensePlate": "ABC-1234"
}
```

**Response (201 Created):**
```json
{
  "ticketId": "TKT-20260418-00142",
  "vehicleId": "VH-00523",
  "licensePlate": "ABC-1234",
  "vehicleType": "CAR",
  "assignedSpot": {
    "spotId": "S-2-045",
    "floor": 2,
    "spotNumber": 45,
    "spotType": "CAR"
  },
  "entryTime": "2026-04-18T09:15:30Z"
}
```

**Error (409 Conflict -- lot full):**
```json
{
  "error": "NO_SPOT_AVAILABLE",
  "message": "No CAR spots available. Parking lot full for this vehicle type."
}
```

### 6.2 Exit -- Process Payment and Release Spot

```
POST /api/exit
```

**Request:**
```json
{
  "ticketId": "TKT-20260418-00142",
  "paymentMethod": "CREDIT_CARD"
}
```

**Response (200 OK):**
```json
{
  "receipt": {
    "receiptId": "RCP-00891",
    "ticketId": "TKT-20260418-00142",
    "licensePlate": "ABC-1234",
    "spotId": "S-2-045",
    "entryTime": "2026-04-18T09:15:30Z",
    "exitTime": "2026-04-18T12:45:10Z",
    "durationHours": 4,
    "ratePerHour": 2.00,
    "totalAmount": 8.00,
    "paymentMethod": "CREDIT_CARD",
    "paymentStatus": "PAID"
  }
}
```

### 6.3 Check Availability

```
GET /api/availability
```

**Response (200 OK):**
```json
{
  "totalSpots": 850,
  "totalAvailable": 312,
  "floors": [
    {
      "floor": 1,
      "availability": {
        "MOTORCYCLE": { "total": 50, "available": 18 },
        "CAR":        { "total": 100, "available": 42 },
        "LARGE":      { "total": 20, "available": 8 }
      }
    },
    {
      "floor": 2,
      "availability": {
        "MOTORCYCLE": { "total": 50, "available": 22 },
        "CAR":        { "total": 100, "available": 55 },
        "LARGE":      { "total": 20, "available": 12 }
      }
    }
  ]
}
```

### 6.4 Get Ticket Details

```
GET /api/ticket/{ticketId}
```

**Response (200 OK):**
```json
{
  "ticketId": "TKT-20260418-00142",
  "licensePlate": "ABC-1234",
  "vehicleType": "CAR",
  "spotId": "S-2-045",
  "floor": 2,
  "entryTime": "2026-04-18T09:15:30Z",
  "exitTime": null,
  "amount": null,
  "isPaid": false
}
```

### API Summary

| Method | Endpoint              | Purpose                   | Auth   |
|--------|-----------------------|---------------------------|--------|
| POST   | `/api/entry`          | Vehicle enters, get ticket | System |
| POST   | `/api/exit`           | Pay and exit               | System |
| GET    | `/api/availability`   | Real-time spot counts      | Public |
| GET    | `/api/ticket/{id}`    | Lookup ticket details      | System |

---

## 7. Data Model

### Entity-Relationship Diagram (Text)

```
  vehicle ──────< parking_ticket >────── parking_spot
                       |
                       |
                    payment
```

### Tables

#### `vehicle`

| Column         | Type         | Constraints         |
|----------------|--------------|---------------------|
| vehicle_id     | VARCHAR(20)  | PK                  |
| license_plate  | VARCHAR(15)  | UNIQUE, NOT NULL    |
| vehicle_type   | ENUM         | MOTORCYCLE/CAR/LARGE|
| created_at     | TIMESTAMP    | DEFAULT NOW()       |

#### `parking_spot`

| Column         | Type         | Constraints                 |
|----------------|--------------|------------------------------|
| spot_id        | VARCHAR(20)  | PK                           |
| floor          | INT          | NOT NULL, CHECK (1-5)        |
| spot_number    | INT          | NOT NULL                     |
| spot_type      | ENUM         | MOTORCYCLE/CAR/LARGE         |
| is_occupied    | BOOLEAN      | DEFAULT FALSE                |
| vehicle_id     | VARCHAR(20)  | FK -> vehicle, NULLABLE      |
| updated_at     | TIMESTAMP    | DEFAULT NOW()                |

**Index:** `idx_spot_available` on `(spot_type, floor, is_occupied)` -- critical for fast spot assignment queries.

#### `parking_ticket`

| Column         | Type         | Constraints                 |
|----------------|--------------|------------------------------|
| ticket_id      | VARCHAR(30)  | PK                           |
| vehicle_id     | VARCHAR(20)  | FK -> vehicle, NOT NULL      |
| spot_id        | VARCHAR(20)  | FK -> parking_spot, NOT NULL |
| entry_time     | TIMESTAMP    | NOT NULL                     |
| exit_time      | TIMESTAMP    | NULLABLE                     |
| amount         | DECIMAL(8,2) | NULLABLE                     |
| is_paid        | BOOLEAN      | DEFAULT FALSE                |
| created_at     | TIMESTAMP    | DEFAULT NOW()                |

#### `payment`

| Column         | Type         | Constraints                 |
|----------------|--------------|------------------------------|
| payment_id     | VARCHAR(20)  | PK                           |
| ticket_id      | VARCHAR(30)  | FK -> parking_ticket, UNIQUE |
| amount         | DECIMAL(8,2) | NOT NULL                     |
| method         | ENUM         | CASH/CREDIT_CARD             |
| status         | ENUM         | PENDING/COMPLETED/FAILED     |
| timestamp      | TIMESTAMP    | DEFAULT NOW()                |

### Key Query: Find Nearest Available Spot

```sql
SELECT spot_id, floor, spot_number
FROM parking_spot
WHERE spot_type = :vehicleType
  AND is_occupied = FALSE
ORDER BY floor ASC, spot_number ASC
LIMIT 1
FOR UPDATE;  -- pessimistic lock to prevent double-booking
```

---

## 8. High-Level Architecture

```
                          PARKING LOT SYSTEM
  ================================================================

  ENTRY FLOW:
  +-----------+     +---------------+     +------------------+     +--------+
  |  Entry    | --> | Ticket Kiosk  | --> | Parking Service  | --> |   DB   |
  |  Gate     |     | (Sensor +     |     | (Spot Assignment |     | (Spot, |
  |  (Barrier)|     |  Printer)     |     |  + Ticketing)    |     | Ticket)|
  +-----------+     +---------------+     +------------------+     +--------+
                                                  |
                                                  v
  EXIT FLOW:                                 [Spot Assigned,
  +-----------+     +---------------+         Ticket Issued]
  |  Exit     | <-- | Payment Kiosk | <--+
  |  Gate     |     | (Card Reader  |    |
  |  (Barrier)|     |  + Scanner)   |    |
  +-----------+     +---------------+    |
                          |              |
                          v              |
                    +------------------+ |
                    | Payment Service  |--+
                    | (Fee Calc +      |
                    |  Payment Gateway)|
                    +------------------+
                          |
                          v
  DISPLAY:          +------------------+     +--------+
  +-----------+     | Availability     | <-- |   DB   |
  | Display   | <-- | Service          |     |        |
  | Board     |     | (Real-time count)|     |        |
  +-----------+     +------------------+     +--------+
```

### Simplified Flow

```
  Vehicle Arrives
       |
       v
  [Entry Gate] -- scan plate --> [Parking Service] -- find spot --> [DB]
       |                              |
       |                         spot found?
       |                         /        \
       |                       YES         NO
       |                        |           |
       |                   issue ticket   "LOT FULL"
       |                        |           |
       v                        v           v
  [Open Barrier]          [Print Ticket]  [Deny Entry]
       |
       |  ... vehicle parks ... time passes ...
       |
       v
  [Exit Gate] -- scan ticket --> [Payment Service] -- calc fee --> [DB]
       |                              |
       |                         fee calculated
       |                              |
       v                              v
  [Payment Kiosk] <------------ [Show Amount]
       |
       |-- pay (cash/card)
       |
       v
  [Payment Gateway] -- success --> [Release Spot] --> [Open Barrier]
                                        |
                                        v
                               [Update Availability Display]
```

---

## 9. Component Deep Dive

### 9.1 Entry Gate System

**Responsibility:** Detect arriving vehicle, capture license plate, request spot assignment.

| Sub-Component     | Role                                             |
|-------------------|--------------------------------------------------|
| Sensor / Camera   | Detect vehicle presence, read license plate (ALPR)|
| Ticket Kiosk      | Display messages, print physical ticket            |
| Barrier           | Open/close based on ticket issuance               |

**Flow:**
1. Sensor detects vehicle at gate
2. Camera reads license plate (or driver inputs manually)
3. System calls `POST /api/entry`
4. If spot available: print ticket, open barrier
5. If full: display "FULL" message, barrier stays closed

### 9.2 Parking Service (Core)

**Responsibility:** Central orchestrator. Manages spot assignment, ticket creation, and spot release.

**Key Operations:**
- `assignSpot(vehicleType)` -- finds and locks nearest available spot
- `issueTicket(vehicle, spot)` -- creates ticket record
- `releaseSpot(ticketId)` -- marks spot as free, updates ticket

**Thread Safety:** This is the critical section. Must use synchronization or database-level locking to prevent double-booking. See Section 12.

### 9.3 Spot Assignment Engine

**Responsibility:** Determine which specific spot to assign given a vehicle type.

**Algorithm (default -- Nearest First):**
1. Query all unoccupied spots for the given vehicle type
2. Sort by floor ASC, then spot number ASC (nearest to entrance)
3. Select the first result
4. Lock and mark as occupied (atomic operation)

See Section 10 for alternative strategies.

### 9.4 Payment Service

**Responsibility:** Calculate parking fee and process payment.

**Fee Calculation:**
```
duration = ceil(exitTime - entryTime)   // rounded up to next hour
fee = duration * ratePerHour[vehicleType]
```

**Payment Flow:**
1. Retrieve ticket by ID
2. Calculate duration and fee
3. Display amount to driver
4. Process payment (cash machine or card reader via payment gateway)
5. On success: mark ticket as paid, trigger spot release
6. On failure: retry or offer alternative payment method

### 9.5 Availability Display Service

**Responsibility:** Maintain and broadcast real-time spot counts.

**Implementation Options:**
- **Poll:** Display boards query DB every 1 second
- **Push:** Parking Service publishes events on entry/exit; display boards subscribe (Observer pattern)
- **In-Memory Counter:** Maintain `ConcurrentHashMap<SpotType, AtomicInteger>` per floor; update on each entry/exit; periodically sync with DB

**Display Board Output:**
```
  =============================================
  |       PARKING AVAILABILITY                |
  |-------------------------------------------|
  | Floor | Motorcycle | Car  | Bus/Truck     |
  |-------|------------|------|---------------|
  |   1   |     18     |  42  |      8        |
  |   2   |     22     |  55  |     12        |
  |   3   |     35     |  78  |     15        |
  |   4   |     40     |  82  |     18        |
  |   5   |     45     |  90  |     20        |
  |-------|------------|------|---------------|
  | TOTAL |    160     | 347  |     73        |
  =============================================
```

### 9.6 Exit Gate System

**Responsibility:** Scan ticket, trigger payment, open barrier on successful payment.

**Flow:**
1. Driver inserts ticket (barcode/QR) or scans at reader
2. System calls `POST /api/exit` with ticket ID
3. Payment kiosk displays amount due
4. Driver pays (cash or card)
5. On payment success: barrier opens, vehicle exits
6. On failure: attendant notified, manual resolution

---

## 10. Spot Assignment Strategy

### Default: Nearest Available (Lowest Floor First)

```
Strategy: Start from Floor 1, find the first unoccupied spot.
Pros: Short walk for drivers, predictable
Cons: Floor 1 wears out faster, uneven traffic
```

### Alternative Strategies

| Strategy         | Description                                         | Use Case               |
|------------------|-----------------------------------------------------|------------------------|
| **Nearest First**| Lowest floor, lowest spot number                    | Default, user-friendly |
| **Compact Fill** | Fill one floor completely before moving to next     | Easier management      |
| **Random**       | Randomly pick from available spots                  | Even wear distribution |
| **Spread**       | Distribute evenly across floors                     | Balanced traffic       |
| **Priority**     | Assign premium spots (floor 1) at higher rate       | Revenue optimization   |

### Design Pattern: Strategy

This is a textbook use of the **Strategy Pattern**:

```
interface SpotAssignmentStrategy {
    ParkingSpot assignSpot(VehicleType type, List<ParkingSpot> available);
}

class NearestFirstStrategy implements SpotAssignmentStrategy { ... }
class CompactFillStrategy implements SpotAssignmentStrategy { ... }
class RandomStrategy implements SpotAssignmentStrategy { ... }
```

The `ParkingService` holds a reference to the strategy interface, and the concrete strategy can be swapped at runtime or via configuration.

> **Interview tip:** Mention the Strategy Pattern immediately when discussing spot assignment. It shows you think about extensibility and Open/Closed Principle.

---

## 11. Pricing Strategy

### Default: Flat Hourly Rate

| Vehicle Type | Rate       |
|--------------|------------|
| Motorcycle   | $1.00/hr   |
| Car          | $2.00/hr   |
| Bus / Truck  | $5.00/hr   |

**Calculation:** `fee = ceil(hours_parked) * rate_per_hour`

Example: Car parked for 2 hours 15 minutes = 3 hours (rounded up) x $2.00 = $6.00

### Extensible Pricing Models

| Model              | Description                                 |
|--------------------|---------------------------------------------|
| **First Hour Free**| No charge for first 60 minutes              |
| **Daily Maximum**  | Cap at $20/day for cars, regardless of hours|
| **Weekend Rate**   | 1.5x on weekends                            |
| **Peak Pricing**   | Higher rate during 9 AM - 6 PM              |
| **Tiered**         | $2/hr first 3 hours, $3/hr after            |

### Design Pattern: Strategy (Again)

```
interface PricingStrategy {
    BigDecimal calculateFee(VehicleType type, Duration duration);
}

class FlatHourlyPricing implements PricingStrategy { ... }
class FirstHourFreePricing implements PricingStrategy { ... }
class WeekendSurchargePricing implements PricingStrategy { ... }
```

> **Interview tip:** Applying Strategy Pattern to both spot assignment AND pricing shows you understand when to apply patterns and when different axes of variation need independent extension points.

---

## 12. Concurrency

### The Problem

Two vehicles arrive at two different entry gates simultaneously. Both request a CAR spot. Without protection, both could be assigned **the same spot** -- a classic race condition.

```
  Thread A (Gate 1)              Thread B (Gate 2)
  ─────────────────              ─────────────────
  READ: Spot S-1-042 is free
                                 READ: Spot S-1-042 is free
  WRITE: Assign S-1-042 to A
                                 WRITE: Assign S-1-042 to B  <-- CONFLICT!
```

### Solution 1: Pessimistic Locking (Recommended for Single Lot)

```sql
BEGIN TRANSACTION;

SELECT spot_id FROM parking_spot
WHERE spot_type = 'CAR' AND is_occupied = FALSE
ORDER BY floor, spot_number
LIMIT 1
FOR UPDATE;                -- row-level lock

UPDATE parking_spot SET is_occupied = TRUE, vehicle_id = :vid
WHERE spot_id = :spotId;

COMMIT;
```

`FOR UPDATE` acquires an exclusive row lock. The second transaction blocks until the first commits, then finds that spot is taken and selects the next one.

| Aspect     | Detail                                       |
|------------|----------------------------------------------|
| Pros       | Simple, guaranteed correctness               |
| Cons       | Blocking under high concurrency              |
| Fits when  | Low-medium concurrency (parking lot: perfect) |

### Solution 2: Optimistic Locking (CAS)

```sql
-- Read spot and its version
SELECT spot_id, version FROM parking_spot
WHERE spot_type = 'CAR' AND is_occupied = FALSE
ORDER BY floor, spot_number LIMIT 1;

-- Attempt update only if version unchanged
UPDATE parking_spot
SET is_occupied = TRUE, vehicle_id = :vid, version = version + 1
WHERE spot_id = :spotId AND version = :readVersion;

-- If rows_affected == 0, retry with next available spot
```

| Aspect     | Detail                                       |
|------------|----------------------------------------------|
| Pros       | No blocking, higher throughput               |
| Cons       | Retry logic needed                           |
| Fits when  | Higher concurrency environments              |

### Solution 3: Database Unique Constraint

Add a UNIQUE constraint on `(spot_id)` in an `active_assignments` table. Only one vehicle can hold a spot. Concurrent inserts for the same spot result in a constraint violation -- catch and retry.

### Solution 4: In-Memory Synchronization (Java)

```java
// Using synchronized block
synchronized (floorLocks[floor]) {
    ParkingSpot spot = findAvailableSpot(vehicleType, floor);
    if (spot != null) {
        spot.setOccupied(true);
        // persist to DB
    }
}

// Or using ConcurrentHashMap + AtomicBoolean per spot
spotAvailability.computeIfAbsent(spotId, k -> new AtomicBoolean(true));
if (spotAvailability.get(spotId).compareAndSet(true, false)) {
    // spot successfully claimed
}
```

### Recommendation

For a single parking lot (850 spots, ~5 entries/min peak): **pessimistic locking with `SELECT ... FOR UPDATE`** is simple, correct, and more than performant enough. No need to over-engineer.

---

## 13. Scaling

### Single Parking Lot (Current Scope)

Scaling is **minimal** for a single lot. The system handles at most a few transactions per second. A single application server + single PostgreSQL instance is sufficient.

```
  [Entry Gate 1] --\
  [Entry Gate 2] ---+--> [Single App Server] --> [PostgreSQL]
  [Exit Gate 1]  ---+        (Spring Boot)
  [Exit Gate 2]  --/
```

### Multi-Lot Chain (100+ Parking Lots)

For a parking lot company managing many locations:

```
                        +-------------------+
                        |  Central Cloud    |
                        |  Dashboard        |
                        |  (Management UI)  |
                        +-------------------+
                                |
                    +-----------+-----------+
                    |                       |
            +-------+-------+     +--------+--------+
            | Lot A (Local) |     | Lot B (Local)   |
            | App + DB      |     | App + DB        |
            +---------------+     +-----------------+
```

| Concern                  | Approach                                          |
|--------------------------|---------------------------------------------------|
| **Per-lot independence**  | Each lot has its own service + DB (microservice)  |
| **Central management**   | Cloud dashboard aggregates data from all lots     |
| **Data sync**            | Each lot pushes occupancy metrics to central API  |
| **Offline resilience**   | Lot continues operating if central is unreachable |
| **Load balancing**       | Not needed per lot; needed for central dashboard  |
| **Reporting**            | Central data warehouse for analytics across lots  |

### Horizontal Scaling Triggers

| Metric                        | Threshold    | Action                            |
|-------------------------------|--------------|-----------------------------------|
| Transactions per second       | > 100 TPS    | Add read replicas for availability|
| Number of managed lots        | > 50         | Shard central DB by region        |
| Dashboard concurrent users    | > 1000       | Auto-scale dashboard service      |

---

## 14. Database Choice

### Recommendation: PostgreSQL (RDBMS)

| Factor               | Why PostgreSQL                                          |
|----------------------|---------------------------------------------------------|
| **Data is relational** | Tickets reference spots, spots reference vehicles      |
| **ACID required**      | Payment processing must be transactional               |
| **Foreign keys**       | Enforce integrity: ticket -> spot, payment -> ticket   |
| **Row-level locking**  | `SELECT ... FOR UPDATE` for concurrency control        |
| **Mature ecosystem**   | JDBC drivers, Spring Data JPA, well-understood         |
| **Sufficient scale**   | Single-lot system: thousands of rows, trivial for RDBMS|

### Why NOT NoSQL

| NoSQL Trait              | Why It Does Not Fit                                  |
|--------------------------|------------------------------------------------------|
| Eventual consistency     | Cannot tolerate stale spot availability               |
| Schema-less flexibility  | Our schema is well-defined and stable                 |
| Horizontal sharding      | Not needed for 850 spots                             |
| Document model           | Relationships between entities are important          |

### Index Strategy

```sql
-- Fast spot assignment lookup
CREATE INDEX idx_spot_assignment
ON parking_spot (spot_type, is_occupied, floor, spot_number)
WHERE is_occupied = FALSE;   -- partial index: only free spots

-- Ticket lookup by vehicle
CREATE INDEX idx_ticket_vehicle
ON parking_ticket (vehicle_id, entry_time DESC);

-- Active tickets (not yet exited)
CREATE INDEX idx_active_tickets
ON parking_ticket (exit_time)
WHERE exit_time IS NULL;
```

---

## 15. CAP Theorem

### Classification: CP (Consistency + Partition Tolerance)

```
        C (Consistency)
       / \
      /   \
     / CP  \     <--- Parking Lot System
    /       \
   +---------+
  A           P
(Availability)  (Partition Tolerance)
```

### Justification

| Property               | Analysis                                                |
|------------------------|---------------------------------------------------------|
| **Consistency (C)**    | CRITICAL. Cannot double-book a spot. Two tickets for one spot means a physical conflict in the real world. |
| **Availability (A)**   | Important but can briefly degrade. A gate can wait 1-2 seconds for a response. Drivers expect some delay. |
| **Partition Tolerance (P)** | Less relevant for a single-lot system (all components on local network). More relevant for multi-lot chain. |

### Practical Implications

- We choose **strong consistency** via database transactions and locking
- If the DB is momentarily unreachable, the entry gate shows "Please Wait" rather than guessing (favoring C over A)
- For a multi-lot chain, each lot is independently CP; the central dashboard can be AP (showing slightly stale aggregated data is acceptable)

---

## 16. Cloud Services

For a multi-lot deployment or cloud-hosted single lot:

| Component              | AWS                    | GCP                     | Azure                   |
|------------------------|------------------------|-------------------------|-------------------------|
| **Compute**            | EC2 / ECS Fargate      | Cloud Run / GKE         | App Service / AKS       |
| **Database**           | RDS (PostgreSQL)       | Cloud SQL (PostgreSQL)  | Azure Database for PG   |
| **Payment Gateway**    | Stripe / external      | Stripe / external       | Stripe / external       |
| **Monitoring**         | CloudWatch             | Cloud Monitoring        | Azure Monitor           |
| **Messaging (events)** | SQS / SNS             | Pub/Sub                 | Service Bus             |
| **Dashboard Hosting**  | S3 + CloudFront        | Cloud Storage + CDN     | Blob Storage + CDN      |
| **Secrets**            | Secrets Manager        | Secret Manager          | Key Vault               |

### Deployment Note

For a single parking lot, a cloud deployment may be overkill. A local on-premises server with PostgreSQL is often sufficient. Cloud becomes valuable when:
- Managing multiple lots centrally
- Needing remote monitoring/management
- Requiring high availability beyond what a single server provides

---

## 17. Tradeoffs Summary

| Decision                        | Chose                    | Alternative            | Why                                             |
|---------------------------------|--------------------------|------------------------|-------------------------------------------------|
| **Database**                    | PostgreSQL (RDBMS)       | MongoDB, DynamoDB      | Relational data, ACID for payments              |
| **Concurrency control**         | Pessimistic locking      | Optimistic / CAS       | Simpler, low concurrency makes blocking a non-issue |
| **Spot assignment**             | Nearest first            | Random, compact        | Best user experience; extensible via Strategy   |
| **Pricing model**               | Flat hourly              | Tiered, time-of-day    | Simple MVP; extensible via Strategy             |
| **Availability updates**        | DB query (poll)          | Event-driven (push)    | Simpler; 1-second poll is fine for display board|
| **Architecture**                | Monolith                 | Microservices          | Single lot does not need service boundaries     |
| **Ticket ID generation**        | Date-based sequential    | UUID                   | Human-readable, sortable by time                |
| **Payment processing**          | Synchronous at exit      | Pre-auth on entry      | Simpler; driver pays and leaves immediately     |
| **Consistency model**           | Strong (CP)              | Eventual (AP)          | Cannot tolerate double-booked spots             |

---

## 18. Interview Talking Points

### This Is Primarily an LLD Question

While this HLD provides system context, interviewers will spend 80% of the time on **Low-Level Design**: class diagrams, OOP relationships, method signatures, and code structure. The HLD context helps you set the stage.

### Key Points to Hit

#### OOP and Class Design
- **Inheritance:** `Vehicle` (abstract) -> `Car`, `Motorcycle`, `Bus`
- **Composition:** `ParkingLot` has `Floor`s, `Floor` has `ParkingSpot`s
- **Encapsulation:** Spot assignment logic hidden behind `ParkingService`
- **Abstraction:** `PaymentMethod` interface, multiple implementations

#### SOLID Principles in Action

| Principle                    | Application in This Design                          |
|------------------------------|-----------------------------------------------------|
| **S** - Single Responsibility | `ParkingService` assigns spots; `PaymentService` handles money; `AvailabilityService` tracks counts |
| **O** - Open/Closed          | New vehicle types or pricing models added via new classes, not modifying existing code |
| **L** - Liskov Substitution  | Any `Vehicle` subtype can be passed to `assignSpot()` |
| **I** - Interface Segregation | Separate interfaces for `Payable`, `Assignable`, `Displayable` |
| **D** - Dependency Inversion  | `ParkingService` depends on `SpotAssignmentStrategy` interface, not a concrete implementation |

#### Design Patterns to Mention

| Pattern       | Where Used                                            |
|---------------|-------------------------------------------------------|
| **Strategy**  | Spot assignment policies, pricing models              |
| **Factory**   | Creating `Vehicle` objects from type string            |
| **Singleton** | `ParkingLot` instance (debatable -- discuss tradeoffs) |
| **Observer**  | Availability display subscribes to spot changes       |
| **State**     | Ticket lifecycle: ACTIVE -> PAID -> EXITED            |

#### Thread Safety (Always Gets Asked)

- Explain the double-booking race condition clearly
- Show you know `synchronized`, `ReentrantLock`, `AtomicBoolean`
- Explain `SELECT ... FOR UPDATE` at the database level
- Mention that for a parking lot, pessimistic locking is the right call (low contention)

#### Common Follow-Up Questions

| Question                                     | Good Answer                                           |
|----------------------------------------------|-------------------------------------------------------|
| "What if the system crashes mid-transaction?" | DB transaction rollback; spot remains free            |
| "How do you handle lost tickets?"             | Look up by license plate in DB                        |
| "What if someone parks in the wrong spot?"    | Out of scope for software; sensor could detect        |
| "How would you add EV charging spots?"        | New `SpotType` enum value + `EVChargingSpot` subclass |
| "What about handicapped spots?"               | Priority assignment + `SpotType.HANDICAPPED`          |
| "How to handle a payment gateway timeout?"    | Retry with idempotency key; fallback to cash          |
| "How would you test this?"                    | Unit tests for pricing, integration tests for entry/exit flow, concurrency tests with thread pool |

### Time Management (45-Minute Interview)

| Phase              | Time   | Focus                                         |
|--------------------|--------|-----------------------------------------------|
| Requirements       | 5 min  | Clarify scope, vehicle types, features        |
| HLD / Architecture | 5 min  | Quick diagram, components, data flow          |
| Class Design (LLD) | 20 min | Classes, relationships, patterns -- THIS IS THE CORE |
| Concurrency        | 5 min  | Double-booking, locking strategy              |
| Code Walkthrough   | 5 min  | Implement one key method (e.g., `assignSpot`) |
| Extensions         | 5 min  | Discuss EV, reservations, scaling             |

---

## Quick Reference Card

```
  PARKING LOT SYSTEM AT A GLANCE
  ===============================

  Capacity:  850 spots (5 floors x 170 spots/floor)
  Vehicles:  Motorcycle | Car | Bus/Truck
  Pricing:   $1/hr | $2/hr | $5/hr
  Database:  PostgreSQL (ACID, relational)
  CAP:       CP (consistency over availability)
  Patterns:  Strategy, Factory, Singleton, Observer, State
  Key NFR:   Thread-safe spot allocation, <100ms ticket issuance
  Concurrency: Pessimistic locking (SELECT ... FOR UPDATE)
```

---

*HLD prepared for Senior Java Developer (7+ years) interview preparation.*
*Covers system design context for a primarily LLD-focused interview question.*
