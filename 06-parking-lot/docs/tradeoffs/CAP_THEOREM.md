# CAP Theorem & Concurrency in the Parking Lot System

> Interview-ready reference for a Senior Java developer.
> A parking lot is NOT a distributed system -- but CAP thinking still applies to concurrency tradeoffs.
> The real challenge here is concurrent access, not network partitions.

---

## Table of Contents

| Section | Key Point |
|---------|-----------|
| CAP Classification | CP system -- consistency over availability |
| Why Consistency Wins | Cannot double-book a spot |
| Concurrency Strategies | Pessimistic vs Optimistic vs Database-level |
| Multi-Lot Extension | When it DOES become distributed |
| Interview Q&A | Ready-to-use answers |

---

## CAP Classification: This Is a CP System

```
         Consistency (C)
            /\
           /  \
          / CP \  <--- PARKING LOT IS HERE
         /------\
        /        \
       /    CA    \
      /____________\
  Availability (A) --- Partition Tolerance (P)
```

### Why Consistency Over Availability

| Scenario | Consistent (CP) | Available (AP) |
|----------|-----------------|----------------|
| Two cars enter simultaneously, one spot left | One gets the spot, other waits 1-2 seconds | Both get a ticket for the same spot -- COLLISION |
| Gate sensor fails | Gate stays closed until sensor recovers | Gate opens, no record of entry -- revenue loss |
| Payment service slow | Driver waits 3 seconds at exit | Exit opens, payment skipped -- revenue loss |

**The fundamental rule**: A 1-second wait at the gate is acceptable. A double-booked spot is not.

### Why Partition Tolerance Is Less Relevant

For a single parking lot with a local database, there are no network partitions. All components (gate controller, spot sensors, payment terminal, display boards) are on the same LAN or even the same machine. CAP's "P" matters when you have a multi-lot chain with a central dashboard.

---

## Concurrency: The Real Challenge

The critical operation: **two cars arrive at the same time, both need a spot**.

```
  Thread A (Car enters Gate 1)         Thread B (Car enters Gate 2)
  ============================         ============================
  1. findAvailableSpot()               1. findAvailableSpot()
  2. Gets Spot #42 (AVAILABLE)         2. Gets Spot #42 (AVAILABLE)  <-- SAME SPOT!
  3. spot.park(carA)                   3. spot.park(carB)
  4. Spot #42 now has... which car?    4. DATA CORRUPTION
```

### Strategy 1: Pessimistic Locking (synchronized)

```java
public class ParkingService {

    public synchronized ParkingTicket parkVehicle(Vehicle vehicle) {
        ParkingSpot spot = parkingStrategy
            .findSpot(vehicle, parkingLot.getFloors())
            .orElseThrow(() -> new ParkingFullException("No spots"));

        spot.park(vehicle);  // Safe -- only one thread here
        return ticketService.issueTicket(vehicle, spot);
    }
}
```

| Pros | Cons |
|------|------|
| Simple to implement | Entire lot is locked per operation |
| Easy to reason about | Throughput bottleneck at peak hours |
| No race conditions | Gate 2 waits while Gate 1 is parking |

**Best for**: Single-entrance lot, low traffic, interview demo.

### Strategy 2: Fine-Grained Locking (per-spot)

```java
public class ParkingSpot {

    private final Object lock = new Object();

    public boolean tryPark(Vehicle vehicle) {
        synchronized (lock) {                    // Lock THIS spot only
            if (!isAvailable() || !canFitVehicle(vehicle)) {
                return false;                    // Spot taken, try next
            }
            this.currentVehicle = vehicle;
            this.status = SpotStatus.OCCUPIED;
            return true;
        }
    }
}

// In ParkingStrategy:
public Optional<ParkingSpot> findSpot(Vehicle vehicle, List<Floor> floors) {
    return floors.stream()
        .flatMap(f -> f.getSpots().stream())
        .filter(s -> s.tryPark(vehicle))         // Atomic check-and-park
        .findFirst();
}
```

| Pros | Cons |
|------|------|
| Higher throughput | More complex code |
| Cars on different floors don't block each other | Must handle "spot taken, try next" retry |
| Scales with number of gates | Potential for livelock under extreme contention |

**Best for**: Multi-entrance lot, moderate traffic.

### Strategy 3: Optimistic Locking (CAS / Compare-And-Swap)

```java
public class ParkingSpot {

    private final AtomicReference<SpotStatus> status =
        new AtomicReference<>(SpotStatus.AVAILABLE);

    public boolean tryPark(Vehicle vehicle) {
        if (!canFitVehicle(vehicle)) return false;

        // Atomic compare-and-swap: only succeeds if still AVAILABLE
        boolean parked = status.compareAndSet(
            SpotStatus.AVAILABLE,
            SpotStatus.OCCUPIED
        );
        if (parked) {
            this.currentVehicle = vehicle;
        }
        return parked;
    }
}
```

| Pros | Cons |
|------|------|
| No locks at all | CAS retry loop under high contention |
| Maximum throughput | More complex error handling |
| Non-blocking | Harder to reason about correctness |

**Best for**: High-traffic lot, many concurrent entries.

### Strategy 4: Database-Level Locking

```sql
-- UNIQUE constraint prevents double-booking at the DB level
ALTER TABLE parking_spot
    ADD CONSTRAINT uq_occupied_spot
    UNIQUE (spot_id) WHERE status = 'OCCUPIED';

-- Transaction with SELECT FOR UPDATE
BEGIN;
    SELECT * FROM parking_spot
    WHERE status = 'AVAILABLE' AND spot_type = 'COMPACT'
    ORDER BY floor_number, spot_number
    LIMIT 1
    FOR UPDATE;                          -- Row-level lock

    UPDATE parking_spot
    SET status = 'OCCUPIED', vehicle_id = ?
    WHERE spot_id = ?;
COMMIT;
```

| Pros | Cons |
|------|------|
| Database guarantees consistency | Requires RDBMS |
| Survives application restarts | Slightly higher latency (DB round-trip) |
| Supports multiple app instances | Must handle deadlocks |

**Best for**: Production system, multiple application instances.

---

## Concurrency Decision Matrix

```
  Interview Demo (single-threaded)
      |
      v
  synchronized on ParkingService     <-- START HERE in interview
      |
      | "What about multiple gates?"
      v
  synchronized per ParkingSpot        <-- Show you understand granularity
      |
      | "What about high throughput?"
      v
  AtomicReference + CAS               <-- Show you know lock-free
      |
      | "What about production?"
      v
  Database SELECT FOR UPDATE          <-- Show you know real-world
```

---

## Multi-Lot Extension: When It Becomes Distributed

When the interviewer asks: "What if there are 50 parking lots across a city?"

### Architecture

```
  +----------+   +----------+   +----------+
  |  Lot A   |   |  Lot B   |   |  Lot C   |
  | (Local   |   | (Local   |   | (Local   |
  |  DB, CP) |   |  DB, CP) |   |  DB, CP) |
  +----+-----+   +----+-----+   +----+-----+
       |              |              |
       v              v              v
  +----+--------------+--------------+-----+
  |         Central Dashboard (AP)         |
  |     Redis cache, read replicas         |
  |   "Lot A: 42 spots, Lot B: 15 spots"  |
  +----------------------------------------+
       |
       v
  +----+-----+
  | Mobile   |
  | App      |
  | (AP, OK  |
  | if stale)|
  +----------+
```

### CAP Split

| Component | CAP | Why |
|-----------|-----|-----|
| Individual lot | CP | Cannot double-book a spot |
| Central dashboard | AP | Stale count (42 vs 41 spots) is fine |
| Mobile app | AP | "Available" badge can be 30 seconds old |
| Cross-lot reservation | CP | If user reserves at Lot B, it must be guaranteed |

### Key Insight

Each lot is **independent** -- no cross-lot coordination needed for parking/unparking. The central dashboard is eventually consistent. This is a natural partition boundary.

---

## Practical Interview Answer

When asked "How does your parking lot handle concurrency?"

> "For a single lot, I'd use **synchronized per spot** -- fine-grained locking that allows parallel parking on different floors while preventing double-booking of the same spot. Each spot has its own lock object. The `tryPark()` method does an atomic check-and-park.
>
> For production with a database, I'd use **SELECT FOR UPDATE** -- row-level locking at the database layer. This handles multiple application instances and survives restarts.
>
> For a multi-lot chain, each lot is CP (consistent, no double-booking). The central dashboard is AP (stale availability counts are acceptable). The lots are independent -- no distributed transactions needed."

---

## Follow-Up Q&A

### Q: "What if two cars arrive at the exact same millisecond?"

**A**: In Java, `synchronized` blocks are serviced one at a time by the JVM. Thread A enters the synchronized block first (arbitrary scheduling), parks successfully. Thread B enters next, finds the spot occupied, moves to the next available spot. No double-booking.

### Q: "What about deadlocks?"

**A**: Deadlocks require two locks acquired in different orders. Our design locks ONE spot at a time -- no circular dependency. If we ever need to lock multiple spots (e.g., bus needs 3 consecutive spots), we lock them in a fixed order (by spot ID) to prevent deadlocks.

### Q: "Why not use a distributed lock like Redis SETNX?"

**A**: For a single parking lot, that's overengineering. All threads are in the same JVM. Java's `synchronized` or `AtomicReference` is faster (nanoseconds vs milliseconds), simpler, and has no external dependency. Redis locks are for multi-instance deployments.

### Q: "How do you handle a crash mid-parking?"

**A**: If the application crashes after finding a spot but before creating a ticket, the spot is locked in memory but the vehicle never parked. On restart, we reload from the database (or re-initialize from sensors). The spot reverts to AVAILABLE. For the in-memory demo, this is a known limitation -- mention it and say "in production, the database is the source of truth."

### Q: "What's the throughput of your system?"

**A**: With per-spot locking, the throughput is limited by the number of unique spots being accessed concurrently, not by a global lock. A 500-spot lot can handle 500 concurrent `tryPark()` calls without contention (assuming they target different spots). Real bottleneck is the physical gate speed (~1 car every 3-5 seconds per gate), not the software.

---

## Summary

| Aspect | Single Lot | Multi-Lot Chain |
|--------|-----------|-----------------|
| CAP | CP (consistency) | CP per lot, AP for dashboard |
| Primary challenge | Concurrency | Distribution + concurrency |
| Locking | synchronized / CAS | Database row-level locks |
| Double-booking prevention | Java-level locks | DB UNIQUE constraint |
| Stale data tolerance | Zero (at the spot level) | Acceptable (at dashboard level) |
