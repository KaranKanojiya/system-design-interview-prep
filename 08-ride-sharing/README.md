# Ride-Sharing System (Uber/Lyft)

## Problem Summary

Design a **ride-sharing platform** (like Uber/Lyft) that matches riders with nearby drivers in real-time, tracks driver locations via GPS, calculates dynamic surge pricing, and manages the full ride lifecycle from request to payment. The core challenges are **spatial indexing for nearest-driver queries** (QuadTree/GeoHash), **real-time location tracking at scale** (50K+ GPS updates/sec), **driver-rider matching** (ETA-aware, not just nearest), and **surge pricing** (demand/supply ratio per zone). The system must handle millions of daily rides with sub-second matching latency.

---

## 1-Minute Interview Revision

**Memorize these bullets. They cover 80% of what interviewers want to hear.**

- **QuadTree: recursive space partitioning, O(log N) range query, find K nearest drivers.** Subdivide when a cell has > threshold drivers. Leaf nodes hold driver lists.
- **GeoHash: lat/lng to base32 string, prefix matching for proximity, 12-char = 3.7cm precision.** Shared prefix = same region. 6-char = ~1.2km cell. Great for DB indexing.
- **Haversine: great-circle distance on a sphere, accounts for Earth's curvature.** O(1) per pair. Never use Euclidean distance for lat/lng -- longitude degrees shrink toward poles.
- **Matching: nearest driver vs ETA-based (traffic-aware). Timeout + cascade to next.** Send request to top-K drivers sorted by ETA. 15s timeout, then cascade to next driver.
- **Surge: demand/supply ratio per zone. Tiers: 1.25x to 3.0x. Caps at 3x.** Zone = H3 hexagon or GeoHash cell. Recalculate every 30 seconds.
- **State machine: REQUESTED -> MATCHED -> EN_ROUTE -> IN_PROGRESS -> COMPLETED/CANCELLED.** Each transition is atomic. Only valid transitions allowed (no REQUESTED -> COMPLETED).
- **CAP: CP for ride state (no double-booking), AP for location (stale OK).** Ride assignment uses row-level locking. Driver GPS can be 3-4 seconds stale.

---

## Class Hierarchy

```
Location (value object)                  Ride (entity, state machine)
  |-- lat, lng                             |-- rideId, rider, driver
  |-- distanceTo() [Haversine]             |-- pickup, dropoff, status
  |-- EARTH_RADIUS_KM = 6371              |-- fare, surgeMultiplier
                                           |-- requestRide(), acceptRide()
                                           |-- startRide(), completeRide()

SpatialIndex (interface)                 MatchingStrategy (interface)
  |-- QuadTreeIndex                        |-- NearestDriverStrategy
  |-- GeoHashIndex                         |-- ETABasedStrategy

PricingStrategy (interface)              Driver / Rider (entities)
  |-- BaseFarePricing                      |-- userId, name, location
  |-- SurgePricingDecorator                |-- status (AVAILABLE, ON_TRIP, etc.)

RideSharingService (Facade)              SurgeManager
  |-- requestRide()                        |-- calculateSurge(zone)
  |-- findNearbyDrivers()                  |-- demand/supply ratio
  |-- calculateFare()                      |-- tier thresholds

AppConfig (wiring)
  |-- creates services, strategies, indexes
```

---

## Key Components

| Component | Role |
|-----------|------|
| `Location` | Value object with lat/lng. Haversine distance calculation. O(1) per pair. |
| `Ride` | Entity with state machine (REQUESTED -> MATCHED -> EN_ROUTE -> IN_PROGRESS -> COMPLETED). |
| `Driver` / `Rider` | User entities with location, status, rating. |
| `SpatialIndex` | Interface for nearest-driver queries. QuadTree and GeoHash implementations. |
| `QuadTreeIndex` | Recursive space partitioning. O(log N) range query. Subdivide at threshold. |
| `GeoHashIndex` | Lat/lng to base32 string. Prefix matching for proximity. DB-friendly. |
| `MatchingStrategy` | Interface for driver selection. Nearest vs ETA-based. Strategy pattern. |
| `PricingStrategy` | Interface for fare calculation. Base fare + surge via Decorator pattern. |
| `SurgeManager` | Demand/supply ratio per zone. Recalculates every 30 seconds. Tiers 1.0x-3.0x. |
| `RideSharingService` | Facade. Orchestrates matching, pricing, ride lifecycle. Single entry point. |
| `AppConfig` | Wires everything together. Creates indexes, strategies, services. |

---

## Key Tradeoffs

| Decision | Option A | Option B | This Design |
|----------|----------|----------|-------------|
| Spatial index | QuadTree (in-memory, O(log N)) | GeoHash (DB-friendly, prefix match) | **Both via Strategy** -- QuadTree for demo, GeoHash for production |
| Matching algorithm | Nearest driver (simple) | ETA-based (traffic-aware) | **ETA-based** -- nearest != fastest; traffic matters |
| Surge calculation | Static zones (city grid) | Dynamic zones (H3 hexagons) | **H3-style zones** -- uniform area, adjustable resolution |
| Location store | PostgreSQL + PostGIS | Redis GEO commands | **Redis GEO** -- sub-ms for 50K updates/sec, AP is fine |
| Ride state consistency | Eventual (AP) | Strong (CP) | **CP** -- no double-booking a driver to two riders |
| Driver timeout | Fixed (15s) | Dynamic (by distance) | **Fixed 15s** -- simple, predictable, cascade to next |
| Location update frequency | Every 1s (fresh) | Every 4s (cheaper) | **Every 4s** -- good enough for matching, 4x less traffic |
| Fare calculation | Pre-calculated (estimate) | Post-trip (actual GPS) | **Both** -- estimate before, actual after via GPS trace |

---

## SOLID Principles

| Principle | Example |
|-----------|---------|
| **S** -- Single Responsibility | `QuadTreeIndex` handles only spatial queries. `SurgeManager` handles only pricing multipliers. |
| **O** -- Open/Closed | Add `ETABasedStrategy` without modifying `RideSharingService`. New strategy = new class. |
| **L** -- Liskov Substitution | Any `SpatialIndex` implementation (QuadTree, GeoHash) works wherever the interface is expected. |
| **I** -- Interface Segregation | `SpatialIndex`, `MatchingStrategy`, `PricingStrategy` are separate interfaces. Matching doesn't need pricing. |
| **D** -- Dependency Inversion | `RideSharingService` depends on `SpatialIndex` interface, not `QuadTreeIndex` class. |

---

## Design Patterns

| Pattern | Where | Why |
|---------|-------|-----|
| **Strategy** (x3) | SpatialIndex, MatchingStrategy, PricingStrategy | Each dimension varies independently |
| **Facade** | RideSharingService | Single entry point for requestRide, findDrivers, calculateFare |
| **State Machine** | Ride status transitions | Enforce valid transitions, prevent illegal states |
| **Decorator** | SurgePricingDecorator wraps BaseFarePricing | Add surge multiplier without modifying base fare logic |
| **Observer** | Location updates notify matching service | Decoupled: driver moves, matching recalculates |
| **Factory** | StrategyFactory creates spatial/matching strategies | Encapsulate strategy selection logic |
| **Builder** | Ride.Builder | 10+ fields -- avoids telescoping constructor |
| **Repository** | DriverRepository, RideRepository | Abstract storage backend, swap in-memory for DB |

---

## How to Run

```bash
cd /Users/kanojik/Documents/Karan/Autodesk_AI_Development/systemDesign
./gradlew :08-ride-sharing:run
```

---

## Demo Output Preview

```
========================================
  RIDE-SHARING SYSTEM DEMO
========================================

--- QuadTree Spatial Index Demo ---
Building QuadTree for downtown area...
  Bounds: (40.70, -74.02) to (40.80, -73.95)
  Inserting 50 drivers...
  QuadTree depth: 4, leaf nodes: 16

Finding 3 nearest drivers to rider at (40.7484, -73.9856):
  1. Driver D-017  (40.7501, -73.9832)  dist=0.27 km  ETA=2 min
  2. Driver D-033  (40.7462, -73.9891)  dist=0.41 km  ETA=3 min
  3. Driver D-008  (40.7519, -73.9810)  dist=0.58 km  ETA=4 min

--- Ride Lifecycle Demo ---
Rider R-001 requests ride: (40.7484, -73.9856) --> (40.7580, -73.9855)
  Status: REQUESTED
  Matching... sending to Driver D-017 (nearest, ETA=2 min)
  Driver D-017 accepts!
  Status: REQUESTED --> MATCHED
  Driver en route to pickup...
  Status: MATCHED --> EN_ROUTE
  Driver arrived. Trip started.
  Status: EN_ROUTE --> IN_PROGRESS
  Trip completed. Distance: 1.07 km, Duration: 6 min
  Status: IN_PROGRESS --> COMPLETED

--- Surge Pricing Demo ---
Zone: Manhattan-Midtown (GeoHash: dr5ru7)
  Demand (ride requests): 150
  Supply (available drivers): 60
  Ratio: 2.50
  Surge multiplier: 2.0x (tier: HIGH)

Fare calculation:
  Base fare:        $2.50
  Distance (1.07 km * $1.75/km): $1.87
  Time (6 min * $0.35/min):      $2.10
  Subtotal:         $6.47
  Surge (2.0x):     $12.94
  Booking fee:      $1.50
  Total:            $14.44

--- Haversine Distance Demo ---
  Times Square to Central Park:  2.03 km  (Haversine, O(1))
  JFK to LaGuardia:              16.87 km
  NYC to London:                 5,570 km (still accurate!)

========================================
  DEMO COMPLETE
========================================
```

---

## Quick Reference

```
QuadTree:         O(log N) range query, O(log N) insert    (recursive space partitioning)
GeoHash:          O(1) encode, O(1) prefix match           (base32 string, 6-char ~ 1.2km)
Haversine:        O(1) distance calculation                 (great-circle, Earth radius 6371km)
Matching:         O(K log N) find K nearest drivers         (QuadTree range + sort by ETA)
Surge:            O(1) per zone lookup                      (demand/supply ratio, 30s refresh)
Redis GEOSEARCH:  O(log N + K) within radius                (production spatial index)
State Machine:    6 states, 7 valid transitions             (REQUESTED -> ... -> COMPLETED)
Location Updates: 50K/sec (200K drivers, every 4 seconds)   (Kinesis/Kafka, Redis GEO)
```

---

## What to Improve Later

- [ ] Full QuadTree implementation with range query visualization
- [ ] GeoHash encode/decode with neighbor cell lookup
- [ ] ETA-based matching with simulated traffic data
- [ ] Pool ride matching (shared rides, detour calculation)
- [ ] H3 hexagonal grid integration for surge zones
- [ ] Driver timeout and cascade (15s timeout, try next K drivers)
- [ ] Fare splitting for pool rides
- [ ] Real-time location streaming with Observer pattern
- [ ] Ride cancellation penalty logic
- [ ] Rating system with weighted moving average
