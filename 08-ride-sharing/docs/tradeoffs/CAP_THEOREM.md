# CAP Theorem & Distributed Tradeoffs in the Ride-Sharing System

> Interview-ready reference for a Senior Java developer.
> A ride-sharing system is a SPLIT CAP system -- different components make different tradeoffs.
> Ride state is CP (no double-booking drivers). Location tracking is AP (stale location is OK).

---

## Table of Contents

| Section | Key Point |
|---------|-----------|
| CAP Classification | Split: CP for ride state, AP for location tracking |
| Why Split CAP | Ride assignment needs consistency, location needs availability |
| Ride State: CP Analysis | No double-booking, strict state machine |
| Location Tracking: AP Analysis | Stale GPS is fine, availability is critical |
| Network Partition Scenarios | What happens when things go wrong |
| Consistency Models by Service | Per-service consistency choices |
| Uber's Architecture | Real-world reference architecture |
| PACELC Extension | What happens when there is NO partition |
| Comparison Table | All services side-by-side |
| Interview Q&A | Ready-to-use answers |

---

## CAP Classification: This Is a SPLIT System

```
         Consistency (C)
            /\
           /  \
          / CP \  <--- RIDE STATE (assignment, state machine)
         /------\
        /   AP   \  <--- LOCATION TRACKING (GPS, nearby drivers)
       /____________\
  Availability (A) --- Partition Tolerance (P)
```

### The Split Approach

Unlike a distributed cache (pure AP) or a banking system (pure CP), a ride-sharing platform has components that MUST choose different sides of CAP:

| Component | CAP Choice | Why |
|-----------|-----------|-----|
| Ride Assignment | **CP** | Driver assigned to two rides simultaneously = disaster |
| Ride State Machine | **CP** | REQUESTED -> MATCHED -> COMPLETED must be consistent |
| Driver Availability | **CP** | Available/busy flag must be accurate (no phantom drivers) |
| Payment Processing | **CP** | Double-charge or no-charge = financial loss |
| Location Tracking | **AP** | 5-second-old GPS is fine; unavailable location = no matching |
| Surge Pricing | **AP** | Slightly stale surge multiplier is OK; no surge = revenue loss |
| Driver ETA | **AP** | Estimated 4 min instead of 5 min is fine; no ETA = bad UX |
| Ride History | **AP** | Eventual consistency for past rides is acceptable |
| Notifications | **AP** | Delayed notification is better than no notification |

---

## Ride State: CP Analysis (Consistency + Partition Tolerance)

### Why Ride State MUST Be Consistent

```
  PROBLEM: Two riders request a ride. One driver is available.
  Without consistency, BOTH riders could be matched to the same driver.

  Rider A                  RideService (Node 1)         Driver Pool
     |                          |                          |
     | (1) requestRide()        |                          |
     |------------------------->|                          |
     |                          | (2) findAvailable()      |
     |                          |------------------------->|
     |                          |  [Driver D1: AVAILABLE]  |
     |                          |<-------------------------|
     |                          |                          |
     |                          | (3) assign D1 to Rider A |
     |                          |   D1.status = BUSY       |
     |                          |                          |

  Rider B                  RideService (Node 2)         Driver Pool
     |                          |                          |
     | (1) requestRide()        |                          |
     |------------------------->|                          |
     |                          | (2) findAvailable()      |
     |                          |------------------------->|
     |                          |                          |
     |                          | *** RACE CONDITION ***   |
     |                          | Node 2 hasn't seen       |
     |                          | D1.status = BUSY yet!    |
     |                          |  [Driver D1: AVAILABLE]  |  <--- STALE!
     |                          |<-------------------------|
     |                          |                          |
     |                          | (3) assign D1 to Rider B |  <--- DOUBLE BOOKING!
     |                          |   D1.status = BUSY       |
```

### How CP Prevents Double-Booking

```
  SOLUTION: Strong consistency for driver assignment

  Rider A             RideService              Database (CP)           Rider B
     |                    |                        |                      |
     | (1) requestRide    |                        |                      |
     |------------------>|                        |                      |
     |                    | (2) BEGIN TRANSACTION  |                      |
     |                    | SELECT driver           |                      |
     |                    | WHERE status='AVAILABLE'|                      |
     |                    | FOR UPDATE (row lock)   |                      |
     |                    |----------------------->|                      |
     |                    |  [D1: AVAILABLE, LOCKED]|                      |
     |                    |<-----------------------|                      |
     |                    |                        |                      |
     |                    |                        |      (3) requestRide |
     |                    |                        |<---------------------|
     |                    |                        |                      |
     |                    |                        | (4) SELECT ... FOR   |
     |                    |                        |   UPDATE -> BLOCKED  |
     |                    |                        |   (row is locked!)   |
     |                    |                        |                      |
     |                    | (5) UPDATE driver      |                      |
     |                    |   SET status='BUSY'    |                      |
     |                    |   WHERE id='D1'        |                      |
     |                    |----------------------->|                      |
     |                    |                        |                      |
     |                    | (6) COMMIT             |                      |
     |                    |----------------------->|                      |
     |                    |                        |                      |
     |                    |                        | (7) UNBLOCK Rider B  |
     |                    |                        |   D1 is now BUSY     |
     |                    |                        |   -> no available    |
     |                    |                        |     drivers for B    |
     |                    |                        |--------------------->|
     |                    |                        |  "No driver found"   |
     |                    |                        |                      |
     | ride assigned D1   |                        |                      |
     |<------------------|                        |                      |
```

### Consistency Mechanisms for Ride State

| Mechanism | How It Works | Tradeoff |
|-----------|-------------|----------|
| Database row locking | `SELECT ... FOR UPDATE` locks driver row during assignment | Blocks concurrent requests -- serializes assignment |
| Optimistic locking | Version column: `UPDATE WHERE version = X` | Retry on conflict -- better throughput |
| Distributed lock (Redis) | `SETNX driver:{id}:lock` with TTL | Works across services, but Redis is AP |
| Saga pattern | Multi-step: reserve -> assign -> confirm | Complex but handles distributed state |

### Cost of Choosing CP for Ride State

```
  During network partition between Service Node A and Service Node B:

  CP Behavior:
  +-----------+     partition     +-----------+
  | Node A    | ----XXXXX------- | Node B    |
  | (can't    |                  | (can't    |
  |  verify   |                  |  verify   |
  |  driver   |                  |  driver   |
  |  status)  |                  |  status)  |
  +-----------+                  +-----------+
       |                               |
       v                               v
  REFUSES to assign       REFUSES to assign
  (returns error)         (returns error)
       |                               |
       v                               v
  "No drivers available"  "No drivers available"

  Result: Riders can't get rides during partition
  But: No driver is assigned to two rides (SAFE)

  This is acceptable because:
  1. Network partitions are rare (seconds to minutes)
  2. Double-booking a driver is FAR worse than a brief outage
  3. Riders retry -- a 30-second wait is better than a confused driver
```

---

## Location Tracking: AP Analysis (Availability + Partition Tolerance)

### Why Location Can Be Eventually Consistent

```
  Driver sends GPS update every 3-5 seconds.
  Even with perfect consistency, location is already 3-5 seconds stale.

  Timeline:
  =========
  t=0s   Driver at (37.7749, -122.4194)    <- actual position
  t=3s   GPS update sent                     <- 3 seconds stale already
  t=3.1s Arrives at server                   <- network latency
  t=3.5s Replicated to other nodes           <- eventual consistency delay
  t=5s   Another GPS update overwrites       <- stale data self-corrects

  The inherent staleness of GPS (3-5s) DWARFS any replication delay (100ms).
  Making location strongly consistent would add latency for NO benefit.
```

### AP Architecture for Location

```
  Driver App          Location Service (Node A)    Location Service (Node B)    Rider App
     |                        |                          |                        |
     | (1) GPS update         |                          |                        |
     |   lat=37.77            |                          |                        |
     |   lng=-122.42          |                          |                        |
     |----------------------->|                          |                        |
     |                        | (2) ACK immediately      |                        |
     |  ACK                   |   (don't wait for        |                        |
     |<-----------------------|    replication)           |                        |
     |                        |                          |                        |
     |                        | (3) async replicate      |                        |
     |                        |------------------------->|                        |
     |                        |                          |                        |
     |                        |                          |   (4) findNearby       |
     |                        |                          |   (37.78, -122.41)     |
     |                        |                          |<-----------------------|
     |                        |                          |                        |
     |                        |                          | (5) return drivers     |
     |                        |                          |   [D1: 37.77, -122.42] |
     |                        |                          |   (slightly stale OK)  |
     |                        |                          |----------------------->|
     |                        |                          |                        |

  Even if replication is delayed:
  - Driver shows 100 meters from actual position
  - At 30 mph, 5 seconds of staleness = ~67 meters
  - For "nearby driver" queries, this is FINE
  - ETA calculation already accounts for uncertainty
```

### What Happens During Network Partition (Location)

```
  +-----------+     partition     +-----------+
  | Node A    | ----XXXXX------- | Node B    |
  | (has      |                  | (has      |
  |  driver   |                  |  stale    |
  |  GPS from |                  |  GPS from |
  |  3s ago)  |                  |  8s ago)  |
  +-----------+                  +-----------+
       |                               |
       v                               v
  Returns 3s-old         Returns 8s-old
  locations              locations
  (still useful)         (still useful)

  AP Behavior:
  - Both nodes SERVE requests (available)
  - Node B has slightly staler data
  - Drivers still appear on map (maybe slightly wrong position)
  - Matching still works (radius query is approximate anyway)
  - When partition heals, latest GPS update wins (last-write-wins)

  Alternative (if we chose CP):
  - Node B REFUSES location queries
  - Riders on Node B see NO drivers on map
  - No ride matching possible for those riders
  - MUCH WORSE user experience for minimal accuracy gain
```

---

## Consistency Models by Service

### Ride Assignment: Linearizable Consistency

```
  The strongest consistency model. Every operation appears to execute
  atomically at some point between its invocation and completion.

  Operation A: Assign D1 to Rider A
  Operation B: Assign D1 to Rider B

  ----[===A===]----------->  time
  --------[===B===]------->  time
              ^
              |
              B sees A's result (D1 is BUSY)

  Implementation:
  - Single-leader database (PostgreSQL)
  - Row-level locking (SELECT FOR UPDATE)
  - Serializable isolation level for critical paths
```

### Location Tracking: Eventual Consistency

```
  After an update, all replicas will EVENTUALLY return the same value.
  No guarantee on when.

  Write: Driver at (37.77, -122.42) at t=0

  Node A: (37.77, -122.42) at t=0      <- immediate
  Node B: (37.76, -122.41) at t=0      <- stale (previous location)
  Node B: (37.77, -122.42) at t=0.1s   <- converged

  Convergence window: 50-200ms (well within GPS update interval)
```

### Surge Pricing: Bounded Staleness

```
  Surge multiplier computed every 30 seconds for each zone.
  All nodes guaranteed to have surge data no older than 60 seconds.

  t=0s    Surge computed: Zone A = 1.8x
  t=0.1s  Primary node updated
  t=0.5s  All replicas updated
  t=30s   Surge recomputed: Zone A = 2.1x
  t=30.5s All replicas updated

  Bound: maximum staleness = 60 seconds (two computation intervals)
  This is fine because:
  - Surge changes gradually (demand doesn't spike in 30s)
  - Riders see "estimated fare" before confirming (can refresh)
  - Slight over/under-charge corrected in final receipt
```

### Notifications: At-Least-Once Delivery

```
  Notifications are fire-and-forget with retry.
  Duplicate notification is better than no notification.

  RideService       Message Queue       NotificationWorker       Rider Phone
      |                  |                     |                      |
      | (1) publish      |                     |                      |
      |   "ride matched" |                     |                      |
      |----------------->|                     |                      |
      |                  | (2) deliver         |                      |
      |                  |-------------------->|                      |
      |                  |                     | (3) send push        |
      |                  |                     |--------------------->|
      |                  |                     |                      |
      |                  |                     | (4) ACK failed!      |
      |                  |                     |   (worker crashed)   |
      |                  |                     |                      |
      |                  | (5) redeliver       |                      |
      |                  |   (no ACK received) |                      |
      |                  |-------------------->|                      |
      |                  |                     | (6) send push AGAIN  |
      |                  |                     |--------------------->|
      |                  |                     |                      |
      |                  |                     |  Rider gets 2 pushes |
      |                  |                     |  (annoying but safe) |

  Idempotency key on notification ID prevents duplicate processing
  where it matters (email receipts), but push notifications are
  inherently idempotent ("Your driver is arriving" x2 is fine)
```

---

## Network Partition Scenarios

### Scenario 1: Partition Between Ride Service and Driver Database

```
  +---------------+     XXXXX     +------------------+
  | Ride Service  | ----XXXXX---- | Driver Database   |
  | (can't read   |    partition  | (has driver data) |
  |  driver       |               |                   |
  |  availability)|               |                   |
  +---------------+               +------------------+

  Impact:
  - Cannot verify driver availability
  - Cannot assign new rides (CP choice: reject request)
  - In-progress rides CONTINUE (driver/rider already connected)
  - Rider gets "No drivers available, try again" message

  Mitigation:
  - Local cache of driver availability (30-second TTL)
  - During partition, use cached data with WARNING flag
  - Retry with exponential backoff
  - Degrade gracefully: "High demand, try again in 1 minute"
```

### Scenario 2: Partition Between Two Ride Service Instances

```
  +-------------------+     XXXXX     +-------------------+
  | Ride Service A    | ----XXXXX---- | Ride Service B    |
  | (serving Region 1)|   partition   | (serving Region 2)|
  +-------------------+               +-------------------+
         |                                     |
         v                                     v
  +-------------------+               +-------------------+
  | DB Primary        |               | DB Replica        |
  | (Region 1)        |               | (Region 2)        |
  +-------------------+               +-------------------+

  Impact:
  - Region 1 and Region 2 can independently assign rides
  - Problem: Driver D1 is in Region 1, crosses into Region 2
  - Region 2 doesn't know D1 is already on a ride
  - D1 appears "available" in Region 2's stale data

  Mitigation:
  - Driver state stored in single-leader DB (not per-region)
  - Cross-region assignment goes through leader
  - OR: drivers belong to ONE region at a time (sharding by region)
  - Handoff protocol when driver crosses region boundary
```

### Scenario 3: Partition Between Location Service and Ride Service

```
  +-------------------+     XXXXX     +-------------------+
  | Ride Service      | ----XXXXX---- | Location Service  |
  | (needs nearby     |   partition   | (has GPS data)    |
  |  drivers)         |               |                   |
  +-------------------+               +-------------------+

  Impact:
  - Ride Service cannot query for nearby drivers
  - New ride requests fail (no location data for matching)

  Mitigation:
  - Ride Service maintains local cache of driver locations (5s TTL)
  - During partition, use cached locations (slightly stale)
  - Expand search radius to compensate for position error
  - Fall back to "last known location" for drivers
  - ETA becomes less accurate but rides can still be matched
```

---

## Uber's Architecture Choices (Reference)

### Uber's Real-World CAP Decisions

```
  +-------------------------------------------------------------------+
  |                    Uber's Architecture (Simplified)                |
  +-------------------------------------------------------------------+
  |                                                                   |
  |  DISPATCH SERVICE (CP)                                            |
  |  +-------------------+                                            |
  |  | - Driver assignment |  <- Consistent (Ringpop + Tchannel)     |
  |  | - Ride state       |  <- Serialized via consistent hashing    |
  |  | - Supply/demand    |  <- Single-owner per geofence cell       |
  |  +-------------------+                                            |
  |                                                                   |
  |  LOCATION SERVICE (AP)                                            |
  |  +-------------------+                                            |
  |  | - GPS ingestion   |  <- Cassandra (AP, tunable consistency)   |
  |  | - Nearby queries  |  <- Google S2 cells for geo-indexing      |
  |  | - ETA calculation |  <- Eventually consistent, 5s refresh    |
  |  +-------------------+                                            |
  |                                                                   |
  |  SURGE PRICING (AP)                                               |
  |  +-------------------+                                            |
  |  | - Demand/supply   |  <- Computed every 30-60 seconds          |
  |  |   ratio per cell  |  <- Redis for fast reads                  |
  |  | - Multiplier      |  <- Bounded staleness (60s max)           |
  |  +-------------------+                                            |
  |                                                                   |
  |  PAYMENT (CP)                                                     |
  |  +-------------------+                                            |
  |  | - Charge rider    |  <- Strong consistency (financial)        |
  |  | - Pay driver      |  <- Serializable transactions             |
  |  | - Refunds         |  <- Exactly-once semantics                |
  |  +-------------------+                                            |
  |                                                                   |
  +-------------------------------------------------------------------+
```

### Uber's Key Technologies for CAP Choices

| Service | Technology | CAP | Why |
|---------|-----------|-----|-----|
| Dispatch | Ringpop (consistent hashing) | CP | Each geofence cell has ONE owner -- no split-brain |
| Location | Cassandra | AP | High write throughput for millions of GPS updates/sec |
| Surge | Redis | AP | Fast reads, bounded staleness is acceptable |
| Payment | MySQL (Vitess) | CP | Financial transactions must be ACID |
| Trip History | Cassandra | AP | Eventual consistency fine for historical data |
| Notifications | Kafka + workers | AP | At-least-once delivery, idempotent |

### Uber's Ringpop (Dispatch Consistency)

```
  How Uber avoids double-booking without a centralized lock:

  Geofence Grid:
  +------+------+------+
  |  A1  |  A2  |  A3  |
  | Node1| Node2| Node1|
  +------+------+------+
  |  B1  |  B2  |  B3  |
  | Node3| Node1| Node2|
  +------+------+------+

  Each cell is OWNED by exactly one node (via consistent hashing).
  All ride requests in cell B2 go to Node1.
  Node1 serializes all assignments in B2 -- no race conditions.

  When driver crosses from B2 to B3:
  1. Node1 releases ownership of driver
  2. Node2 acquires ownership of driver
  3. Handoff protocol ensures no gap/overlap

  This is CP because:
  - During partition, the cell becomes unavailable (no owner)
  - But no double-booking is possible
```

---

## PACELC Extension

### What Is PACELC?

PACELC extends CAP by asking: when there is **No Partition** (normal operation), do you choose **Latency** or **Consistency**?

```
  if (Partition) {
      choose: Availability (A) or Consistency (C)   // CAP
  } else {
      choose: Latency (L) or Consistency (C)        // PACELC extension
  }
```

### PACELC Analysis for Ride-Sharing

| Component | During Partition (PA/PC) | Normal Operation (EL/EC) | Full Classification |
|-----------|------------------------|------------------------|-------------------|
| Ride Assignment | **PC** (refuse if can't verify) | **EC** (strong consistency, accept latency) | **PC/EC** |
| Driver Availability | **PC** (don't assign stale drivers) | **EC** (row lock, accept latency) | **PC/EC** |
| Location Tracking | **PA** (serve stale, stay available) | **EL** (async replication, low latency) | **PA/EL** |
| Surge Pricing | **PA** (serve stale surge) | **EL** (precomputed, cached, fast reads) | **PA/EL** |
| Payment | **PC** (refuse charges if uncertain) | **EC** (ACID transactions, accept latency) | **PC/EC** |
| Notifications | **PA** (deliver if possible) | **EL** (async queue, fire-and-forget) | **PA/EL** |
| Ride History | **PA** (serve stale history) | **EL** (eventual consistency, fast reads) | **PA/EL** |

### PACELC Diagram

```
  +-------------------------------------------------------------------+
  |                     PACELC Classification                         |
  +-------------------------------------------------------------------+
  |                                                                   |
  |   PC/EC (Consistent Path)           PA/EL (Available Path)       |
  |   ========================          =========================     |
  |                                                                   |
  |   +-------------------+             +-------------------+         |
  |   | Ride Assignment   |             | Location Tracking |         |
  |   | - Partition: STOP |             | - Partition: SERVE|         |
  |   | - Normal: LOCK    |             | - Normal: FAST    |         |
  |   +-------------------+             +-------------------+         |
  |                                                                   |
  |   +-------------------+             +-------------------+         |
  |   | Driver Avail.     |             | Surge Pricing     |         |
  |   | - Partition: STOP |             | - Partition: SERVE|         |
  |   | - Normal: LOCK    |             | - Normal: CACHED  |         |
  |   +-------------------+             +-------------------+         |
  |                                                                   |
  |   +-------------------+             +-------------------+         |
  |   | Payment           |             | Notifications     |         |
  |   | - Partition: STOP |             | - Partition: QUEUE|         |
  |   | - Normal: ACID    |             | - Normal: ASYNC   |         |
  |   +-------------------+             +-------------------+         |
  |                                                                   |
  +-------------------------------------------------------------------+
```

### Normal Operation Tradeoffs (EL vs EC)

```
  RIDE ASSIGNMENT (EC -- accept latency for consistency)
  ======================================================

  Rider              RideService              Database
    |                     |                       |
    | requestRide()       |                       |
    |-------------------->|                       |
    |                     | BEGIN TRANSACTION      |
    |                     | SELECT FOR UPDATE      |
    |                     | (acquires row lock)    |
    |                     |----------------------->|
    |                     |                  ~5ms  |  <--- LATENCY COST
    |                     |<-----------------------|
    |                     | UPDATE status=BUSY     |
    |                     | COMMIT                 |
    |                     |----------------------->|
    |                     |                  ~3ms  |  <--- LATENCY COST
    |                     |<-----------------------|
    |  ride assigned      |                       |
    |<--------------------|                       |

    Total: ~15ms for consistent assignment
    This is FINE -- rider is waiting seconds for a match anyway


  LOCATION TRACKING (EL -- accept staleness for low latency)
  ===========================================================

  Driver              LocationService           Redis GEO
    |                     |                       |
    | GPS update          |                       |
    | (37.77, -122.42)    |                       |
    |-------------------->|                       |
    |                     | GEOADD drivers        |
    |                     |   -122.42 37.77 D1    |
    |                     |----------------------->|
    |  ACK                |                 ~1ms  |  <--- FAST
    |<--------------------|<-----------------------|

    No locking, no replication wait, no consensus.
    At 1M+ GPS updates per minute, latency MUST be low.
```

---

## Comparison Table: All Services

| Service | Consistency | Availability | Latency (p99) | During Partition | Data Loss Risk | PACELC |
|---------|------------|-------------|---------------|-----------------|---------------|--------|
| Ride Assignment | Strong (linearizable) | Reduced during partition | 15-50ms | Reject new rides | None (consistent) | PC/EC |
| Driver Availability | Strong (row lock) | Reduced during partition | 10-30ms | Use cached (degraded) | None | PC/EC |
| Location Tracking | Eventual (~200ms) | Always available | 1-5ms | Serve stale GPS | 3-5s of GPS data | PA/EL |
| Surge Pricing | Bounded (60s) | Always available | 1-2ms (cached) | Serve stale surge | 0-60s of surge data | PA/EL |
| Payment | Strong (ACID) | Reduced during partition | 50-200ms | Queue for retry | None | PC/EC |
| Notifications | At-least-once | Always available | 10-50ms | Queue and retry | Duplicate possible | PA/EL |
| Ride History | Eventual (~1s) | Always available | 5-20ms | Serve stale history | Seconds of history | PA/EL |
| ETA Calculation | Eventual (~5s) | Always available | 5-15ms | Use last known ETA | 5-10s of ETA data | PA/EL |

---

## Design Decisions: When the Interviewer Asks "Why?"

### "Why not make everything strongly consistent?"

```
  If ALL services were CP:

  GPS Update Rate:    1,000,000 updates/minute (Uber scale)
  Consensus Round:    5-10ms per update (Raft/Paxos)
  Required Throughput: 16,667 consensus rounds/second
  Available Throughput: ~5,000 consensus rounds/second (typical Raft)

  Result: System can't keep up. GPS updates queue up.
           Drivers show 30+ seconds behind.
           Matching becomes useless.

  With AP for location:
  Write Throughput:   100,000+ writes/second (Redis GEO)
  Staleness:          200ms (negligible vs 3s GPS interval)
  Result:             Real-time driver positions on map
```

### "Why not make everything eventually consistent?"

```
  If Ride Assignment were AP:

  Scenario: 100 riders request rides at 5:00 PM rush hour
            50 available drivers

  Without consistency:
  - All 100 requests see 50 "available" drivers
  - 80 riders get matched to someone already assigned
  - Riders wait, then get "driver cancelled" notification
  - Retry storm: all 80 retry, same problem repeats
  - Result: Cascading failure, no one gets a ride

  With consistency (CP):
  - Request 1 locks Driver 1, assigns, releases
  - Request 2 locks Driver 2, assigns, releases
  - ...
  - Request 51: "No drivers available"
  - Result: 50 successful rides, 50 clean "no driver" messages
```

---

## Interview Q&A

### Q: "Is a ride-sharing system CP or AP?"

> **A:** "Neither -- it's a split system. Ride assignment and payment are CP because double-booking a driver or double-charging a rider are unacceptable. Location tracking and surge pricing are AP because stale GPS data (3-5 seconds old) is inherently approximate anyway, and unavailable location data would make matching impossible. The key insight is that different components in the same system can make different CAP tradeoffs based on their specific consistency requirements."

### Q: "What happens during a network partition?"

> **A:** "It depends on which partition. If the ride assignment service is partitioned from the database, we stop assigning new rides (CP -- safety over availability). In-progress rides continue fine because the driver/rider are already connected. If the location service is partitioned, we serve stale GPS data (AP -- a 5-second-old position is still useful). The system degrades gracefully: riders see 'high demand, try again shortly' for assignment failures, but existing rides complete normally."

### Q: "How does Uber handle this at scale?"

> **A:** "Uber uses Ringpop for dispatch -- consistent hashing assigns each geofence cell to exactly one node, preventing double-booking without distributed locks. Location uses Cassandra (AP) for high write throughput of millions of GPS updates per second. Surge pricing is precomputed every 30-60 seconds and cached in Redis. Payment goes through MySQL with Vitess for sharding, using strong ACID transactions. The key architectural insight is cell-based ownership: each geographic cell has a single owner that serializes all assignments within it."

### Q: "How do you prevent double-booking without distributed locks?"

> **A:** "Three approaches: (1) Single-leader database with row-level locking -- `SELECT FOR UPDATE` serializes concurrent assignments to the same driver. (2) Optimistic locking with a version column -- the second writer's UPDATE fails because the version changed. (3) Cell-based ownership (Uber's approach) -- consistent hashing makes one service instance the owner of a geographic cell, so all assignments in that cell are serialized locally. Option 3 is best at scale because it avoids cross-node coordination."

### Q: "What's the PACELC classification?"

> **A:** "Ride assignment is PC/EC -- during partition we prioritize consistency (no double-booking), and during normal operation we still choose consistency (row locks) and accept slightly higher latency (~15ms). Location tracking is PA/EL -- during partition we stay available with stale data, and during normal operation we choose low latency (async replication to Redis GEO, ~1ms writes). The split follows the principle: CP for things where correctness matters more than speed, AP for things where freshness is already bounded by real-world constraints (GPS interval, demand computation window)."

---

## Cross-Reference to Other Projects

| Project | CAP Choice | Why |
|---------|-----------|-----|
| 01 - URL Shortener | CP (read-after-write) | Short URL must resolve correctly |
| 02 - Rate Limiter | AP (approximate counts OK) | Over-counting slightly is better than no limiting |
| 03 - Notification System | AP (at-least-once) | Duplicate notification > missed notification |
| 04 - Chat System | CP (message ordering) | Messages must appear in order |
| 05 - Social Media Feed | AP (eventual consistency) | Stale feed is fine |
| 06 - Parking Lot | CP (spot assignment) | No double-booking parking spots |
| 07 - Distributed Cache | AP (stale data OK) | Cache miss is better than cache unavailability |
| **08 - Ride Sharing** | **Split: CP (ride state) + AP (location)** | **Different components, different requirements** |
