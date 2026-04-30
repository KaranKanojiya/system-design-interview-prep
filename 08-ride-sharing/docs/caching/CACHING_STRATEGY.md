# Caching Strategies for the Ride-Sharing System

> Interview-ready reference for a Senior Java developer.
> A ride-sharing system has extreme caching diversity: sub-second TTL for driver locations, 30-second TTL for surge pricing, and NO cache for ride state.
> Knowing WHAT to cache and WHAT NOT to cache is the real interview answer.

---

## Table of Contents

| Section | Key Point |
|---------|-----------|
| What to Cache | Driver locations, surge multipliers, fare estimates, ride history |
| What NOT to Cache | Ride state, payment data, driver availability |
| Multi-Layer Caching | L1 (in-app) -> L2 (Redis GEO) -> L3 (PostGIS) |
| Driver Location Caching | Hot data, 3-5s TTL, GeoHash-based keys |
| Surge Pricing Cache | 30s TTL, zone-based, cache warming |
| Cache Invalidation | Driver availability transitions |
| Geospatial Caching | GeoHash keys, neighbor queries, hot spot handling |
| Hot Spot Handling | Airports, stadiums, downtown clusters |
| Cache Warming | Pre-compute popular zones |
| Interview Q&A | Ready-to-use answers |

---

## What to Cache vs What NOT to Cache

### The Caching Decision Matrix

```
  +--------------------------------------------------------------------+
  |                      CACHE DECISION MATRIX                         |
  +--------------------------------------------------------------------+
  |                                                                    |
  |  CACHE (hot, stale-tolerant)      DON'T CACHE (must be consistent)|
  |  ============================      ===============================  |
  |                                                                    |
  |  +---------------------+          +---------------------+          |
  |  | Driver Locations    |          | Ride State          |          |
  |  | TTL: 3-5 seconds    |          | (REQUESTED->MATCHED)|          |
  |  | Stale GPS is OK     |          | Must be real-time   |          |
  |  +---------------------+          +---------------------+          |
  |                                                                    |
  |  +---------------------+          +---------------------+          |
  |  | Surge Multipliers   |          | Payment Data        |          |
  |  | TTL: 30 seconds     |          | (charges, refunds)  |          |
  |  | Computed periodically|          | Must be ACID        |          |
  |  +---------------------+          +---------------------+          |
  |                                                                    |
  |  +---------------------+          +---------------------+          |
  |  | Fare Estimates      |          | Driver Availability |          |
  |  | TTL: 60 seconds     |          | (available/busy)    |          |
  |  | Approximate is fine |          | Must prevent double |          |
  |  +---------------------+          |   booking           |          |
  |                                    +---------------------+          |
  |  +---------------------+                                           |
  |  | Ride History        |          +---------------------+          |
  |  | TTL: 5 minutes      |          | Driver Assignment   |          |
  |  | Past rides don't    |          | (who is assigned to  |          |
  |  |   change            |          |  which ride)         |          |
  |  +---------------------+          | Must be serialized   |          |
  |                                    +---------------------+          |
  |  +---------------------+                                           |
  |  | ETA Estimates       |                                           |
  |  | TTL: 10 seconds     |                                           |
  |  | Approximate is fine |                                           |
  |  +---------------------+                                           |
  |                                                                    |
  +--------------------------------------------------------------------+
```

### Detailed Cache/No-Cache Rationale

| Data | Cache? | TTL | Why |
|------|--------|-----|-----|
| Driver locations (GPS) | YES | 3-5s | GPS updates every 3-5s, inherently stale, high read frequency |
| Surge multipliers | YES | 30s | Computed every 30-60s, same value for all riders in a zone |
| Fare estimates | YES | 60s | Approximate is fine, rider sees "estimated fare $23-28" |
| Ride history | YES | 5 min | Past rides don't change, rider checks "my trips" frequently |
| ETA estimates | YES | 10s | Approximate is fine, already accounts for traffic variability |
| Popular routes | YES | 1 hour | Route data changes slowly, high reuse in urban areas |
| Driver ratings | YES | 5 min | Ratings change slowly, displayed on every match screen |
| **Ride state** | **NO** | N/A | REQUESTED -> MATCHED must be consistent, no double-booking |
| **Payment data** | **NO** | N/A | Financial data must be ACID, stale charge = wrong amount |
| **Driver availability** | **NO** | N/A | available/busy flag drives matching, stale = double-booking |
| **Driver assignment** | **NO** | N/A | Which driver is assigned to which ride must be real-time |

### Why NOT Cache Ride State

```
  SCENARIO: Caching ride state with 5-second TTL

  t=0.0s  Rider requests ride. Ride status: REQUESTED (DB + cache)
  t=0.5s  Driver found. Ride status: MATCHED (DB updated, cache still REQUESTED)
  t=1.0s  Rider's app reads cache -> sees REQUESTED (stale!)
          Rider thinks no driver found, requests ANOTHER ride
  t=3.0s  Second ride gets a driver too
  t=5.0s  Cache expires, rider sees MATCHED for BOTH rides
          Now rider has TWO rides, TWO drivers heading to same pickup

  Result: confused rider, wasted driver time, billing mess

  SOLUTION: Always read ride state from database (source of truth)
  The 5-10ms database read is acceptable for state transitions
  that happen a few times per ride (not thousands of times per second)
```

### Why NOT Cache Driver Availability

```
  SCENARIO: Caching driver availability with 3-second TTL

  t=0.0s  Driver D1 is AVAILABLE (DB + cache)
  t=0.1s  Rider A requests ride -> cache says D1 AVAILABLE -> assign D1
  t=0.2s  D1 set to BUSY in DB (cache still shows AVAILABLE for 2.8s)
  t=0.5s  Rider B requests ride -> cache says D1 AVAILABLE -> assign D1

  Result: D1 assigned to TWO rides simultaneously

  SOLUTION: Driver availability is read from DB with row-level locking
  SELECT ... WHERE status = 'AVAILABLE' FOR UPDATE

  This is one of the few cases where cache-aside would cause
  correctness issues, not just UX issues.
```

---

## Multi-Layer Caching Architecture

### L1 -> L2 -> L3 Cache Hierarchy

```
  Driver Phone        Rider Phone          Backend Services
      |                   |                      |
      | GPS every 3s      | "Show nearby"        |
      v                   v                      |
  +-------+          +-------+                   |
  | L1    |          | L1    |                   |
  | In-App|          | In-App|                   |
  | Buffer|          | Cache |                   |
  | (5    |          | (last |                   |
  |  GPS  |          |  map  |                   |
  |  pts) |          |  view)|                   |
  +---+---+          +---+---+                   |
      |                   |                      |
      | batch upload      | cache miss           |
      | every 3-5s        | (new area / TTL)     |
      v                   v                      |
  +---------------------------------------------------+
  | L2: Redis GEO Cluster                             |
  |                                                   |
  | GEOADD drivers -122.42 37.77 "D1"                |
  | GEORADIUS drivers -122.42 37.77 5 km COUNT 10    |
  |                                                   |
  | TTL: 10 seconds (auto-expire stale locations)     |
  | Throughput: 100K+ operations/sec                  |
  +---------------------------------------------------+
      |                                    |
      | cache miss or                      | persist
      | complex query                      | every 30s
      v                                    v
  +---------------------------------------------------+
  | L3: PostGIS (PostgreSQL)                           |
  |                                                   |
  | SELECT * FROM drivers                              |
  | WHERE ST_DWithin(location, pickup, 5000)          |
  | AND status = 'AVAILABLE'                          |
  | ORDER BY ST_Distance(location, pickup)            |
  |                                                   |
  | Source of truth for driver state                   |
  | Complex geo queries (polygon, geofence)           |
  +---------------------------------------------------+
```

### When Each Layer Is Used

| Query Type | L1 (In-App) | L2 (Redis GEO) | L3 (PostGIS) |
|-----------|------------|----------------|-------------|
| "Show drivers on map" (rider app) | YES -- animate from cached positions | Fallback if L1 expired | Not needed |
| "Find 5 nearest drivers" (matching) | NO -- need server-side consistency | YES -- GEORADIUS | Fallback if Redis down |
| "Is driver in airport geofence?" | NO -- complex polygon query | NO -- no polygon support | YES -- ST_Contains |
| "Driver location history" (disputes) | NO -- ephemeral | NO -- short TTL | YES -- historical data |
| "Surge zone computation" | NO | YES -- aggregate by zone | YES -- base data for computation |

### Numbered Flow -- "Find Nearby Drivers" with Multi-Layer Cache

```
  Rider App             API Gateway           Matching Service        Redis GEO        PostGIS
     |                      |                      |                     |               |
     | (1) requestRide      |                      |                     |               |
     |   pickup=(37.77,     |                      |                     |               |
     |    -122.42)          |                      |                     |               |
     |--------------------->|                      |                     |               |
     |                      | (2) findNearby       |                     |               |
     |                      |--------------------->|                     |               |
     |                      |                      |                     |               |
     |                      |                      | (3) GEORADIUS       |               |
     |                      |                      |   drivers           |               |
     |                      |                      |   -122.42 37.77    |               |
     |                      |                      |   5 km COUNT 10    |               |
     |                      |                      |-------------------->|               |
     |                      |                      |                     |               |
     |                      |                      |  [D3: 1.2km,       |               |
     |                      |                      |   D7: 2.1km,       |               |
     |                      |                      |   D1: 2.8km]       |               |
     |                      |                      |<--------------------|               |
     |                      |                      |                     |               |
     |                      |                      | (4) For each driver |               |
     |                      |                      |   check availability|               |
     |                      |                      |   from DB (NOT      |               |
     |                      |                      |   cache!)           |               |
     |                      |                      |---------------------------------------->|
     |                      |                      |                     |  D3: AVAILABLE |
     |                      |                      |                     |  D7: BUSY      |
     |                      |                      |                     |  D1: AVAILABLE |
     |                      |                      |<----------------------------------------|
     |                      |                      |                     |               |
     |                      |                      | (5) Filter: [D3,D1]|               |
     |                      |                      |   (D7 is busy)     |               |
     |                      |                      |                     |               |
     |                      |  match: D3 (nearest  |                     |               |
     |                      |   available)         |                     |               |
     |                      |<---------------------|                     |               |
     |                      |                      |                     |               |
     |  "Driver D3 is on    |                      |                     |               |
     |   the way! ETA 4min" |                      |                     |               |
     |<---------------------|                      |                     |               |

  KEY INSIGHT:
  - Step 3: Location from CACHE (Redis GEO) -- stale OK
  - Step 4: Availability from DATABASE (PostGIS) -- must be fresh
  - This split is why we cache locations but NOT availability
```

---

## Driver Location Caching

### GPS Update Flow

```
  Driver Phone          Location Service         Redis GEO           QuadTree (in-memory)
      |                       |                      |                      |
      | (1) GPS update        |                      |                      |
      |   lat=37.7751         |                      |                      |
      |   lng=-122.4193       |                      |                      |
      |   timestamp=now       |                      |                      |
      |   heading=45 deg      |                      |                      |
      |   speed=25 mph        |                      |                      |
      |---------------------->|                      |                      |
      |                       |                      |                      |
      |                       | (2) GEOADD drivers   |                      |
      |                       |   -122.4193 37.7751  |                      |
      |                       |   "D1"               |                      |
      |                       |   EX 10              |  <- 10s TTL          |
      |                       |--------------------->|                      |
      |                       |                      |                      |
      |                       | (3) Update QuadTree  |                      |
      |                       |   remove old point   |                      |
      |                       |   insert new point   |                      |
      |                       |--------------------------------------------->|
      |                       |                      |                      |
      |  ACK                  |                      |                      |
      |<----------------------|                      |                      |

  Update rate: every 3-5 seconds per driver
  At 500K drivers: ~100K-165K updates/second
  Redis handles this easily (100K+ ops/sec per node)
```

### TTL Strategy for Driver Locations

```
  TTL = 10 seconds (2-3x the GPS update interval)

  Why 10 seconds?
  - GPS updates every 3-5 seconds
  - If a driver disconnects, their location expires in 10 seconds
  - No "ghost drivers" on the map after 10 seconds
  - Short enough that stale position is still useful (driver moved ~100m)

  What happens when TTL expires:
  +---+---+---+---+---+---+---+---+---+---+---+---+---+
  | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 |10 |11 |12 |
  +---+---+---+---+---+---+---+---+---+---+---+---+---+
    ^           ^           ^
    |           |           |
    GPS1        GPS2        GPS3      <- Normal: TTL refreshed every 3-5s
    TTL=10s     TTL=10s     TTL=10s

  Driver disconnects after GPS2:
  +---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+
  | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 |10 |11 |12 |13 |14 |15 |
  +---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+
    ^           ^                                   ^
    |           |                                   |
    GPS1        GPS2 (last update)                  EXPIRED
                                                    (driver removed from cache)
                                                    (no longer appears on map)
```

### Redis GEO Commands for Location

```
  # Store driver location with TTL
  # Redis GEO doesn't support per-member TTL natively
  # Solution: combine GEOADD with a separate TTL key

  # (1) Update location
  GEOADD drivers -122.4193 37.7751 "driver-1"

  # (2) Set heartbeat TTL
  SET driver:driver-1:heartbeat 1 EX 10

  # (3) Periodic cleanup: find expired heartbeats
  #     Remove from GEO set if heartbeat expired
  #     (background job every 5 seconds)

  # Alternative: Use GEOSEARCHSTORE with STORE to maintain a clean set
  # Or: Rely on the matching service to filter out stale drivers
  #     by checking last_update_time > now() - 10s
```

---

## Surge Pricing Cache

### Surge Computation and Caching

```
  Surge Computation Pipeline:

  Every 30 seconds, per zone:
  +------------------------------------------------------------------+
  |                                                                  |
  | (1) Count ride requests in zone (last 5 minutes)                |
  |     demand = requests.count()                                    |
  |                                                                  |
  | (2) Count available drivers in zone (current)                   |
  |     supply = available_drivers.count()                           |
  |                                                                  |
  | (3) Compute ratio                                                |
  |     ratio = demand / supply                                      |
  |                                                                  |
  | (4) Map to multiplier                                            |
  |     ratio < 1.0  ->  surge = 1.0x  (no surge)                  |
  |     ratio 1.0-2.0 -> surge = 1.2x                              |
  |     ratio 2.0-3.0 -> surge = 1.5x                              |
  |     ratio 3.0-5.0 -> surge = 2.0x                              |
  |     ratio > 5.0   -> surge = 3.0x  (cap)                       |
  |                                                                  |
  | (5) Cache in Redis with 30s TTL                                  |
  |     SET surge:zone:9q8yy 1.5 EX 30                             |
  |                                                                  |
  +------------------------------------------------------------------+
```

### Surge Cache Keys (GeoHash-Based)

```
  Zone grid (GeoHash precision 5 ~ 5km cells):

  +--------+--------+--------+--------+
  | 9q8yx  | 9q8yy  | 9q8yz  | 9q8zb  |
  | surge: | surge: | surge: | surge: |
  | 1.0x   | 1.8x   | 1.5x   | 1.0x   |
  +--------+--------+--------+--------+
  | 9q8yv  | 9q8yw  | 9q8yq  | 9q8yr  |
  | surge: | surge: | surge: | surge: |
  | 1.0x   | 2.0x   | 1.2x   | 1.0x   |
  +--------+--------+--------+--------+

  Redis keys:
  SET surge:9q8yy 1.8 EX 30    <- downtown SF, high demand
  SET surge:9q8yw 2.0 EX 30    <- SoMa/Financial, very high demand
  SET surge:9q8yx 1.0 EX 30    <- residential, normal demand

  Lookup:
  (1) Encode rider's pickup to GeoHash: (37.77, -122.42) -> "9q8yy"
  (2) GET surge:9q8yy -> 1.8
  (3) Apply to fare: $23.50 * 1.8 = $42.30
```

### Numbered Flow -- Surge Lookup During Fare Calculation

```
  RideService        SurgePricingStrategy     SurgeService          Redis
      |                      |                     |                  |
      | (1) calculateFare    |                     |                  |
      |   (pickup, dropoff)  |                     |                  |
      |--------------------->|                     |                  |
      |                      | (2) delegate to     |                  |
      |                      |   StandardPricing   |                  |
      |                      |   -> baseFare=$23.50|                  |
      |                      |                     |                  |
      |                      | (3) getSurge        |                  |
      |                      |   Multiplier(pickup)|                  |
      |                      |------------------->|                  |
      |                      |                     |                  |
      |                      |                     | (4) encode pickup|
      |                      |                     |   to GeoHash     |
      |                      |                     |   -> "9q8yy"     |
      |                      |                     |                  |
      |                      |                     | (5) GET          |
      |                      |                     |   surge:9q8yy    |
      |                      |                     |----------------->|
      |                      |                     |   1.8            |
      |                      |                     |<-----------------|
      |                      |                     |                  |
      |                      |  multiplier=1.8     |                  |
      |                      |<--------------------|                  |
      |                      |                     |                  |
      |                      | (6) $23.50 * 1.8    |                  |
      |                      |   = $42.30          |                  |
      |                      |                     |                  |
      |  fare=$42.30         |                     |                  |
      |<---------------------|                     |                  |
```

### Surge Cache Miss Handling

```
  What if surge cache is empty (cold start or TTL expired)?

  Option 1: Default to 1.0x (no surge)
  - Pro: Rider gets a ride immediately
  - Con: Revenue loss if there IS surge demand

  Option 2: Compute on-the-fly
  - Pro: Accurate surge
  - Con: 50-100ms extra latency for ride request

  Option 3: Return last known surge with flag
  - Pro: Best of both worlds
  - Con: Slightly stale

  RECOMMENDATION: Option 3

  public double getSurgeMultiplier(Location pickup) {
      String geoHash = GeoHash.encode(pickup.getLat(), pickup.getLng(), 5);
      String key = "surge:" + geoHash;

      // Try cache
      Double cached = redis.get(key);
      if (cached != null) {
          return cached;
      }

      // Cache miss: use last known or default
      Double lastKnown = redis.get(key + ":last");
      if (lastKnown != null) {
          // Return stale value, trigger async recomputation
          surgeComputeQueue.enqueue(geoHash);
          return lastKnown;
      }

      // No data at all: default to 1.0x
      return 1.0;
  }
```

---

## Cache Invalidation for Driver Availability

### The State Transition Problem

```
  Driver goes through availability states during a ride:

  AVAILABLE  ->  BUSY (matched)  ->  BUSY (en route)  ->  BUSY (in progress)  ->  AVAILABLE

  Each transition MUST be reflected in the matching system:

  (1) AVAILABLE: driver appears in "nearby drivers" queries
  (2) BUSY: driver MUST NOT appear in "nearby drivers" queries
  (3) Back to AVAILABLE: driver appears again

  If cache invalidation is delayed, a BUSY driver appears AVAILABLE
  -> another rider gets matched to them -> double booking
```

### Invalidation Strategy: Event-Driven

```
  RideService            Event Bus              Location Cache          Matching Cache
      |                      |                       |                       |
      | (1) ride matched     |                       |                       |
      |   D1 -> BUSY         |                       |                       |
      |                      |                       |                       |
      | (2) publish event:   |                       |                       |
      |   DRIVER_BUSY(D1)    |                       |                       |
      |--------------------->|                       |                       |
      |                      |                       |                       |
      |                      | (3) invalidate D1     |                       |
      |                      |   from location cache |                       |
      |                      |   (don't remove --    |                       |
      |                      |    mark as busy)      |                       |
      |                      |---------------------->|                       |
      |                      |                       |                       |
      |                      | (4) invalidate D1     |                       |
      |                      |   from matching cache |                       |
      |                      |   (remove from        |                       |
      |                      |    available set)     |                       |
      |                      |---------------------------------------------->|
      |                      |                       |                       |
      |                      |                       |                       |
  ... ride completes ...     |                       |                       |
      |                      |                       |                       |
      | (5) ride completed   |                       |                       |
      |   D1 -> AVAILABLE    |                       |                       |
      |                      |                       |                       |
      | (6) publish event:   |                       |                       |
      |   DRIVER_AVAILABLE   |                       |                       |
      |   (D1)               |                       |                       |
      |--------------------->|                       |                       |
      |                      |                       |                       |
      |                      | (7) D1 back in        |                       |
      |                      |   available set       |                       |
      |                      |---------------------------------------------->|
      |                      |                       |                       |
```

### Redis Implementation: Separate Sets for Available/Busy

```
  Instead of caching a boolean "available" flag (which can be stale),
  maintain TWO separate Redis GEO sets:

  GEOADD drivers:available -122.42 37.77 "D1"
  GEOADD drivers:available -122.41 37.78 "D3"
  GEOADD drivers:available -122.43 37.76 "D7"

  GEOADD drivers:busy -122.40 37.79 "D2"
  GEOADD drivers:busy -122.44 37.75 "D5"

  When matching: query ONLY drivers:available
  GEORADIUS drivers:available -122.42 37.77 5 km COUNT 10

  When driver assigned:
  GEOADD drivers:busy -122.42 37.77 "D1"       // add to busy
  ZREM drivers:available "D1"                    // remove from available

  When ride completes:
  GEOADD drivers:available -122.38 37.80 "D1"   // add to available (new location)
  ZREM drivers:busy "D1"                         // remove from busy

  ATOMIC: Use Redis transaction (MULTI/EXEC) to move between sets
  MULTI
  ZREM drivers:available "D1"
  GEOADD drivers:busy -122.42 37.77 "D1"
  EXEC
```

---

## Geospatial Caching: GeoHash-Based Keys

### Cache Key Design for Spatial Queries

```
  Problem: "Find drivers near (37.7749, -122.4194) within 5km"

  BAD cache key: "nearby:37.7749:-122.4194:5km"
  - Every unique coordinate = different cache key
  - Rider at (37.7750, -122.4194) = cache MISS (1 meter away!)
  - Cache hit rate: ~0%

  GOOD cache key: "nearby:9q8yy:5km"  (GeoHash prefix)
  - All riders in the same ~5km GeoHash cell share the same cache key
  - Rider at (37.7749, -122.4194) AND (37.7750, -122.4194) = SAME key
  - Cache hit rate: 50-80% in dense areas

  GeoHash precision 5 = ~5km cell -> perfect for 5km radius queries
```

### GeoHash Caching Implementation

```java
public class GeoCacheService {
    private final RedisClient redis;
    private final LocationService locationService;

    private static final int GEOHASH_PRECISION = 5;    // ~5km cells
    private static final int NEARBY_CACHE_TTL = 5;     // 5 seconds

    public List<Driver> findNearbyDriversCached(double lat, double lng,
                                                  double radiusKm) {
        // (1) Encode to GeoHash for cache key
        String geoHash = GeoHash.encode(lat, lng, GEOHASH_PRECISION);
        String cacheKey = "nearby:" + geoHash + ":" + (int) radiusKm;

        // (2) Check cache
        List<Driver> cached = redis.getList(cacheKey);
        if (cached != null) {
            return cached;  // CACHE HIT
        }

        // (3) Cache miss: query actual spatial index
        // Must query target cell AND 8 neighbors (edge effect)
        Set<String> cellsToQuery = getNeighborCells(geoHash);
        cellsToQuery.add(geoHash);

        List<Driver> results = new ArrayList<>();
        for (String cell : cellsToQuery) {
            results.addAll(locationService.findInCell(cell));
        }

        // (4) Filter by actual radius (GeoHash cells are rectangular)
        results = results.stream()
            .filter(d -> Haversine.distance(lat, lng,
                d.getLocation().getLatitude(),
                d.getLocation().getLongitude()) <= radiusKm)
            .collect(Collectors.toList());

        // (5) Cache result
        redis.setList(cacheKey, results, NEARBY_CACHE_TTL);

        return results;
    }

    private Set<String> getNeighborCells(String geoHash) {
        // Returns 8 neighboring GeoHash cells
        // Handles edge effects where nearby drivers are
        // in adjacent cells
        return GeoHash.neighbors(geoHash);
    }
}
```

### Neighbor Query: Handling GeoHash Edge Effects

```
  Target cell: 9q8yy (rider is at the eastern edge)
  Nearby driver: in cell 9q8yz (just across the border, 100m away)

  Without neighbor query:
  +--------+--------+
  | 9q8yy  | 9q8yz  |
  |        |        |
  |      R | D      |    R=Rider, D=Driver (100m apart)
  |        |        |    Different cells! Cache miss for D!
  +--------+--------+

  With neighbor query (query cell + 8 neighbors):
  +--------+--------+--------+
  | 9q8yx  | 9q8yy  | 9q8yz  |
  |        | (target)|        |
  |        |      R | D      |
  +--------+--------+--------+
  | 9q8yv  | 9q8yw  | 9q8yq  |
  |        |        |        |
  +--------+--------+--------+

  Query all 9 cells, find driver D in 9q8yz
  Filter by actual Haversine distance: 100m < 5km -> include D
```

---

## Hot Spot Handling

### The Problem: Popular Pickup Locations

```
  Airport (SFO):           Downtown (Financial District):
  500 ride requests/minute  300 ride requests/minute

  +--+--+--+--+--+--+     +--+--+--+--+--+--+
  |  |  |  |  |  |  |     |  |  |  |XX|XX|  |
  +--+--+--+--+--+--+     +--+--+--+--+--+--+
  |  |  |XX|  |  |  |     |  |  |XX|XX|XX|  |
  +--+--+--+--+--+--+     +--+--+--+--+--+--+
  |  |  |  |  |  |  |     |  |  |XX|XX|  |  |
  +--+--+--+--+--+--+     +--+--+--+--+--+--+

  XX = hot zone              XX = hot zone
  (concentrated at one cell) (spread across multiple cells)

  Problem: Cache key "nearby:9q8xy:5km" gets 500 reads/minute
  If cache expires, 500 requests hit the database simultaneously
  = THUNDERING HERD / CACHE STAMPEDE
```

### Solution 1: Cache Warming for Known Hot Spots

```
  Pre-compute and cache nearby drivers for known hot spots
  BEFORE riders request them.

  Background Job (runs every 3 seconds):

  HOT_SPOTS = [
      {"name": "SFO Airport", "lat": 37.6213, "lng": -122.3790},
      {"name": "Financial District", "lat": 37.7946, "lng": -122.3999},
      {"name": "Union Square", "lat": 37.7880, "lng": -122.4074},
      {"name": "AT&T Park", "lat": 37.7786, "lng": -122.3893},
      {"name": "Caltrain Station", "lat": 37.7764, "lng": -122.3944},
  ]

  for each hotspot:
      drivers = findNearbyDrivers(hotspot.lat, hotspot.lng, 5km)
      geoHash = GeoHash.encode(hotspot.lat, hotspot.lng, 5)
      redis.set("nearby:" + geoHash + ":5", drivers, TTL=5s)

  Result: When a rider at SFO requests a ride, the cache is already warm
  No database query needed. Latency: <1ms.
```

### Solution 2: Staggered TTL to Prevent Thundering Herd

```java
// Problem: all cache entries for hot zones expire at the same time
// 500 requests hit the DB simultaneously

// Solution: add random jitter to TTL
public void cacheNearbyDrivers(String geoHash, List<Driver> drivers) {
    int baseTTL = 5;  // 5 seconds
    int jitter = ThreadLocalRandom.current().nextInt(0, 3);  // 0-2 seconds
    int ttl = baseTTL + jitter;  // 5-7 seconds

    redis.setList("nearby:" + geoHash + ":5", drivers, ttl);
}

// Now entries expire between t=5 and t=7 seconds
// First expiry triggers one DB query and re-caches
// Remaining 499 requests hit the refreshed cache
```

### Solution 3: Probabilistic Early Expiration (XFetch)

```java
// Before TTL expires, probabilistically refresh the cache
// As TTL gets closer to expiration, probability of refresh increases

public List<Driver> findNearbyWithXFetch(double lat, double lng,
                                          double radiusKm) {
    String geoHash = GeoHash.encode(lat, lng, 5);
    String cacheKey = "nearby:" + geoHash + ":" + (int) radiusKm;

    CacheEntry<List<Driver>> entry = redis.getWithMetadata(cacheKey);

    if (entry != null) {
        double ttlRemaining = entry.getTTLSeconds();
        double totalTTL = 5.0;
        double delta = totalTTL * 0.2;  // refresh window = last 20% of TTL

        // Probabilistic refresh: higher chance as TTL decreases
        boolean shouldRefresh = ttlRemaining < delta
            && Math.random() < (1 - ttlRemaining / delta);

        if (!shouldRefresh) {
            return entry.getValue();  // use cached value
        }

        // Refresh in background, return cached value now
        asyncRefresh(cacheKey, lat, lng, radiusKm);
        return entry.getValue();
    }

    // Cache miss: synchronous fetch
    return fetchAndCache(cacheKey, lat, lng, radiusKm);
}
```

### Solution 4: Request Coalescing (Singleflight)

```
  Multiple concurrent requests for the same hot spot?
  Only ONE hits the database. Others wait for the result.

  Request 1 ----+
  Request 2 ----+----> ONE database query ----> result shared with all 5
  Request 3 ----+
  Request 4 ----+
  Request 5 ----+

  Implementation (similar to Go's singleflight):

  public class SingleFlight<K, V> {
      private final ConcurrentHashMap<K, CompletableFuture<V>> inflight
          = new ConcurrentHashMap<>();

      public V execute(K key, Supplier<V> loader) {
          CompletableFuture<V> future = inflight.computeIfAbsent(key, k -> {
              CompletableFuture<V> f = CompletableFuture.supplyAsync(loader);
              f.whenComplete((v, ex) -> inflight.remove(key));
              return f;
          });
          return future.join();  // all requests wait for the same result
      }
  }

  // Usage:
  SingleFlight<String, List<Driver>> singleFlight = new SingleFlight<>();

  public List<Driver> findNearby(String geoHash) {
      return singleFlight.execute(geoHash, () -> {
          // Only ONE thread executes this, even if 500 call findNearby
          return locationService.findNearby(geoHash);
      });
  }
```

---

## Event-Specific Caching: Stadiums, Concerts, Airports

### Airport Caching Strategy

```
  Airports have unique caching needs:
  - Arrivals: surge of ride requests when flights land
  - Departures: drivers queue for the next pickup
  - Geofence: special pricing rules inside airport boundary

  +------------------------------------------------------------------+
  | Airport Cache Strategy                                            |
  +------------------------------------------------------------------+
  |                                                                   |
  | ARRIVAL TERMINAL                                                  |
  | +----------------------------------------------+                  |
  | | Cache: "airport:SFO:arrivals:nearby_drivers"  |                  |
  | | TTL: 3 seconds (very fresh)                   |                  |
  | | Warm: YES (background job, every 3s)          |                  |
  | | Content: sorted list of queued drivers         |                  |
  | +----------------------------------------------+                  |
  |                                                                   |
  | FLIGHT SCHEDULE                                                   |
  | +----------------------------------------------+                  |
  | | Cache: "airport:SFO:arrivals:next_hour"       |                  |
  | | TTL: 5 minutes                                |                  |
  | | Content: list of flights arriving in 60 min   |                  |
  | | Used for: pre-positioning drivers              |                  |
  | +----------------------------------------------+                  |
  |                                                                   |
  | GEOFENCE PRICING                                                  |
  | +----------------------------------------------+                  |
  | | Cache: "airport:SFO:surcharge"                |                  |
  | | TTL: 1 hour                                   |                  |
  | | Content: flat surcharge amount ($3.80)         |                  |
  | | Rarely changes (regulatory)                    |                  |
  | +----------------------------------------------+                  |
  |                                                                   |
  +------------------------------------------------------------------+
```

### Stadium / Concert Event Handling

```
  EVENT: Concert at Chase Center, ends at 10:00 PM
  EXPECTED: 18,000 people requesting rides between 10:00-10:30 PM

  CACHE WARMING TIMELINE:

  9:30 PM  - Pre-compute surge for Chase Center zone
           - SET surge:9q8yn 2.5 EX 60
           - Pre-warm nearby driver cache
           - Notify drivers: "Event ending at Chase Center, position nearby"

  9:50 PM  - Increase cache refresh rate from 5s to 2s for this zone
           - Expand search radius from 5km to 10km in cache
           - Pre-allocate request coalescing for this GeoHash

  10:00 PM - Event ends, requests spike
           - Cache serves 90% of requests (warmed)
           - 10% cache misses handled by singleflight (coalesced)
           - No thundering herd

  10:30 PM - Demand normalizes
           - Return to normal cache refresh rate (5s)
           - Surge cache returns to normal TTL (30s)

  PRE-WARMING COMMAND:
  List<Driver> driversNearChaseCenter = findNearby(37.7680, -122.3877, 10km);
  redis.setList("nearby:9q8yn:10", driversNearChaseCenter, 3);  // 3s TTL
  redis.set("surge:9q8yn", "2.5", "EX", "60");
```

---

## Cache Sizing and Memory

### Memory Estimation

```
  Driver Location Cache (Redis GEO):
  ===================================
  Per driver: member name (20 bytes) + score (8 bytes) + overhead (50 bytes)
  = ~78 bytes per driver

  500,000 active drivers * 78 bytes = 39 MB
  With metadata (heading, speed): 500,000 * 200 bytes = 100 MB

  Verdict: EASILY fits in a single Redis node (even 1 GB is overkill)


  Surge Cache:
  ============
  Number of GeoHash-5 cells covering a major city: ~1,000 cells
  Per cell: key (20 bytes) + value (8 bytes) + TTL overhead (30 bytes) = ~58 bytes

  1,000 cells * 58 bytes = 58 KB

  Verdict: NEGLIGIBLE memory. Could cache 1M cities.


  Nearby Driver Cache:
  ====================
  Per cached query: key (30 bytes) + list of 10 driver IDs (200 bytes) = ~230 bytes
  Active cells (popular areas): ~5,000 cells

  5,000 * 230 bytes = 1.15 MB

  Verdict: NEGLIGIBLE. Even with 100K cells: 23 MB.


  TOTAL CACHE MEMORY (one city):
  ==============================
  Driver locations:   100 MB
  Surge data:         < 1 MB
  Nearby queries:     < 25 MB
  Driver metadata:    50 MB (ratings, vehicle info)
  --------------------------------
  Total:              ~175 MB

  A single Redis node with 2 GB RAM handles a major city easily.
  For redundancy: Redis Sentinel or Redis Cluster with 3 nodes.
```

---

## Cache Metrics to Monitor

### Key Metrics

| Metric | Target | Alert Threshold | Why |
|--------|--------|----------------|-----|
| Cache hit rate (nearby) | > 70% | < 50% | Low hit rate = DB overloaded |
| Cache hit rate (surge) | > 95% | < 80% | Surge is pre-computed, should rarely miss |
| p99 cache latency | < 2ms | > 10ms | Redis should be sub-millisecond |
| Stale location age | < 10s | > 30s | Drivers showing in wrong position |
| Cache memory usage | < 80% | > 90% | Approaching eviction pressure |
| Thundering herd count | 0 | > 5/minute | Singleflight not working |
| Cache invalidation lag | < 1s | > 5s | BUSY drivers showing as AVAILABLE |

---

## Interview Q&A

### Q: "What do you cache in a ride-sharing system?"

> **A:** "I cache in three tiers. L1 is the driver's phone -- it buffers 5 GPS points and batch-uploads every 3-5 seconds. L2 is Redis GEO -- it holds all driver locations with 10-second TTL and handles GEORADIUS queries in under 1ms. L3 is PostGIS for durable storage and complex geofence queries. I also cache surge multipliers per GeoHash zone with 30-second TTL. Critically, I do NOT cache ride state or driver availability -- those must be consistent to prevent double-booking. The cache key trick is using GeoHash prefixes so all riders in the same ~5km cell share one cache entry, giving 70%+ hit rates in dense areas."

### Q: "How do you handle the thundering herd at a hot spot like an airport?"

> **A:** "Four techniques layered together. First, cache warming: a background job pre-computes nearby drivers for known hot spots every 3 seconds, so the cache is never cold. Second, staggered TTL: I add 0-2 seconds of random jitter so entries don't all expire simultaneously. Third, probabilistic early refresh (XFetch): as TTL approaches expiration, requests probabilistically trigger background refresh while still returning the cached value. Fourth, request coalescing (singleflight): if 500 requests arrive for the same cache key simultaneously, only one hits the database and the result is shared. Together, these ensure the database never sees more than 1 request per cache miss event."

### Q: "Why not cache everything in Redis and skip the database?"

> **A:** "Because ride assignment requires ACID transactions. When two riders request a ride simultaneously and only one driver is available, I need SELECT FOR UPDATE to serialize the assignment -- Redis doesn't support this. I use Redis for what it's great at: sub-millisecond geospatial queries on frequently-changing data. The database handles what it's great at: consistent state transitions, durable storage, and complex queries. The split is: cache location data (high volume, stale-tolerant) in Redis, keep ride state (low volume, consistency-critical) in PostgreSQL."

### Q: "How do you handle cache invalidation when a driver becomes busy?"

> **A:** "I maintain two separate Redis GEO sets: drivers:available and drivers:busy. When a driver is assigned to a ride, a Redis MULTI/EXEC transaction atomically removes them from drivers:available and adds them to drivers:busy. When the ride completes, the reverse happens. The matching service only queries drivers:available, so a busy driver never appears in search results. This is event-driven -- the ride state change triggers the cache update through an event bus, and the Redis transaction ensures no window where the driver appears in both sets."

---

## Cross-Reference to Other Projects

| Project | Caching Strategy | Parallel to Ride-Sharing |
|---------|-----------------|------------------------|
| 01 - URL Shortener | Cache popular short URLs (read-heavy, rarely changes) | Cache popular routes (read-heavy, slowly changes) |
| 02 - Rate Limiter | Cache rate counters (must be approximate-consistent) | Cache surge multipliers (bounded staleness) |
| 05 - Social Media Feed | Cache user feeds (eventually consistent) | Cache ride history (eventually consistent) |
| 07 - Distributed Cache | THE caching project -- all patterns | Multi-layer caching, TTL strategy, invalidation |
| **08 - Ride Sharing** | **Geospatial caching, split cache/no-cache, hot spots** | **Spatial-aware caching with GeoHash keys** |
