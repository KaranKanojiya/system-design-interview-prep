# Interview Walkthrough -- Ride-Sharing System (Uber/Lyft)

> **Total time: ~35 minutes. The spatial indexing deep dive and matching algorithm are 50% of this interview.**
> This problem tests spatial data structures (QuadTree, GeoHash), real-time systems (GPS streaming), state machines (ride lifecycle), and capacity estimation (millions of location updates).

---

## Phase 1: Clarify Requirements (2-3 min)

### Questions to Ask

- "How many rides per day? 1M? 10M? This determines whether I need a single-region or multi-region design."
- "Do we need real-time tracking? Riders see driver position live on the map?"
- "Surge pricing? Dynamic pricing based on demand/supply?"
- "Pool rides (shared)? Or only single-rider for now?"
- "What's the matching strategy -- nearest driver, or ETA-based considering traffic?"
- "Do we need to handle driver cancellations and timeouts?"

### Clarified Scope

```
In scope:   Ride request, driver matching, real-time tracking, surge pricing,
            ride lifecycle (state machine), fare calculation
Out of scope: Pool rides (mention only), payment processing (Stripe wrapper),
              driver onboarding, maps/routing engine
```

### What This Signals

You understand this is a **real-time, location-heavy system** -- not just CRUD. You're probing for the hard parts: spatial queries, streaming GPS, surge pricing.

**Common follow-up:** "Why does the number of rides/day matter?"

**Answer:** "At 1M rides/day with 200K active drivers sending GPS every 4 seconds, that's 50K location updates per second. This eliminates SQL for location storage -- I need Redis GEO or an in-memory spatial index. It also means my matching service needs to handle ~35 ride requests per second at peak, each requiring a nearest-driver spatial query."

---

## Phase 2: High-Level Architecture (5-7 min)

### What to Say

> "I'll design five core services: a **Location Service** that ingests GPS updates into a spatial index, a **Matching Service** that finds the best driver for a rider, a **Pricing Service** with surge, a **Ride Service** that manages the ride state machine, and a **Notification Service** for real-time push to rider and driver apps."

### Draw This Diagram

```
                  ┌───────────────────┐      ┌───────────────────┐
                  │    Rider App      │      │    Driver App      │
                  │  (request ride,   │      │  (accept ride,     │
                  │   track driver)   │      │   send GPS)        │
                  └────────┬──────────┘      └────────┬───────────┘
                           │                          │
              1. POST /rides/request       2. WebSocket: GPS every 4s
                           │                          │
                  ┌────────▼──────────────────────────▼───────────┐
                  │              API Gateway                       │
                  │   (REST for rides, WebSocket for location)    │
                  └───┬──────────┬──────────┬──────────┬──────────┘
                      │          │          │          │
           3. Ride    │  4. Match │  5. GPS  │  6. Push │
              request │   driver  │   update │   notify │
                      ▼          ▼          ▼          ▼
               ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
               │  Ride    │ │ Matching │ │ Location │ │ Notif.   │
               │  Service │ │ Service  │ │ Service  │ │ Service  │
               │          │ │          │ │          │ │          │
               │ State    │ │ Nearest/ │ │ Ingest   │ │ Push to  │
               │ machine  │ │ ETA-based│ │ GPS,     │ │ rider &  │
               │ ACID txn │ │ K nearest│ │ spatial  │ │ driver   │
               └────┬─────┘ └────┬─────┘ └────┬─────┘ └──────────┘
                    │             │             │
                    ▼             ▼             ▼
               ┌──────────┐ ┌──────────┐ ┌──────────────────────┐
               │  RDS     │ │ Pricing  │ │  Redis GEO           │
               │ PostGIS  │ │ Service  │ │  (driver locations)   │
               │ (rides)  │ │ (surge)  │ │  GEOSEARCH BYRADIUS  │
               │ CP: ACID │ └────┬─────┘ │  AP: stale OK        │
               └──────────┘      │        └──────────────────────┘
                                 ▼
                          ┌──────────────┐
                          │ Surge Cache  │
                          │ Redis k/v    │
                          │ TTL = 30s    │
                          └──────────────┘
```

### Components to Name

| Component | Role | CAP |
|-----------|------|-----|
| Ride Service | State machine, ACID transitions, fare finalization | CP |
| Matching Service | Find K nearest drivers, rank by ETA, send request | Stateless |
| Location Service | Ingest GPS, write to Redis GEO, publish to stream | AP |
| Pricing Service | Base fare + surge multiplier per zone | AP (cached) |
| Notification Service | Push updates to rider/driver via WebSocket/SNS | Best-effort |
| Redis GEO | Spatial index for driver locations, GEOSEARCH | AP |
| RDS PostGIS | Ride state, driver-ride assignment (row-level lock) | CP |

### What This Signals

You separate **CP concerns (ride state)** from **AP concerns (location)**. This is the key architectural insight for ride-sharing.

**Common follow-up:** "Why separate Location Service from Matching Service?"

**Answer:** "Location Service handles 50K writes/sec (GPS firehose). Matching Service handles 35 reads/sec (ride requests). Very different scaling profiles. Location is write-heavy, matching is read-heavy with compute (spatial query + ETA calculation). Separating them lets me scale independently."

---

## Phase 3: Spatial Indexing Deep Dive (8-10 min)

**This is the core of the interview. Spend the most time here.**

### Part A: QuadTree -- Recursive Space Partitioning

> "A QuadTree recursively divides 2D space into four quadrants. Each node either holds points (leaf) or has four children (internal). When a leaf exceeds a threshold (e.g., 4 drivers), it splits into four children."

```
Full city area (root):
┌─────────────────────────────────────┐
│                                     │
│            1000 drivers             │
│        (exceeds threshold=4)        │
│              SPLIT!                 │
│                                     │
└─────────────────────────────────────┘
                  │
                  ▼
┌────────────────┬────────────────────┐
│   NW: 280     │   NE: 350          │
│   (split)     │   (split)          │
│               │                    │
├───────────────┼────────────────────┤
│   SW: 170     │   SE: 200          │
│   (split)     │   (split)          │
│               │                    │
└───────────────┴────────────────────┘
                  │
      (keep splitting until leaf <= threshold)
                  │
                  ▼
Final tree (depth 5-7 in dense areas, depth 2-3 in sparse):

┌────┬────┬────┬────┐
│ 2  │ 3  │ 1  │ 4  │  <-- dense downtown: depth 6, tiny cells
├────┼────┼────┼────┤
│ 1  │ 2  │ 3  │ 2  │
├────┴────┼────┴────┤
│   1     │    0    │  <-- sparse suburbs: depth 3, large cells
├─────────┼─────────┤
│   0     │    1    │
└─────────┴─────────┘
```

### Range Query: Find K Nearest Drivers

```
Rider is at point R. Find 3 nearest drivers within 5km.

Step-by-step:
  1. Start at root, descend to leaf containing R           O(log N)
  2. Collect all drivers in that leaf cell                  O(K)
  3. Check: does 5km radius extend beyond this cell?
     YES --> also search neighboring cells (sibling nodes)
  4. Compute Haversine distance to each candidate driver    O(1) each
  5. Sort by distance (or ETA), return top 3                O(K log K)

Total: O(log N + K) where N = total drivers, K = candidates in range

     ┌────────────────────────────────┐
     │         ┌──────┐              │
     │         │ D2   │              │
     │    ┌────┼──────┼────┐        │
     │    │    │  R   │    │        │
     │    │ D1 │(rider)│ D3 │        │
     │    │    │      │    │  D5    │
     │    └────┼──────┼────┘        │
     │         │  D4  │              │
     │         └──────┘              │
     │     5km radius circle         │
     └────────────────────────────────┘

Result: D2 (0.3km), D1 (0.8km), D4 (1.1km)
        D3 (1.4km), D5 (4.8km) -- only return top 3
```

### Why Not Just SQL?

```
SQL approach:
  SELECT * FROM drivers
  WHERE status = 'AVAILABLE'
  ORDER BY ST_Distance(location, ST_Point(-73.98, 40.74))
  LIMIT 3;

Problems at scale:
  - Full table scan of 200K drivers                    O(N)
  - PostGIS spatial index helps, but still disk I/O
  - At 35 requests/sec, this hammers the DB
  - Adding location writes (50K/sec) kills performance

QuadTree approach:
  - In-memory, O(log N) traversal
  - 200K drivers, depth ~8, check ~50 candidates       sub-ms
  - No disk I/O, no DB connection overhead

Redis GEO approach (production):
  - GEOSEARCH FROMLONLAT lng lat BYRADIUS 5 km COUNT 10 ASC
  - Sorted set with geohash scores, O(log N + K)       sub-ms
  - Handles 50K writes/sec natively
```

**Say:** "In an interview, I'd implement the QuadTree to show I understand spatial data structures. In production, I'd use Redis GEO -- it's a sorted set with geohash scores internally, giving O(log N + K) for both inserts and range queries."

### Part B: GeoHash -- String-Based Spatial Encoding

> "GeoHash encodes latitude/longitude into a base32 string. Longer string = higher precision. Two points that share a prefix are in the same geographic region."

```
Encoding process (simplified):

Latitude  40.7484:  range [-90, 90]
  Step 1: 40.7484 > 0?    YES → bit 1    range [0, 90]
  Step 2: 40.7484 > 45?   NO  → bit 0    range [0, 45]
  Step 3: 40.7484 > 22.5? YES → bit 1    range [22.5, 45]
  Step 4: 40.7484 > 33.75? YES → bit 1   range [33.75, 45]
  ... (interleave lat and lng bits, encode as base32)

Result: "dr5ru7" (6 characters)

Precision table:
  Length  |  Cell Size      |  Use Case
  --------|----------------|------------------
  1       |  5000 x 5000 km | continent
  4       |  39 x 20 km    | city
  5       |  5 x 5 km      | neighborhood
  6       |  1.2 x 0.6 km  | block (SURGE ZONES)
  7       |  150 x 150 m   | street
  8       |  38 x 19 m     | building
  12      |  3.7 x 1.9 cm  | centimeter precision

Proximity by prefix:
  "dr5ru7"  and "dr5ru6"  → same block (shared 5-char prefix)
  "dr5ru7"  and "dr5rk9"  → same neighborhood (shared 4-char prefix)
  "dr5ru7"  and "dr7abc"  → same city (shared 2-char prefix)
```

### GeoHash Edge Case (Mention Proactively)

```
PROBLEM: Two points on opposite sides of a cell boundary
         have DIFFERENT geohash prefixes despite being 1 meter apart.

    ┌──────────┬──────────┐
    │          │          │
    │  dr5ru7  │ X  dr5ru8│    X and Y are 1 meter apart
    │          │Y         │    but have different 6-char prefixes!
    │          │          │
    └──────────┴──────────┘

SOLUTION: When searching, also query the 8 neighboring cells:
    ┌──────┬──────┬──────┐
    │      │      │      │
    │ NW   │  N   │  NE  │
    ├──────┼──────┼──────┤
    │  W   │ HERE │  E   │   Query 9 cells total (HERE + 8 neighbors)
    ├──────┼──────┼──────┤
    │ SW   │  S   │  SE  │
    └──────┴──────┴──────┘
```

**Common follow-up:** "QuadTree vs GeoHash -- when to use which?"

**Answer:** "QuadTree is better for in-memory, dynamic data -- drivers moving constantly, variable density across the city. GeoHash is better for database indexing -- it's a string, so you get B-tree index support, prefix range queries, and easy persistence. In practice: QuadTree (or Redis GEO, which uses geohash internally) for real-time driver lookup, GeoHash for trip history queries like 'all rides starting in this zone.'"

---

## Phase 4: Matching and Surge Pricing (5-7 min)

### Driver Matching Algorithm

> "When a rider requests a ride, the matching service finds K nearest available drivers, ranks them by ETA (not just distance), and sends the request to the best candidate with a 15-second timeout."

```
Matching Flow (numbered):

  1. Rider sends ride request (pickup: lat/lng, dropoff: lat/lng)
                │
  2. Matching Service queries Redis GEO:
     GEOSEARCH drivers FROMLONLAT lng lat BYRADIUS 5 km ASC COUNT 10
                │
  3. Filter: only AVAILABLE drivers (check status in Redis hash)
     10 nearby → 6 available
                │
  4. For each available driver, estimate ETA:
     ETA = haversine_distance / average_speed_in_area
     (In production: call routing API for actual road-network ETA)
                │
  5. Rank by ETA (ascending):
     Driver D-017: ETA 2 min (0.8 km, no traffic)
     Driver D-033: ETA 3 min (0.4 km, congested road)
     Driver D-008: ETA 5 min (1.2 km, highway access)
                │
  6. Send ride request to D-017 (best ETA)
     Start 15-second timeout timer
                │
  7a. D-017 accepts within 15s → MATCHED → proceed
  7b. D-017 times out or declines → CASCADE to D-033
     Start new 15-second timeout
                │
  8. If all K drivers decline → notify rider "No drivers available"
     Suggest: try again in 2 minutes, or try a different ride type
```

### Why ETA, Not Just Nearest?

```
Scenario: rider at position R

                    Highway (fast)
    D1 ═══════════════════════════════ R
    (3 km away, ETA: 3 min via highway)

              Local roads (slow, traffic)
    D2 ─── traffic ─── construction ─── R
    (0.5 km away, ETA: 8 min due to congestion)

Nearest: D2 (0.5 km)
Best ETA: D1 (3 min)

Uber learned this the hard way -- nearest driver often meant
longer wait times in dense urban areas with traffic.
```

### Surge Pricing Formula

> "Surge pricing balances demand and supply. Each zone has a demand count (ride requests in last 5 min) and supply count (available drivers). The ratio determines the multiplier."

```
Surge Calculation per Zone:

  ratio = demand / supply

  Tier thresholds:
    ratio < 1.2  → multiplier = 1.0x  (no surge)
    ratio 1.2-1.5 → multiplier = 1.25x (mild)
    ratio 1.5-2.0 → multiplier = 1.5x  (moderate)
    ratio 2.0-3.0 → multiplier = 2.0x  (high)
    ratio > 3.0   → multiplier = 3.0x  (cap -- never exceed 3x)

  Example:
    Zone "Manhattan-Midtown" (geohash: dr5ru):
      Demand: 150 requests in last 5 min
      Supply: 60 available drivers
      Ratio: 150/60 = 2.5
      Multiplier: 2.0x (HIGH tier)

  Fare with surge:
    base_fare = $2.50 + (distance_km * $1.75) + (time_min * $0.35)
    surge_fare = base_fare * surge_multiplier
    total = surge_fare + booking_fee ($1.50)
```

### Surge Architecture

```
Every 30 seconds:

  1. Count ride requests per zone (from Ride Service events)
     ┌────────────────────────────────┐
     │  Kinesis stream: ride-requests │
     │  Consumer: aggregate by zone   │
     └──────────────┬─────────────────┘
                    │
  2. Count available drivers per zone (from Redis GEO)
     ┌────────────────────────────────┐
     │  Redis GEO: scan each zone,   │
     │  count drivers with status=    │
     │  AVAILABLE                     │
     └──────────────┬─────────────────┘
                    │
  3. Calculate ratio, apply tier thresholds
  4. Write surge multiplier to Redis:
     SET surge:dr5ru 2.0 EX 30
     (key = zone geohash, value = multiplier, TTL = 30 seconds)

  5. When rider requests ride:
     zone = geohash(pickup_location, precision=5)
     surge = GET surge:{zone}   // O(1) lookup
     fare = base_fare * surge
```

**Common follow-up:** "How do you prevent drivers from gaming surge?"

**Answer:** "Two problems. First, drivers turning off the app in a surge zone to increase demand -- we detect this by tracking driver activity patterns and penalizing consistent opt-outs during surge. Second, drivers repositioning to surge zones -- this is actually desired behavior; surge is designed to incentivize drivers to go where demand is. The cap at 3x prevents extreme pricing that harms riders."

---

## Phase 5: Scaling and Edge Cases (5-8 min)

### Millions of GPS Updates Per Second

```
Problem: 200K drivers, GPS every 4 seconds = 50K updates/sec
         Peak (3x): 150K updates/sec

Architecture:

  Driver App
      │
  1. Batch 3-5 GPS points per message (reduce message count 3-5x)
      │
  2. WebSocket to API Gateway (persistent connection)
      │
  3. Location Service (ECS, 10 instances)
      │
  ┌───┴─────────────────────────────────────┐
  │                                          │
  4a. Redis GEO: GEOADD drivers lng lat id   │  4b. Kinesis: publish for
      (latest position only, overwrite)      │      analytics, ETA, history
      50K writes/sec across 3 Redis shards   │      50 shards, partition by
      ~17K/sec per shard (well within Redis  │      driver_id (ordering)
      capacity of 100K+ ops/sec per shard)   │
  └──────────────────────────────────────────┘

Key insight: Redis GEO only stores LATEST position per driver.
  GEOADD overwrites the previous position for the same member.
  No unbounded growth -- always exactly 200K entries max.
```

### Driver Timeout and Cascade

```
Problem: Driver doesn't respond to ride request

Timeline:
  T+0s:  Send request to Driver A (best ETA)
  T+15s: Driver A timeout → mark as UNRESPONSIVE
  T+15s: Send request to Driver B (second best)
  T+30s: Driver B timeout → mark as UNRESPONSIVE
  T+30s: Send request to Driver C (third best)
  T+35s: Driver C accepts! → MATCHED

Edge cases:
  - Driver A responds at T+16s (after timeout): REJECT (already cascaded)
  - All K drivers timeout: notify rider "No drivers available"
  - Driver accepts then cancels within 2 min: penalize, re-match

State transitions during cascade:
  Ride: REQUESTED (stays REQUESTED until someone accepts)
  Driver A: AVAILABLE → OFFERED → UNRESPONSIVE → AVAILABLE
  Driver B: AVAILABLE → OFFERED → UNRESPONSIVE → AVAILABLE
  Driver C: AVAILABLE → OFFERED → MATCHED (locked to this ride)
```

### Double-Booking Prevention

```
Problem: Two riders request at the same time, both match to Driver D-017

WRONG (race condition):
  Thread 1: SELECT status FROM drivers WHERE id = 'D-017'  → AVAILABLE
  Thread 2: SELECT status FROM drivers WHERE id = 'D-017'  → AVAILABLE
  Thread 1: UPDATE drivers SET status = 'MATCHED', ride_id = 'R-001' WHERE id = 'D-017'
  Thread 2: UPDATE drivers SET status = 'MATCHED', ride_id = 'R-002' WHERE id = 'D-017'
  ← D-017 assigned to R-002, R-001 thinks they have a driver but don't!

CORRECT (optimistic locking / compare-and-swap):
  UPDATE drivers
  SET status = 'MATCHED', ride_id = 'R-001', version = version + 1
  WHERE id = 'D-017'
    AND status = 'AVAILABLE'
    AND version = 42;

  If rows_affected = 1 → success, driver locked to this ride
  If rows_affected = 0 → someone else got them, try next driver

ALSO CORRECT (Redis atomic):
  -- Lua script for atomic check-and-set
  if redis.call('GET', 'driver:D-017:status') == 'AVAILABLE' then
      redis.call('SET', 'driver:D-017:status', 'MATCHED')
      redis.call('SET', 'driver:D-017:ride', 'R-001')
      return 1
  else
      return 0
  end
```

**Say:** "This is why ride state is CP, not AP. Double-booking a driver means two riders waiting for the same car. I use optimistic locking -- a single atomic UPDATE with a WHERE clause that checks both status and version. If the row isn't updated, someone else matched first, and I cascade to the next driver."

### Handling Driver Going Offline Mid-Ride

```
Problem: Driver's app crashes or loses network during a ride

Detection:
  1. Location Service expects GPS every 4 seconds
  2. If no update for 30 seconds → mark driver as STALE
  3. If no update for 2 minutes → mark driver as OFFLINE

  Implementation: Redis TTL per driver
    SET driver:D-017:heartbeat 1 EX 30
    (Location Service refreshes this with every GPS update)
    (Background job checks for expired heartbeat keys)

Recovery:
  - Driver reconnects within 2 min → resume ride (last known location)
  - Driver offline > 5 min → notify rider, offer to cancel or wait
  - Driver offline > 10 min → auto-cancel, no charge, rematching offered
  - Trip fare calculated from last known GPS trace (partial trip)
```

---

## Phase 6: Tradeoffs Discussion (3-5 min)

### QuadTree vs GeoHash vs H3

| Aspect | QuadTree | GeoHash | H3 (Uber) |
|--------|----------|---------|-----------|
| Shape | Rectangles (variable size) | Rectangles (fixed per precision) | Hexagons (uniform area) |
| Precision | Variable depth | Prefix length (1-12 chars) | Resolution level (0-15) |
| Neighbors | Complex (8 neighbors, different tree levels) | 8 neighbors (prefix computation) | 6 neighbors (simple, equidistant) |
| Area uniformity | Non-uniform at same depth | Non-uniform (distortion at poles) | Uniform within resolution |
| Dynamic updates | Excellent (in-memory, O(log N)) | Good (DB index update) | Good (precomputed cells) |
| Best for | In-memory spatial queries | Database spatial indexing | Surge zones, dispatch |
| Interview | Implement this one | Explain for DB layer | Mention "what Uber uses" |

**Say:** "I'd implement QuadTree for the interview to show spatial data structure knowledge. In production, I'd use Redis GEO (which uses geohash internally) for driver lookup, and H3 for surge pricing zones because hexagons have uniform area and equidistant neighbors -- no edge distortion like rectangles."

### Nearest Driver vs ETA-Based Matching

| Aspect | Nearest (Distance) | ETA-Based |
|--------|-------------------|-----------|
| Complexity | O(K log N) QuadTree query | O(K log N) + K routing API calls |
| Accuracy | Poor in cities (ignores traffic, one-way streets) | High (real road network + traffic) |
| Latency | Sub-ms | 100-500ms (depends on routing API) |
| Rider satisfaction | Lower (longer actual wait) | Higher (shorter actual wait) |
| When to use | MVP, rural areas, low traffic | Production, urban areas |

**Say:** "Start with nearest distance for the MVP -- it's simpler and works well in low-traffic areas. Add ETA-based matching as an optimization once you have a routing API. In the interview, I'd implement nearest and explain how I'd extend to ETA-based."

### CP vs AP Per Component

| Component | CP or AP | Why |
|-----------|----------|-----|
| Ride state (assignment) | **CP** | Double-booking a driver is catastrophic |
| Driver location | **AP** | 3-4 second staleness is fine for matching |
| Surge pricing | **AP** | Stale surge (30s) is acceptable |
| Trip history | **AP** | Eventually consistent, not real-time critical |
| Payment | **CP** | Cannot double-charge or miss a charge |
| User profiles | **AP** | Stale name/photo is harmless |

**Say:** "Not everything in ride-sharing needs the same consistency model. I use CP only where it matters -- ride assignment and payments. Everything else is AP. This lets me scale the location service and surge cache independently without the overhead of distributed transactions."

---

## Red Flags (What NOT to Do)

- Saying "just use a database query to find nearby drivers" without explaining spatial indexing
- Not knowing QuadTree or GeoHash internals (at least one in depth)
- Ignoring the scale of GPS updates (50K/sec is not trivial)
- Making everything CP (kills performance) or everything AP (double-booking)
- Not addressing driver timeout and cascade in matching
- Forgetting state machine transitions (allowing REQUESTED -> COMPLETED)
- Using Euclidean distance for lat/lng (wrong -- longitude shrinks toward poles)

## Green Flags (What Interviewers Want to Hear)

- Draw QuadTree subdivision and explain O(log N) range query
- Explain GeoHash encoding and the boundary edge case (query 9 cells)
- Mention Haversine formula (not Euclidean) for distance
- Separate CP (ride state) from AP (location) with clear reasoning
- Proactively bring up double-booking prevention (optimistic locking)
- Mention ETA-based matching as an improvement over nearest distance
- Explain surge pricing with concrete formula and zone architecture
- Reference H3 hexagonal grid as "what Uber actually uses"
- Calculate capacity: 200K drivers * 1 update/4s = 50K writes/sec

---

## 30-Second Elevator Pitch

> "For a ride-sharing system, I'd use a **QuadTree** (or Redis GEO in production) for O(log N) nearest-driver queries from 200K active drivers. Drivers send GPS every 4 seconds over WebSocket -- 50K updates/sec written to Redis GEO. When a rider requests, the matching service queries Redis for K nearest drivers, ranks by ETA, and sends the request with a 15-second timeout, cascading to the next driver on timeout. Ride state is CP -- I use optimistic locking to prevent double-booking. Location is AP -- 3-4 second staleness is fine. Surge pricing uses demand/supply ratio per zone, recalculated every 30 seconds, capped at 3x."

**Time: Under 30 seconds. Covers: spatial index, GPS streaming, matching, timeout, CAP split, surge.**

---

## Phase-by-Phase Timing Cheat Sheet

```
Phase 1:  Clarify Requirements         2-3 min   (rides/day, tracking, surge, pool?)
Phase 2:  High-Level Architecture       5-7 min   (5 services, CP vs AP split)
Phase 3:  Spatial Indexing Deep Dive     8-10 min  (QuadTree, GeoHash, range query, why not SQL)
Phase 4:  Matching & Surge              5-7 min   (ETA-based, timeout cascade, surge formula)
Phase 5:  Scaling & Edge Cases          5-8 min   (50K GPS/sec, double-booking, driver offline)
Phase 6:  Tradeoffs Discussion          3-5 min   (QuadTree vs GeoHash vs H3, CP vs AP)
────────────────────────────────────────────────────
Total:                                  ~35 min
```

If short on time, shorten Phase 4 (matching) and Phase 6 (tradeoffs). Never skip Phase 3 (spatial indexing) -- that's the core of the interview.
