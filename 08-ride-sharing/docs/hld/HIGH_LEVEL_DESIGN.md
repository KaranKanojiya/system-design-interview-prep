# High-Level Design: Ride-Sharing Service (Uber/Lyft)

> Interview-optimized system design document.
> Target: 30-45 minute system design discussion.

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
10. [Spatial Indexing Deep Dive](#10-spatial-indexing-deep-dive)
11. [Driver Matching Algorithm](#11-driver-matching-algorithm)
12. [Surge Pricing](#12-surge-pricing)
13. [Concurrency](#13-concurrency)
14. [Scaling](#14-scaling)
15. [Database Choice](#15-database-choice)
16. [CAP Theorem](#16-cap-theorem)
17. [Cloud Services](#17-cloud-services)
18. [Tradeoffs Summary](#18-tradeoffs-summary)
19. [Interview Talking Points](#19-interview-talking-points)

---

## 1. Problem Statement

Design a **Ride-Sharing Service** (like Uber or Lyft) that connects riders who need transportation with nearby drivers in real time. The system must track millions of driver locations updating every few seconds, match riders with optimal drivers, calculate dynamic pricing based on supply and demand, and manage the full ride lifecycle from request to payment.

**Why is it needed?**

- Traditional taxi dispatch relies on phone calls, manual assignment, and fixed pricing -- none of which scale.
- Real-time GPS tracking enables matching riders with the nearest available driver in seconds.
- Dynamic (surge) pricing balances supply and demand, incentivizing more drivers to come online when demand spikes.
- A platform-based approach creates a two-sided marketplace that benefits both riders (convenience, competitive pricing) and drivers (flexible work, steady demand).
- At scale (20M+ daily rides), the system must ingest billions of GPS pings, perform spatial queries across millions of drivers, and handle concurrent ride requests without conflicts.

**Core Workflow:**

```
Rider opens app, enters destination

(1) Rider App --POST /rides/request--> API Gateway
(2) API Gateway --> Ride Service: create ride (REQUESTED)
(3) Ride Service --> Pricing Service: calculate fare estimate
(4) Pricing Service --> Surge Engine: get current surge multiplier for zone
(5) Ride Service --> Matching Service: find nearest available drivers
(6) Matching Service --> Location Service: query drivers within radius
(7) Location Service --> Spatial Index (QuadTree/GeoHash): range query
(8) Matching Service --> selected Driver App: ride offer via push notification
(9) Driver App --POST /rides/{id}/accept--> Ride Service: MATCHED
(10) Rider App <--push notification-- "Driver en route, ETA 4 min"
(11) Driver picks up rider --> Ride Service: IN_PROGRESS
(12) Driver completes ride --> Ride Service: COMPLETED
(13) Ride Service --> Payment Service: charge rider, pay driver
```

### Why This Is Asked in Interviews

This is a **tier-1 system design** interview question, rated **Hard**. It appears at every major tech company because it tests nearly every distributed systems concept simultaneously:

| Skill Tested                    | What Interviewers Look For                                       |
|---------------------------------|------------------------------------------------------------------|
| **Real-Time Systems**           | GPS ingestion at scale, WebSocket connections, push notifications|
| **Spatial Data Structures**     | QuadTree, GeoHash, H3 -- how to index and query geo data        |
| **Matching Algorithms**         | Nearest neighbor search, ETA-based ranking, driver cascading     |
| **Dynamic Pricing**             | Supply/demand modeling, zone-based surge multipliers             |
| **State Machines**              | Ride lifecycle: REQUESTED -> MATCHED -> EN_ROUTE -> COMPLETED    |
| **Concurrency**                 | Two riders requesting the same driver simultaneously             |
| **Scalability**                 | Millions of GPS updates/sec, horizontal scaling of each service  |
| **CAP Tradeoffs**               | Strong consistency for ride state vs eventual for location data  |
| **Two-Sided Marketplaces**      | Balancing rider experience with driver utilization               |
| **Event-Driven Architecture**   | Kafka streams for location events, ride state changes            |

> **Interview tip**: This question is a goldmine because you can go deep on spatial indexing (QuadTree vs GeoHash vs H3), matching algorithms, surge pricing economics, or scaling real-time location tracking. The interviewer will steer you toward their area of interest -- be ready to pivot. Start with the high-level flow, then let them pick the deep-dive.

---

## 2. Scope

### In Scope

| Feature                       | Description                                                         |
|-------------------------------|---------------------------------------------------------------------|
| Ride Request & Matching       | Rider requests ride, system matches with nearest available driver   |
| Real-Time Location Tracking   | Drivers send GPS updates every 3-5 seconds                         |
| Ride Lifecycle Management     | Full state machine: REQUESTED -> MATCHED -> EN_ROUTE -> IN_PROGRESS -> COMPLETED |
| Fare Calculation              | Base fare + distance + time + surge multiplier                     |
| Surge Pricing                 | Dynamic pricing based on supply/demand ratio per geographic zone   |
| Driver Acceptance Flow        | Offer ride to driver, handle accept/reject/timeout, cascade        |
| Payment Processing            | Calculate fare, charge rider, pay driver (minus platform fee)      |
| Push Notifications            | Real-time updates to rider and driver throughout ride lifecycle    |
| ETA Calculation               | Estimated time of arrival for driver pickup and trip duration      |
| Split Fare                    | Allow multiple riders to split a ride's cost                       |

### Out of Scope

| Feature                       | Reason                                                              |
|-------------------------------|---------------------------------------------------------------------|
| Ride Pooling (UberPool/Lyft Shared) | Significantly more complex matching, separate deep-dive       |
| Driver Onboarding / KYC       | Operational concern, not core system design                        |
| In-App Chat / Calling         | Covered in separate chat system design (Project 04)                |
| Route Navigation              | Delegated to third-party map providers (Google Maps, Mapbox)       |
| Driver Rating / Feedback      | CRUD feature, not architecturally interesting                      |
| Multi-Modal (bikes, scooters) | Extends the model but does not change core architecture            |
| Scheduled Rides               | Extension of the REQUESTED state, not core real-time matching      |
| Insurance / Safety Features   | Business logic layer, not system architecture                      |

---

## 3. Assumptions

### Platform Scale

| Parameter                     | Value                          |
|-------------------------------|--------------------------------|
| Daily rides                   | 20 million                     |
| Registered users (riders)     | 100 million                    |
| Active drivers                | 5 million                      |
| Concurrent online drivers     | 500,000 (peak)                 |
| Concurrent ride requests      | 50,000/sec (peak)              |
| Average ride duration         | 15 minutes                     |
| Geographic coverage           | 500+ cities worldwide          |

### Location Data

| Parameter                     | Value                          |
|-------------------------------|--------------------------------|
| GPS update interval           | Every 3-5 seconds per driver   |
| GPS updates/sec (peak)        | 500K drivers * (1/3 sec) = ~167K updates/sec |
| Location record size          | ~200 bytes (driver_id, lat, lng, timestamp, heading, speed) |
| Location data throughput      | 167K * 200 bytes = ~33 MB/sec  |
| Location data daily           | ~2.8 TB/day (raw, before compaction) |
| Location retention (hot)      | Last known location only (in Redis) |
| Location retention (cold)     | 30 days in time-series store for analytics |

### Ride Data

| Parameter                     | Value                          |
|-------------------------------|--------------------------------|
| Rides per second              | 20M / 86400 = ~230 rides/sec (avg), ~1000/sec (peak) |
| Average ride record size      | ~2 KB (with all state transitions) |
| Ride data daily               | 20M * 2 KB = ~40 GB/day       |

### Back-of-the-Envelope: Matching Latency Budget

```
Total rider wait time target:    < 30 seconds (request to driver matched)
Breakdown:
  (1) API Gateway + auth:         50 ms
  (2) Pricing calculation:       100 ms
  (3) Spatial query (find drivers): 50 ms
  (4) ETA calculation (top 5):   200 ms
  (5) Send offer to driver:      100 ms
  (6) Driver decision timeout: 15,000 ms (15 sec)
  (7) If rejected, cascade:    15,000 ms (second driver)
  ----------------------------------------
  Best case (first accept):    ~15.5 sec
  Worst case (2 rejections):   ~30.5 sec
  
GPS Updates Bandwidth:
  500K drivers * 1 update/3 sec * 200 bytes = 33 MB/sec inbound
  With WebSocket overhead: ~50 MB/sec
  Single server at 10K connections: need 50 WebSocket servers minimum
```

---

## 4. Functional Requirements

### FR-1: Request a Ride
Rider specifies pickup location and destination. System returns a fare estimate (including any surge multiplier) and initiates driver matching.

### FR-2: Match Rider with Driver
System finds the K nearest available drivers, ranks them by ETA (not raw distance), and sends a ride offer to the best match.

### FR-3: Accept / Reject Ride
Driver can accept or reject a ride offer within a timeout window (15 seconds). On rejection or timeout, the system cascades to the next best driver.

### FR-4: Track Driver Location
Drivers continuously send GPS coordinates (every 3-5 seconds). The system maintains a real-time spatial index of all online drivers.

### FR-5: Manage Ride Lifecycle
Track ride through states: REQUESTED -> MATCHED -> DRIVER_EN_ROUTE -> ARRIVED_AT_PICKUP -> IN_PROGRESS -> COMPLETED -> CANCELLED. Each transition is timestamped and auditable.

### FR-6: Calculate Fare
Compute fare based on: base_fare + (distance_miles * per_mile_rate) + (duration_minutes * per_minute_rate), multiplied by the surge multiplier.

### FR-7: Surge Pricing
Dynamically adjust pricing based on the ratio of ride requests to available drivers within each geographic zone. Recalculate at regular intervals (every 1-2 minutes).

### FR-8: Process Payment
Charge the rider upon ride completion. Support credit card, debit, and wallet. Allow split fare among multiple riders.

### FR-9: Push Notifications
Notify rider when: driver matched, driver arriving, driver arrived, ride started, ride completed, payment processed. Notify driver when: new ride offer, rider cancelled, navigation updates.

### FR-10: Cancel Ride
Either rider or driver can cancel. Cancellation policy depends on ride state (free before match, fee after driver en route).

---

## 5. Non-Functional Requirements

| Requirement            | Target                          | Rationale                                                |
|------------------------|---------------------------------|----------------------------------------------------------|
| **Matching Latency**   | < 30 sec (request to match)     | Rider patience threshold; longer = lost customers        |
| **Location Freshness** | < 5 sec staleness               | Stale driver positions lead to bad matches and wrong ETAs|
| **Ride State Consistency** | Strong (linearizable)       | Two riders must never be matched with same driver        |
| **Availability**       | 99.99% (52 min/year)            | Riders stranded if system is down; safety concern        |
| **GPS Throughput**     | 200K updates/sec sustained      | 500K online drivers, each pinging every 3 seconds        |
| **Fare Accuracy**      | Exact to the cent               | Financial transactions must be precise                   |
| **Notification Latency** | < 2 sec                       | Real-time experience depends on instant push updates     |
| **Horizontal Scalability** | Linear with driver/rider count | Must scale to new cities without re-architecture       |
| **Data Durability**    | Zero ride data loss              | Financial and legal audit requirements                   |
| **Geo Query Latency**  | < 50 ms for range query          | Fast matching depends on fast spatial index lookups      |

---

## 6. API Design

### 6.1 Request a Ride

```
POST /api/v1/rides/request
Authorization: Bearer <rider_token>
Content-Type: application/json
```

**Request:**

```json
{
  "rider_id": "rider_abc123",
  "pickup": {
    "latitude": 37.7749,
    "longitude": -122.4194,
    "address": "123 Market St, San Francisco"
  },
  "dropoff": {
    "latitude": 37.3382,
    "longitude": -121.8863,
    "address": "456 Santa Clara St, San Jose"
  },
  "ride_type": "STANDARD",
  "payment_method_id": "pm_visa_4242"
}
```

**Response (201 Created):**

```json
{
  "ride_id": "ride_xyz789",
  "status": "REQUESTED",
  "fare_estimate": {
    "min_cents": 4500,
    "max_cents": 5200,
    "currency": "USD",
    "surge_multiplier": 1.5,
    "breakdown": {
      "base_fare_cents": 250,
      "distance_cents": 2800,
      "time_cents": 950,
      "surge_extra_cents": 1200,
      "booking_fee_cents": 200
    }
  },
  "estimated_pickup_eta_seconds": 240,
  "created_at": "2026-04-26T14:30:00Z"
}
```

### 6.2 Accept a Ride (Driver)

```
POST /api/v1/rides/{ride_id}/accept
Authorization: Bearer <driver_token>
Content-Type: application/json
```

**Request:**

```json
{
  "driver_id": "driver_def456",
  "current_location": {
    "latitude": 37.7751,
    "longitude": -122.4180
  }
}
```

**Response (200 OK):**

```json
{
  "ride_id": "ride_xyz789",
  "status": "MATCHED",
  "rider": {
    "rider_id": "rider_abc123",
    "name": "Karan",
    "rating": 4.85,
    "pickup": {
      "latitude": 37.7749,
      "longitude": -122.4194,
      "address": "123 Market St, San Francisco"
    }
  },
  "navigation_url": "https://maps.example.com/nav?to=37.7749,-122.4194",
  "accepted_at": "2026-04-26T14:30:15Z"
}
```

**Response (409 Conflict -- ride already matched):**

```json
{
  "error": "RIDE_ALREADY_MATCHED",
  "message": "This ride has already been accepted by another driver.",
  "ride_id": "ride_xyz789"
}
```

### 6.3 Update Driver Location

```
PUT /api/v1/drivers/{driver_id}/location
Authorization: Bearer <driver_token>
Content-Type: application/json
```

**Request:**

```json
{
  "driver_id": "driver_def456",
  "latitude": 37.7752,
  "longitude": -122.4178,
  "heading": 45.0,
  "speed_mph": 25.5,
  "timestamp": "2026-04-26T14:30:18Z"
}
```

**Response (200 OK):**

```json
{
  "status": "LOCATION_UPDATED",
  "driver_id": "driver_def456",
  "server_timestamp": "2026-04-26T14:30:18.123Z"
}
```

> **Note:** In production, location updates use WebSocket or gRPC streaming, not REST. The REST endpoint is shown for interview clarity. The WebSocket version sends binary-encoded lat/lng/timestamp frames at 33% of the REST payload size.

### 6.4 Complete a Ride

```
POST /api/v1/rides/{ride_id}/complete
Authorization: Bearer <driver_token>
Content-Type: application/json
```

**Request:**

```json
{
  "driver_id": "driver_def456",
  "final_location": {
    "latitude": 37.3382,
    "longitude": -121.8863
  },
  "odometer_miles": 42.3
}
```

**Response (200 OK):**

```json
{
  "ride_id": "ride_xyz789",
  "status": "COMPLETED",
  "fare": {
    "total_cents": 4850,
    "currency": "USD",
    "breakdown": {
      "base_fare_cents": 250,
      "distance_cents": 2800,
      "time_cents": 950,
      "surge_extra_cents": 650,
      "booking_fee_cents": 200
    },
    "surge_multiplier": 1.5,
    "distance_miles": 42.3,
    "duration_minutes": 48
  },
  "payment_status": "CHARGED",
  "completed_at": "2026-04-26T15:18:00Z"
}
```

### 6.5 Query Surge Pricing

```
GET /api/v1/surge?lat=37.7749&lng=-122.4194
```

**Response (200 OK):**

```json
{
  "zone_id": "zone_sf_downtown_001",
  "surge_multiplier": 1.5,
  "tier": "ELEVATED",
  "supply_count": 45,
  "demand_count": 120,
  "supply_demand_ratio": 0.375,
  "updated_at": "2026-04-26T14:29:00Z",
  "valid_until": "2026-04-26T14:31:00Z"
}
```

### 6.6 Cancel a Ride

```
POST /api/v1/rides/{ride_id}/cancel
Authorization: Bearer <token>
Content-Type: application/json
```

**Request:**

```json
{
  "cancelled_by": "RIDER",
  "reason": "Changed plans"
}
```

**Response (200 OK):**

```json
{
  "ride_id": "ride_xyz789",
  "status": "CANCELLED",
  "cancellation_fee_cents": 500,
  "cancelled_by": "RIDER",
  "cancelled_at": "2026-04-26T14:31:00Z"
}
```

---

## 7. Data Model

### 7.1 Rider

```
Table: riders
+-------------------+---------------+----------------------------------------+
| Column            | Type          | Notes                                  |
+-------------------+---------------+----------------------------------------+
| rider_id          | VARCHAR(36)   | PK, UUID                               |
| name              | VARCHAR(100)  | Display name                           |
| email             | VARCHAR(255)  | Unique, indexed                        |
| phone             | VARCHAR(20)   | Unique, indexed                        |
| rating            | DECIMAL(3,2)  | 1.00 - 5.00, default 5.00             |
| total_rides       | INT           | Lifetime ride count                    |
| default_payment_id| VARCHAR(36)   | FK to payment_methods                  |
| status            | ENUM          | ACTIVE, SUSPENDED, BANNED              |
| created_at        | TIMESTAMP     | Registration time                      |
| updated_at        | TIMESTAMP     | Last profile update                    |
+-------------------+---------------+----------------------------------------+
Index: (email), (phone), (status)
```

### 7.2 Driver

```
Table: drivers
+-------------------+---------------+----------------------------------------+
| Column            | Type          | Notes                                  |
+-------------------+---------------+----------------------------------------+
| driver_id         | VARCHAR(36)   | PK, UUID                               |
| name              | VARCHAR(100)  | Display name                           |
| email             | VARCHAR(255)  | Unique, indexed                        |
| phone             | VARCHAR(20)   | Unique, indexed                        |
| license_number    | VARCHAR(50)   | Driving license                        |
| vehicle_id        | VARCHAR(36)   | FK to vehicles                         |
| rating            | DECIMAL(3,2)  | 1.00 - 5.00, default 5.00             |
| total_rides       | INT           | Lifetime completed rides               |
| status            | ENUM          | OFFLINE, AVAILABLE, ON_RIDE, SUSPENDED |
| current_lat       | DOUBLE        | Denormalized for quick reads           |
| current_lng       | DOUBLE        | Denormalized for quick reads           |
| last_location_at  | TIMESTAMP     | Last GPS ping time                     |
| created_at        | TIMESTAMP     | Registration time                      |
+-------------------+---------------+----------------------------------------+
Index: (status), (status, current_lat, current_lng)
Spatial Index: on (current_lat, current_lng) -- PostGIS GIST
```

### 7.3 Ride

```
Table: rides
+---------------------+---------------+--------------------------------------+
| Column              | Type          | Notes                                |
+---------------------+---------------+--------------------------------------+
| ride_id             | VARCHAR(36)   | PK, UUID                             |
| rider_id            | VARCHAR(36)   | FK to riders                         |
| driver_id           | VARCHAR(36)   | FK to drivers (NULL until matched)   |
| status              | ENUM          | REQUESTED, MATCHED, DRIVER_EN_ROUTE, |
|                     |               | ARRIVED, IN_PROGRESS, COMPLETED,     |
|                     |               | CANCELLED                            |
| pickup_lat          | DOUBLE        | Pickup latitude                      |
| pickup_lng          | DOUBLE        | Pickup longitude                     |
| pickup_address      | VARCHAR(500)  | Human-readable                       |
| dropoff_lat         | DOUBLE        | Destination latitude                 |
| dropoff_lng         | DOUBLE        | Destination longitude                |
| dropoff_address     | VARCHAR(500)  | Human-readable                       |
| ride_type           | ENUM          | STANDARD, PREMIUM, XL                |
| fare_cents          | INT           | Final fare in cents                  |
| surge_multiplier    | DECIMAL(3,2)  | 1.00 - 5.00                         |
| distance_miles      | DECIMAL(8,2)  | Actual distance traveled             |
| duration_minutes    | INT           | Actual ride duration                 |
| estimated_fare_cents| INT           | Fare estimate at request time        |
| payment_method_id   | VARCHAR(36)   | FK to payment_methods                |
| payment_status      | ENUM          | PENDING, CHARGED, REFUNDED, FAILED  |
| requested_at        | TIMESTAMP     | Ride request time                    |
| matched_at          | TIMESTAMP     | Driver matched time                  |
| pickup_at           | TIMESTAMP     | Rider picked up time                 |
| completed_at        | TIMESTAMP     | Ride completion time                 |
| cancelled_at        | TIMESTAMP     | Cancellation time (if applicable)    |
| cancelled_by        | ENUM          | RIDER, DRIVER, SYSTEM (nullable)     |
| cancellation_reason | VARCHAR(500)  | Free text                            |
+---------------------+---------------+--------------------------------------+
Index: (rider_id, status), (driver_id, status), (status, requested_at)
```

### 7.4 Location (Time-Series / Event Log)

```
Table: driver_locations (time-series, partitioned by day)
+-------------------+---------------+----------------------------------------+
| Column            | Type          | Notes                                  |
+-------------------+---------------+----------------------------------------+
| driver_id         | VARCHAR(36)   | FK to drivers                          |
| latitude          | DOUBLE        | GPS latitude                           |
| longitude         | DOUBLE        | GPS longitude                          |
| heading           | DOUBLE        | Compass heading (0-360 degrees)        |
| speed_mph         | DOUBLE        | Current speed                          |
| accuracy_meters   | DOUBLE        | GPS accuracy radius                    |
| timestamp         | TIMESTAMP     | GPS measurement time                   |
+-------------------+---------------+----------------------------------------+
Partition: by day (timestamp)
Index: (driver_id, timestamp DESC)
Note: Hot data (last known location) lives in Redis, not queried from this table.
```

### 7.5 Payment

```
Table: payments
+---------------------+---------------+--------------------------------------+
| Column              | Type          | Notes                                |
+---------------------+---------------+--------------------------------------+
| payment_id          | VARCHAR(36)   | PK, UUID                             |
| ride_id             | VARCHAR(36)   | FK to rides                          |
| rider_id            | VARCHAR(36)   | FK to riders (payer)                 |
| amount_cents        | INT           | Total charge                         |
| currency            | VARCHAR(3)    | ISO 4217 (USD, EUR, etc.)            |
| payment_method_id   | VARCHAR(36)   | FK to payment_methods                |
| status              | ENUM          | PENDING, AUTHORIZED, CAPTURED,       |
|                     |               | REFUNDED, FAILED                     |
| platform_fee_cents  | INT           | Uber/Lyft take (typically 20-25%)    |
| driver_payout_cents | INT           | Driver earnings                      |
| stripe_charge_id    | VARCHAR(100)  | External payment processor reference |
| created_at          | TIMESTAMP     | Payment initiation                   |
| captured_at         | TIMESTAMP     | Payment capture (after ride complete) |
+---------------------+---------------+--------------------------------------+
Index: (ride_id), (rider_id, created_at DESC), (status)
```

### 7.6 SurgeZone

```
Table: surge_zones
+---------------------+---------------+--------------------------------------+
| Column              | Type          | Notes                                |
+---------------------+---------------+--------------------------------------+
| zone_id             | VARCHAR(36)   | PK, UUID or H3 cell index            |
| city_id             | VARCHAR(36)   | FK to cities                         |
| zone_type           | ENUM          | HEXAGONAL, RECTANGULAR               |
| center_lat          | DOUBLE        | Zone center latitude                 |
| center_lng          | DOUBLE        | Zone center longitude                |
| boundary_geojson    | TEXT          | GeoJSON polygon defining the zone    |
| current_multiplier  | DECIMAL(3,2)  | Current surge multiplier (1.00-5.00) |
| supply_count        | INT           | Available drivers in zone            |
| demand_count        | INT           | Ride requests in zone (last window)  |
| supply_demand_ratio | DECIMAL(5,3)  | supply / demand                      |
| last_calculated_at  | TIMESTAMP     | Last surge recalculation             |
+---------------------+---------------+--------------------------------------+
Index: (city_id), (zone_id, last_calculated_at)
Spatial Index: on boundary_geojson -- PostGIS GIST
```

### Entity Relationship Diagram

```
+----------+       +----------+       +---------+
|  Rider   |1----*>|   Ride   |<*----1| Driver  |
+----------+       +----------+       +---------+
| rider_id |       | ride_id  |       |driver_id|
| name     |       | rider_id |       | name    |
| email    |       | driver_id|       | status  |
| phone    |       | status   |       | lat/lng |
| rating   |       | pickup   |       | rating  |
+----------+       | dropoff  |       +---------+
     |              | fare     |            |
     |              | surge    |            |
     v              +----------+            |
+----------+            |                   |
| Payment  |<-----------+                   |
| Method   |       +----------+             |
+----------+       | Payment  |             |
                   +----------+             |
                   | ride_id  |             |
                   | amount   |             |
                   | status   |       +----------+
                   +----------+       | Location |
                                      | (Redis)  |
                        +----------+  +----------+
                        |SurgeZone |  | driver_id|
                        +----------+  | lat, lng |
                        | zone_id  |  | timestamp|
                        | multiplier| +----------+
                        | supply   |
                        | demand   |
                        +----------+
```

---

## 8. High-Level Architecture

```
+------------------+          +------------------+
|   Rider App      |          |   Driver App     |
|   (Mobile)       |          |   (Mobile)       |
+--------+---------+          +--------+---------+
         |                             |
         | HTTPS / WSS                 | HTTPS / WSS (GPS stream)
         |                             |
+--------v-----------------------------v---------+
|              API Gateway / Load Balancer        |
|           (Auth, Rate Limit, Routing)           |
+----+-------+-------+-------+-------+------+----+
     |       |       |       |       |      |
     v       v       v       v       v      v
+------+ +------+ +------+ +------+ +------+ +------+
| Ride | |Match | | Loc  | |Price | |Surge | | Pay  |
| Svc  | | Svc  | | Svc  | | Svc  | | Eng  | | Svc  |
+--+---+ +--+---+ +--+---+ +--+---+ +--+---+ +--+---+
   |        |        |        |        |        |
   |        |        v        |        |        |
   |        |  +-----------+  |        |        |
   |        +->| Spatial   |  |        |        |
   |        |  | Index     |<-+--------+        |
   |        |  | (QuadTree |  |                 |
   |        |  |  /GeoHash)|  |                 |
   |        |  +-----------+  |                 |
   |        |        |        |                 |
   v        v        v        v                 v
+------+ +------+ +------+ +------+       +---------+
|Notif | |Kafka | |Redis | |PostGIS|       | Stripe  |
| Svc  | |Events| |Cache | |  DB   |       | Payment |
+------+ +------+ +------+ +------+       +---------+
   |        |
   v        v
+------+ +------+
| FCM/ | | S3 / |
| APNS | |Archiv|
+------+ +------+
```

### Numbered Request Flow: Rider Requests a Ride

```
+----------+     +----------+     +----------+     +----------+     +----------+
|  Rider   |     |   API    |     |   Ride   |     | Pricing  |     |  Surge   |
|   App    |     | Gateway  |     | Service  |     | Service  |     |  Engine  |
+----+-----+     +----+-----+     +----+-----+     +----+-----+     +----+-----+
     |                |                |                |                |
     | (1) POST       |                |                |                |
     | /rides/request  |                |                |                |
     |--------------->|                |                |                |
     |                | (2) Validate   |                |                |
     |                | auth + rate    |                |                |
     |                | limit          |                |                |
     |                |--------------->|                |                |
     |                |                | (3) Create ride|                |
     |                |                | status=REQUESTED                |
     |                |                |--------------->|                |
     |                |                |                | (4) Get surge  |
     |                |                |                | for zone       |
     |                |                |                |--------------->|
     |                |                |                |<---------------|
     |                |                |                | (5) surge=1.5x |
     |                |                |<---------------|                |
     |                |                | (6) fare estimate               |
     |                |                |                |                |
     |                |                |                |                |
+----+-----+     +----+-----+     +----+-----+     +----+-----+     +----+-----+
| Matching |     | Location |     |  Spatial  |     |  Driver  |     |  Notif   |
| Service  |     | Service  |     |  Index    |     |   App    |     | Service  |
+----+-----+     +----+-----+     +----+-----+     +----+-----+     +----+-----+
     |                |                |                |                |
     | (7) Find       |                |                |                |
     | nearest drivers|                |                |                |
     |<-------------- Ride Service     |                |                |
     |                |                |                |                |
     |--------------->|                |                |                |
     | (8) Query      |                |                |                |
     | drivers within |                |                |                |
     | 5km radius     |                |                |                |
     |                |--------------->|                |                |
     |                | (9) Range      |                |                |
     |                | query QuadTree |                |                |
     |                |<---------------|                |                |
     |                | (10) Return    |                |                |
     |<---------------| K=5 nearest    |                |                |
     |                | drivers        |                |                |
     | (11) Rank by   |                |                |                |
     | ETA, select #1 |                |                |                |
     |                |                |                |                |
     |------------------------------------------------>|                |
     |                (12) Ride offer push notification |                |
     |                                                 |                |
     |                                                 |--------------->|
     |                                                 |(13) Push to    |
     |                                                 |    driver FCM  |
     |                                                 |                |
     |<------------------------------------------------|                |
     | (14) Driver accepts (POST /rides/{id}/accept)   |                |
     |                                                 |                |
     | (15) Update ride status=MATCHED                 |                |
     |                                                 |                |
     |                                                 |                |
     +--- (16) Notify rider: "Driver matched, ETA 4m" --------------->|
                                                                       |
                                                       (17) Push to    |
                                                       rider FCM/APNS  |
```

### Ride Lifecycle State Machine

```
                    +-------------+
                    |  REQUESTED  |
                    +------+------+
                           |
              (driver found & accepts)
                           |
                    +------v------+
              +-----|   MATCHED   |-----+
              |     +------+------+     |
              |            |            |
        (rider cancels)    |     (driver cancels)
              |     (driver starts     |
              |      driving to pickup)|
              |            |            |
              v     +------v------+     v
        +---------+ |DRIVER_EN_ROUTE| +---------+
        |CANCELLED| +------+------+ |CANCELLED|
        +---------+        |        +---------+
                           |
                  (driver arrives at pickup)
                           |
                    +------v------+
                    |   ARRIVED   |
                    | (at pickup) |
                    +------+------+
                           |
                  (rider gets in, trip starts)
                           |
                    +------v------+
                    | IN_PROGRESS |
                    +------+------+
                           |
                  (driver ends trip at destination)
                           |
                    +------v------+
                    |  COMPLETED  |-------> Payment Processing
                    +-------------+
```

---

## 9. Component Deep Dive

### 9.1 Location Service

**Responsibility:** Ingest, store, and serve real-time driver location data.

**GPS Update Flow:**

```
+----------+       +-----------+       +--------+       +-----------+
| Driver   |       | WebSocket |       | Location|       |  Redis    |
|   App    |       | Server    |       | Service |       | (GeoSet)  |
+----+-----+       +-----+-----+       +----+----+       +-----+-----+
     |                    |                  |                  |
     | (1) GPS update     |                  |                  |
     | {lat, lng, ts,     |                  |                  |
     |  heading, speed}   |                  |                  |
     | (binary frame)     |                  |                  |
     |------------------->|                  |                  |
     |                    | (2) Decode +     |                  |
     |                    | validate         |                  |
     |                    |----------------->|                  |
     |                    |                  | (3) GEOADD       |
     |                    |                  | driver_locations  |
     |                    |                  | {driver_id, lat, lng}
     |                    |                  |----------------->|
     |                    |                  |                  |
     |                    |                  | (4) Publish to   |
     |                    |                  | Kafka topic      |
     |                    |                  | "driver.location" |
     |                    |                  |-----> Kafka      |
     |                    |                  |                  |
     |                    |                  | (5) Update       |
     |                    |                  | spatial index    |
     |                    |                  | (QuadTree)       |
     |                    |                  |-----> QuadTree   |
     |                    |                  |                  |
     | (6) ACK            |                  |                  |
     |<-------------------|                  |                  |
```

**Key Design Decisions:**

- **WebSocket over REST**: Persistent connection eliminates TCP handshake overhead for 167K updates/sec. Each driver maintains one long-lived WebSocket connection.
- **Redis GeoSet**: Redis GEOADD and GEORADIUS commands provide O(N+log(M)) range queries where M = total members and N = results. Perfect for "find all drivers within 5km."
- **Kafka for event sourcing**: Every location update is published to Kafka for downstream consumers (analytics, ETA service, ride tracking, surge calculation).
- **Binary encoding**: Protocol Buffers or MessagePack reduce payload size from ~200 bytes (JSON) to ~60 bytes (binary).

**GPS Accuracy Handling:**

```
Raw GPS from phone: accuracy = 10-50 meters (urban canyon, tunnels)

Filtering pipeline:
(1) Reject if accuracy > 100m (unreliable)
(2) Kalman filter: smooth jitter from consecutive readings
(3) Map snapping: snap to nearest road segment (optional, improves ETA)
(4) Speed validation: reject if implied speed > 200 mph (GPS jump)
```

**Edge Cases:**

| Edge Case                         | Handling                                           |
|-----------------------------------|----------------------------------------------------|
| Driver goes into tunnel (no GPS)  | Use last known location; mark as "stale" after 30s |
| GPS jumps (teleport artifact)     | Kalman filter + speed validation rejects outliers   |
| Driver closes app without going offline | Heartbeat timeout (60s) marks driver OFFLINE   |
| Massive simultaneous updates      | WebSocket servers are stateless, scale horizontally |
| Clock skew on driver phone        | Server assigns canonical timestamp on receipt       |

### 9.2 Matching Service

**Responsibility:** Find the best available driver for a ride request. "Best" = lowest ETA, not just closest distance.

**Matching Flow:**

```
+----------+       +-----------+       +--------+       +-----------+
| Ride     |       | Matching  |       | Location|       | ETA       |
| Service  |       | Service   |       | Service |       | Service   |
+----+-----+       +-----+-----+       +----+----+       +-----+-----+
     |                    |                  |                  |
     | (1) Match request  |                  |                  |
     | {ride_id, pickup   |                  |                  |
     |  lat/lng, type}    |                  |                  |
     |------------------->|                  |                  |
     |                    | (2) GEORADIUS     |                  |
     |                    | pickup, 5km,     |                  |
     |                    | status=AVAILABLE |                  |
     |                    |----------------->|                  |
     |                    |<-----------------|                  |
     |                    | (3) Returns 12   |                  |
     |                    | available drivers|                  |
     |                    |                  |                  |
     |                    | (4) Calculate    |                  |
     |                    | ETA for top 5    |                  |
     |                    | (by raw distance)|                  |
     |                    |---------------------------------->|
     |                    |<----------------------------------|
     |                    | (5) ETAs:        |                  |
     |                    |  D1=3m, D2=4m,   |                  |
     |                    |  D3=4m, D4=6m,   |                  |
     |                    |  D5=8m            |                  |
     |                    |                  |                  |
     |                    | (6) Rank by ETA  |                  |
     |                    | Select D1 (3 min)|                  |
     |                    |                  |                  |
     |<-------------------|                  |                  |
     | (7) Offer ride to  |                  |                  |
     | driver D1          |                  |                  |
```

**Why ETA > Raw Distance:**

```
Example: San Francisco, rush hour

Driver A: 0.5 miles away, but across Market Street (heavy traffic)
          Straight-line distance: 0.5 mi
          Actual ETA: 8 minutes (traffic, one-way streets)

Driver B: 1.2 miles away, but on same side of the street, clear route
          Straight-line distance: 1.2 mi  
          Actual ETA: 3 minutes

Naive approach (distance): picks Driver A --> rider waits 8 min
ETA approach: picks Driver B --> rider waits 3 min
```

### 9.3 Ride Service

**Responsibility:** Manage the full ride lifecycle, enforce state transitions, coordinate between all other services.

**State Transition Rules:**

```
+-------------------+--------------------+--------------------+-------------------+
| Current State     | Valid Transitions  | Triggered By       | Side Effects      |
+-------------------+--------------------+--------------------+-------------------+
| REQUESTED         | MATCHED            | Driver accepts     | Notify rider      |
|                   | CANCELLED          | Rider cancels      | Release resources |
|                   | CANCELLED          | Timeout (no driver)| Notify rider      |
+-------------------+--------------------+--------------------+-------------------+
| MATCHED           | DRIVER_EN_ROUTE    | Driver starts nav  | Start ETA tracking|
|                   | CANCELLED          | Rider cancels      | Cancel fee maybe  |
|                   | CANCELLED          | Driver cancels     | Re-match rider    |
+-------------------+--------------------+--------------------+-------------------+
| DRIVER_EN_ROUTE   | ARRIVED            | Driver at pickup   | Notify rider      |
|                   | CANCELLED          | Rider cancels      | Cancellation fee  |
|                   | CANCELLED          | Driver cancels     | Re-match rider    |
+-------------------+--------------------+--------------------+-------------------+
| ARRIVED           | IN_PROGRESS        | Rider gets in      | Start trip meter  |
|                   | CANCELLED          | No-show (5 min)    | No-show fee rider |
+-------------------+--------------------+--------------------+-------------------+
| IN_PROGRESS       | COMPLETED          | Driver ends trip   | Calculate fare    |
+-------------------+--------------------+--------------------+-------------------+
| COMPLETED         | (terminal)         | --                 | Process payment   |
+-------------------+--------------------+--------------------+-------------------+
| CANCELLED         | (terminal)         | --                 | Refund if needed  |
+-------------------+--------------------+--------------------+-------------------+
```

**Concurrency Guard on State Transitions:**

```java
// Optimistic locking with version column
// Prevents two concurrent transitions from corrupting state

UPDATE rides
SET status = 'MATCHED',
    driver_id = 'driver_def456',
    matched_at = NOW(),
    version = version + 1
WHERE ride_id = 'ride_xyz789'
  AND status = 'REQUESTED'
  AND version = 3;

// If affected_rows = 0 --> someone else already transitioned this ride
// Return 409 Conflict to the second driver
```

### 9.4 Pricing Service

**Responsibility:** Calculate fare estimates (before ride) and final fares (after ride).

**Fare Formula:**

```
fare = (base_fare + distance_charge + time_charge) * surge_multiplier + booking_fee

Where:
  base_fare        = fixed amount per ride type (e.g., $2.50 STANDARD, $5.00 PREMIUM)
  distance_charge  = distance_miles * per_mile_rate (e.g., $1.50/mi)
  time_charge      = duration_minutes * per_minute_rate (e.g., $0.25/min)
  surge_multiplier = 1.0x - 5.0x based on zone demand
  booking_fee      = flat platform fee (e.g., $2.00)

Minimum fare:
  fare = MAX(calculated_fare, minimum_fare)  -- e.g., $8.00 minimum
```

**Fare Calculation Flow:**

```
+----------+       +-----------+       +-----------+       +-----------+
| Ride     |       | Pricing   |       |  Surge    |       |   Maps    |
| Service  |       | Service   |       |  Engine   |       | (External)|
+----+-----+       +-----+-----+       +-----+-----+       +-----+-----+
     |                    |                   |                   |
     | (1) Calculate fare |                   |                   |
     | {pickup, dropoff,  |                   |                   |
     |  ride_type}        |                   |                   |
     |------------------->|                   |                   |
     |                    | (2) Get surge     |                   |
     |                    | for pickup zone   |                   |
     |                    |------------------>|                   |
     |                    |<------------------|                   |
     |                    | surge=1.5x        |                   |
     |                    |                   |                   |
     |                    | (3) Get route     |                   |
     |                    | distance + time   |                   |
     |                    |---------------------------------------->|
     |                    |<----------------------------------------|
     |                    | distance=42.3 mi, |                   |
     |                    | time=48 min       |                   |
     |                    |                   |                   |
     |                    | (4) Calculate:    |                   |
     |                    | base    = $2.50   |                   |
     |                    | distance= $63.45  |                   |
     |                    | time    = $12.00  |                   |
     |                    | subtotal= $77.95  |                   |
     |                    | * 1.5x  = $116.93 |                   |
     |                    | + fee   = $118.93 |                   |
     |                    |                   |                   |
     |<-------------------|                   |                   |
     | (5) Fare estimate: |                   |                   |
     | $116 - $122 range  |                   |                   |
     | (range for traffic |                   |                   |
     |  variability)      |                   |                   |
```

**Estimate vs Final Fare:**

| Aspect              | Estimate (pre-ride)              | Final (post-ride)                |
|---------------------|----------------------------------|----------------------------------|
| Distance            | Predicted (Maps API route)       | Actual (GPS trace odometer)      |
| Time                | Predicted (traffic model)        | Actual (timestamps)              |
| Surge               | Locked at request time           | Same as estimate (honored)       |
| Accuracy            | +/- 15% range                    | Exact to the cent                |

### 9.5 Surge Pricing Engine

**Responsibility:** Calculate real-time surge multipliers per geographic zone based on supply/demand balance.

(Detailed in Section 12 below.)

### 9.6 Payment Service

**Responsibility:** Handle all financial transactions -- authorization, capture, refunds, split fare, driver payouts.

**Payment Flow:**

```
+----------+       +-----------+       +-----------+       +-----------+
| Ride     |       | Payment   |       |  Stripe   |       |  Driver   |
| Service  |       | Service   |       | (External)|       |  Payout   |
+----+-----+       +-----+-----+       +-----+-----+       +-----+-----+
     |                    |                   |                   |
     | (1) Ride requested:|                   |                   |
     | pre-authorize $150 |                   |                   |
     | (max possible fare)|                   |                   |
     |------------------->|                   |                   |
     |                    | (2) Create        |                   |
     |                    | PaymentIntent     |                   |
     |                    | amount=$150       |                   |
     |                    | capture=manual    |                   |
     |                    |------------------>|                   |
     |                    |<------------------|                   |
     |                    | (3) auth_id       |                   |
     |                    |                   |                   |
     |     ... ride happens ...               |                   |
     |                    |                   |                   |
     | (4) Ride completed:|                   |                   |
     | actual fare=$48.50 |                   |                   |
     |------------------->|                   |                   |
     |                    | (5) Capture $48.50|                   |
     |                    | (releases $101.50 |                   |
     |                    |  hold)            |                   |
     |                    |------------------>|                   |
     |                    |<------------------|                   |
     |                    | (6) captured      |                   |
     |                    |                   |                   |
     |                    | (7) Calculate     |                   |
     |                    | driver payout:    |                   |
     |                    | $48.50 * 0.75     |                   |
     |                    | = $36.38          |                   |
     |                    |                   |                   |
     |                    | (8) Queue payout  |                   |
     |                    |---------------------------------------->|
     |                    |                   |                   |
     |<-------------------|                   |                   |
     | (9) payment_status |                   |                   |
     | = CAPTURED         |                   |                   |
```

**Split Fare:**

```
Ride fare: $48.50
Split between 3 riders: rider_A, rider_B, rider_C

rider_A: $16.17 (ceil)
rider_B: $16.17 (ceil)
rider_C: $16.16 (remainder)

Each rider charged independently.
If one rider's payment fails: platform covers the shortfall temporarily,
retries, or charges the requesting rider (rider_A) the full amount.
```

**Edge Cases:**

| Edge Case                          | Handling                                            |
|------------------------------------|-----------------------------------------------------|
| Payment fails after ride           | Retry 3x, then add to rider's debt balance          |
| Rider disputes fare                | Hold payment, trigger manual review                 |
| Driver cancels mid-ride            | Charge reduced fare (distance so far only)          |
| Pre-auth expires (ride too long)   | Re-authorize during ride if approaching limit       |
| Currency conversion (international)| Lock exchange rate at ride request time              |

### 9.7 Notification Service

**Responsibility:** Deliver real-time push notifications to riders and drivers at every state transition.

**Notification Events:**

```
+---------------------+------------------+----------------------------------+
| Ride State Change   | Notify           | Message                          |
+---------------------+------------------+----------------------------------+
| REQUESTED           | Rider            | "Finding your driver..."         |
| MATCHED             | Rider            | "Driver found! ETA 4 min"        |
| MATCHED             | Driver           | "New ride! Navigate to pickup"   |
| DRIVER_EN_ROUTE     | Rider            | "Driver is on the way"           |
| ARRIVED             | Rider            | "Your driver has arrived"        |
| IN_PROGRESS         | Rider            | "Trip started"                   |
| COMPLETED           | Rider            | "Trip complete. Fare: $48.50"    |
| COMPLETED           | Driver           | "Trip complete. Earned: $36.38"  |
| CANCELLED (by rider)| Driver           | "Rider cancelled the trip"       |
| CANCELLED (by driver)| Rider           | "Driver cancelled. Rematching..."  |
+---------------------+------------------+----------------------------------+
```

**Delivery Architecture:**

```
+----------+       +-----------+       +-----------+       +-----------+
| Ride     |       | Kafka     |       | Notif     |       | FCM/APNS  |
| Service  |       | Topic     |       | Service   |       | (Push)    |
+----+-----+       +-----+-----+       +-----+-----+       +-----+-----+
     |                    |                   |                   |
     | (1) Publish event  |                   |                   |
     | {ride_id, status,  |                   |                   |
     |  rider_id,         |                   |                   |
     |  driver_id}        |                   |                   |
     |------------------->|                   |                   |
     |                    | (2) Consume       |                   |
     |                    |------------------>|                   |
     |                    |                   | (3) Look up device|
     |                    |                   | tokens for rider  |
     |                    |                   | and driver         |
     |                    |                   |                   |
     |                    |                   | (4) Send push to  |
     |                    |                   | FCM (Android) and |
     |                    |                   | APNS (iOS)         |
     |                    |                   |------------------>|
     |                    |                   |                   |
     |                    |                   | (5) Also send via |
     |                    |                   | WebSocket if app  |
     |                    |                   | is in foreground   |
     |                    |                   |-----> WSS         |
```

**Delivery Guarantees:**

- **At-least-once**: Kafka consumer commits offset only after successful push delivery.
- **Deduplication**: Notification Service tracks sent notification IDs to prevent duplicate pushes on retry.
- **Fallback**: If push delivery fails, fall back to SMS for critical notifications (ride matched, ride cancelled).
- **TTL**: Notifications older than 5 minutes are dropped (stale information is worse than no information).

---

## 10. Spatial Indexing Deep Dive

Spatial indexing is the most architecturally significant component of a ride-sharing system. It answers the fundamental question: **"Given a point (lat, lng), find all drivers within radius R."**

### 10.1 QuadTree

**How It Works:**

A QuadTree recursively subdivides 2D space into four quadrants. Each node represents a rectangular region. When a node contains more than a threshold (e.g., 4) points, it splits into four children.

```
                 +---------------------------+
                 |            World          |
                 |                           |
                 +---------------------------+
                            |
              +-------------+-------------+
              |             |             |
     +--------+--+  +------+----+  +-----+-----+  +-------+---+
     |    NW     |  |    NE     |  |     SW     |  |    SE     |
     | (empty)   |  | D1, D2   |  |  D3         |  | D4, D5,  |
     |           |  |          |  |             |  | D6, D7,  |
     +-----------+  +----------+  +-------------+  | D8       |
                                                    +----+------+
                                                         |
                                         +------+------+------+------+
                                         | NW   | NE   | SW   | SE   |
                                         | D4,D5| D6   | D7   | D8   |
                                         +------+------+------+------+
```

**Insertion Algorithm:**

```
insert(node, driver):
    (1) If driver.location is NOT within node.boundary:
        return  // out of bounds

    (2) If node is a LEAF and has room (count < MAX_POINTS):
        node.drivers.add(driver)
        return

    (3) If node is a LEAF but full:
        subdivide(node)  // create 4 children (NW, NE, SW, SE)
        redistribute existing drivers to children
        insert into appropriate child

    (4) If node is an INTERNAL node:
        determine which child quadrant contains driver.location
        insert(child, driver)
```

**Range Query (find drivers within radius R of point P):**

```
rangeQuery(node, center, radius, results):
    (1) If node.boundary does NOT intersect circle(center, radius):
        return  // prune this entire subtree

    (2) If node is a LEAF:
        for each driver in node.drivers:
            if distance(driver.location, center) <= radius:
                results.add(driver)
        return

    (3) If node is INTERNAL:
        rangeQuery(node.NW, center, radius, results)
        rangeQuery(node.NE, center, radius, results)
        rangeQuery(node.SW, center, radius, results)
        rangeQuery(node.SE, center, radius, results)
```

**Complexity:**

| Operation       | Average Case    | Worst Case      |
|-----------------|-----------------|-----------------|
| Insert          | O(log N)        | O(N) degenerate |
| Range Query     | O(log N + K)    | O(N)            |
| Delete          | O(log N)        | O(N)            |
| Space           | O(N)            | O(N)            |

Where N = total drivers, K = drivers in result set.

**Pros and Cons for Ride-Sharing:**

| Advantage                               | Disadvantage                             |
|-----------------------------------------|------------------------------------------|
| Efficient pruning of empty regions      | Rebalancing needed as drivers move        |
| Natural hierarchical decomposition      | Not easy to distribute across machines    |
| Good for range queries                  | Updates are O(delete + insert)            |
| Adaptive resolution (dense areas = deeper tree) | In-memory only; no built-in persistence |

### 10.2 GeoHash

**How It Works:**

GeoHash encodes a (latitude, longitude) pair into a Base32 string by interleaving the bits of the latitude and longitude binary representations. Nearby points share common prefixes.

**Encoding Step-by-Step:**

```
Point: (37.7749, -122.4194) -- San Francisco

Step 1: Encode longitude (-122.4194) into bits
  Range: [-180, 180]
  -122.4194 < 0 (midpoint) --> bit=0, range=[-180, 0]
  -122.4194 > -90           --> bit=1, range=[-90, 0]  (wrong, recalculate)
  ... (continue bisecting for desired precision)

Step 2: Encode latitude (37.7749) into bits
  Range: [-90, 90]
  37.7749 > 0               --> bit=1, range=[0, 90]
  37.7749 < 45              --> bit=0, range=[0, 45]
  ... (continue bisecting)

Step 3: Interleave bits (longitude in even positions, latitude in odd)
  lon: 0 1 1 0 1 ...
  lat: 1 0 0 1 0 ...
  interleaved: 01 10 10 01 10 ...

Step 4: Convert to Base32
  Group into 5-bit chunks: 01101 01001 ...
  Map to Base32 chars: "9q8yy..."

Result: GeoHash("37.7749, -122.4194") = "9q8yyk" (6 chars = ~1.2km x 0.6km cell)
```

**Precision vs Cell Size:**

```
+----------+-------------------+----------------------------+
| Length   | Cell Size (approx) | Use Case                  |
+----------+-------------------+----------------------------+
| 1        | 5000 km x 5000 km | Continental               |
| 2        | 1250 km x 625 km  | Country/region             |
| 3        | 156 km x 156 km   | State/province             |
| 4        | 39 km x 19.5 km   | City                       |
| 5        | 4.9 km x 4.9 km   | Neighborhood               |
| 6        | 1.2 km x 0.6 km   | Block level -- RIDE-SHARING|
| 7        | 153 m x 153 m     | Street level               |
| 8        | 38 m x 19 m       | Building level             |
+----------+-------------------+----------------------------+
```

**Proximity Search with GeoHash:**

```
To find drivers near "9q8yyk":

(1) Compute the 8 neighboring GeoHash cells:
    +--------+--------+--------+
    | 9q8yym | 9q8yyt | 9q8yyw |
    +--------+--------+--------+
    | 9q8yyj | 9q8yyk | 9q8yys |  <-- center cell + 8 neighbors
    +--------+--------+--------+
    | 9q8yyh | 9q8yyn | 9q8yyp |
    +--------+--------+--------+

(2) Query Redis/DB for all drivers with GeoHash prefix in these 9 cells
    SCAN drivers WHERE geohash LIKE '9q8yyk%'
                    OR geohash LIKE '9q8yyj%'
                    OR geohash LIKE '9q8yym%'
                    ... (all 9)

(3) Post-filter by actual distance (GeoHash cells are rectangular,
    but we want a circular radius)
```

**Edge Case -- Boundary Problem:**

```
Two points very close but in different GeoHash cells:

    +----------+----------+
    |          | X        |  X and Y are 10 meters apart
    |     9q8yyk |9q8yys  |  but in different GeoHash cells
    |          |Y         |
    +----------+----------+

Solution: ALWAYS query the center cell + 8 neighbors.
This guarantees all nearby points are found regardless of cell boundaries.
```

### 10.3 H3 (Uber's Hexagonal Grid)

**Why Hexagons?**

```
Square grid problem:                Hexagonal grid advantage:
+----+----+----+                    /  \  /  \  /  \
|    |    |    |                   | H1 || H2 || H3 |
| D1 | C  | D2 |                   \  /  \  /  \  /
+----+----+----+                    | H4 || C  || H5 |
|    |    |    |                   /  \  /  \  /  \
| D3 |    | D4 |                   | H6 || H7 || H8 |
+----+----+----+                    \  /  \  /  \  /

D1 is farther from C than D2      All 6 neighbors are EQUIDISTANT
(diagonal = sqrt(2) * side)        from center C (no diagonal bias)
```

**Key Properties of H3:**

| Property                  | Value                                              |
|---------------------------|----------------------------------------------------|
| Cell shape                | Hexagon (plus 12 pentagons at icosahedron vertices)|
| Resolution levels         | 0 (continent) to 15 (< 1 m^2)                     |
| Ride-sharing resolution   | 7 (~5.16 km^2) for surge zones, 9 (~0.105 km^2) for matching |
| Neighbor count            | Exactly 6 (vs 8 for square grid)                   |
| Equidistant neighbors     | Yes (key advantage over squares and GeoHash)       |
| Hierarchical              | Yes, each hex contains ~7 children at next resolution |
| Open source               | Yes (Uber open-sourced H3 in 2018)                 |

**H3 Resolution Table:**

```
+------------+------------------+------------------------------+
| Resolution | Avg Area         | Use Case in Ride-Sharing     |
+------------+------------------+------------------------------+
| 4          | ~1,770 km^2      | City-level analytics         |
| 5          | ~252 km^2        | District-level surge zones   |
| 7          | ~5.16 km^2       | Surge pricing zones          |
| 8          | ~0.737 km^2      | Fine-grained demand tracking |
| 9          | ~0.105 km^2      | Driver matching / pickup     |
| 10         | ~0.015 km^2      | Precise location bucketing   |
+------------+------------------+------------------------------+
```

**H3 Operations:**

```
// Get H3 index for a point at resolution 7
h3Index = latLngToCell(37.7749, -122.4194, 7)
// Returns: "872830828ffffff"

// Get all neighbors (k-ring)
neighbors = gridDisk(h3Index, 1)
// Returns: [center + 6 surrounding hexagons]

// Expand search radius
ring2 = gridDisk(h3Index, 2)
// Returns: [center + 6 + 12 = 19 hexagons]
```

### 10.4 Comparison: QuadTree vs GeoHash vs H3

```
+------------------+-------------------+-------------------+-------------------+
| Criteria         | QuadTree          | GeoHash           | H3                |
+------------------+-------------------+-------------------+-------------------+
| Data Structure   | Tree (in-memory)  | String prefix     | Hexagonal grid    |
| Storage          | In-memory only    | Redis/DB string   | Integer cell ID   |
|                  |                   | index             |                   |
+------------------+-------------------+-------------------+-------------------+
| Range Query      | Tree traversal    | Prefix scan +     | K-ring expansion  |
| Method           | with pruning      | neighbor cells    | + filter          |
+------------------+-------------------+-------------------+-------------------+
| Equidistant      | No (rectangular   | No (rectangular   | Yes (hexagonal    |
| Neighbors        | cells)            | cells)            | cells)            |
+------------------+-------------------+-------------------+-------------------+
| Update Cost      | O(log N) delete   | O(1) recompute    | O(1) recompute    |
|                  | + O(log N) insert | hash string       | cell index        |
+------------------+-------------------+-------------------+-------------------+
| Distributed      | Hard (tree not    | Easy (hash prefix | Easy (cell ID     |
| Friendly         | easily sharded)   | = partition key)  | = partition key)  |
+------------------+-------------------+-------------------+-------------------+
| Boundary Issue   | None (tree prunes)| Yes (need 9-cell  | Minimal (6        |
|                  |                   | query)            | equidistant)      |
+------------------+-------------------+-------------------+-------------------+
| Dynamic Density  | Adapts (deeper    | Fixed cell size   | Fixed cell size   |
|                  | where more points)| per precision     | per resolution    |
+------------------+-------------------+-------------------+-------------------+
| Production Use   | In-memory spatial | Redis GEOADD      | Uber (matching,   |
|                  | index in a single | (uses GeoHash     | surge, analytics) |
|                  | service           | internally)       |                   |
+------------------+-------------------+-------------------+-------------------+
| Best For         | Single-node,      | Simple proximity  | Production ride-  |
|                  | moderate scale,   | search with Redis,| sharing at scale, |
|                  | interview default | distributed cache | surge zone mgmt   |
+------------------+-------------------+-------------------+-------------------+
```

**Interview Recommendation:**

> Start with GeoHash (simplest to explain, Redis supports it natively). Then mention H3 as "what Uber actually uses" and explain why hexagons are better. Save QuadTree for if the interviewer asks about in-memory spatial indexing or wants a data structure deep-dive.

---

## 11. Driver Matching Algorithm

### 11.1 Find K Nearest Drivers Within Radius

```
findNearestDrivers(pickupLat, pickupLng, radiusKm, K):

    (1) Compute H3 cell index at resolution 9 for pickup point
        cellIndex = latLngToCell(pickupLat, pickupLng, 9)

    (2) Start with k-ring = 1 (center + 6 neighbors = 7 cells)
        cells = gridDisk(cellIndex, 1)

    (3) Query Redis for all AVAILABLE drivers in these cells:
        candidates = []
        for cell in cells:
            drivers = SMEMBERS("drivers:available:" + cell)
            candidates.addAll(drivers)

    (4) If candidates.size() < K:
        Expand ring: cells = gridDisk(cellIndex, 2)  // 19 cells
        Repeat step 3 for new cells
        
        If still < K after ring 3 (37 cells, ~15km radius):
            Return "No drivers available"

    (5) For each candidate, compute straight-line distance:
        for driver in candidates:
            driver.distance = haversine(pickupLat, pickupLng, 
                                        driver.lat, driver.lng)
        
    (6) Sort by distance, take top K (e.g., K=5)
        topK = candidates.sortBy(distance).take(K)
    
    (7) Return topK for ETA calculation
```

### 11.2 ETA-Based Matching

```
matchDriver(ride):
    
    (1) topK = findNearestDrivers(ride.pickup, 5km, K=5)
    
    (2) For each driver in topK, compute road-network ETA:
        // This calls an external routing engine (OSRM, Google Maps, Mapbox)
        for driver in topK:
            driver.eta = routingEngine.getETA(
                from = driver.currentLocation,
                to   = ride.pickup,
                mode = "driving",
                traffic = "real-time"
            )
    
    (3) Rank by ETA (not distance):
        topK.sortBy(eta)
    
    (4) Apply filters:
        // Remove drivers with low acceptance rate (optional)
        // Remove drivers heading away from pickup (heading filter)
        // Remove drivers with vehicle type mismatch
        filtered = topK.filter(
            d -> d.vehicleType.matches(ride.rideType)
              && d.acceptanceRate > 0.70
        )
    
    (5) Select best match:
        bestDriver = filtered.first()
    
    (6) Send ride offer to bestDriver
        offerRide(ride, bestDriver, timeoutSeconds=15)
```

### 11.3 Driver Acceptance/Rejection Flow

```
+----------+       +-----------+       +-----------+       +-----------+
| Matching |       |  Notif    |       |  Driver   |       |  Ride     |
| Service  |       |  Service  |       |   App     |       |  Service  |
+----+-----+       +-----+-----+       +-----+-----+       +-----+-----+
     |                    |                   |                   |
     | (1) Offer ride     |                   |                   |
     | to Driver #1       |                   |                   |
     | timeout=15s        |                   |                   |
     |------------------->|                   |                   |
     |                    | (2) Push notif    |                   |
     |                    | "New ride offer!  |                   |
     |                    |  Pickup: 0.3 mi"  |                   |
     |                    |------------------>|                   |
     |                    |                   |                   |
     |           ... Driver sees notification, decides ...        |
     |                    |                   |                   |
     |   CASE A: Driver accepts within 15s    |                   |
     |<--------------------------------------|                   |
     | (3a) Accept                            |                   |
     |                                        |                   |
     |------------------------------------------------------->  |
     | (4a) Update ride: MATCHED, driver=#1   |                   |
     |                                        |                   |
     |                                                            |
     |   CASE B: Driver rejects explicitly    |                   |
     |<--------------------------------------|                   |
     | (3b) Reject                            |                   |
     |                                        |                   |
     | (4b) CASCADE: Offer to Driver #2       |                   |
     |------------------->|                   |                   |
     |                    | Push to Driver #2 |                   |
     |                    |-----> Driver #2   |                   |
     |                                                            |
     |   CASE C: Driver does not respond (timeout)                |
     | (3c) 15 seconds elapsed, no response   |                   |
     |                                        |                   |
     | (4c) CASCADE: Offer to Driver #2       |                   |
     | Mark Driver #1 acceptance_rate -= penalty                  |
     |------------------->|                   |                   |
```

### 11.4 Timeout and Cascading

```
cascadeMatch(ride, candidateDrivers, attempt=1):

    if attempt > MAX_ATTEMPTS (e.g., 3):
        ride.status = CANCELLED
        ride.cancellation_reason = "NO_DRIVERS_AVAILABLE"
        notify(ride.rider, "No drivers available. Please try again.")
        return

    driver = candidateDrivers[attempt - 1]

    (1) Lock this driver (prevent other rides from offering to same driver)
        Redis: SET "driver:{id}:offered_ride" = ride.id, EX 15 (TTL 15s)

    (2) Send push notification to driver
        notification.send(driver, rideOffer)

    (3) Start 15-second timeout timer

    (4) Wait for response:
        - If ACCEPT within 15s:
            ride.status = MATCHED
            ride.driver_id = driver.id
            driver.status = ON_RIDE
            unlock driver
            return SUCCESS

        - If REJECT or TIMEOUT:
            unlock driver
            log(driver, "rejected/timed-out", ride)
            
            (5) CASCADE to next driver:
            cascadeMatch(ride, candidateDrivers, attempt + 1)
```

**Concurrency Edge Case -- Two Rides Offered to Same Driver:**

```
Timeline:
  T=0.000s: Ride A finds Driver X as best match
  T=0.001s: Ride B finds Driver X as best match (concurrent request)
  T=0.002s: Ride A tries to lock Driver X --> SET NX succeeds --> LOCKED
  T=0.003s: Ride B tries to lock Driver X --> SET NX fails --> SKIP
  T=0.004s: Ride B cascades to Driver Y (next best)

Solution: Redis SET with NX (set-if-not-exists) as a distributed lock.
Only one ride can offer to a driver at a time.
```

---

## 12. Surge Pricing

### 12.1 Supply/Demand Calculation Per Zone

```
For each zone Z, every T minutes (T=2):

    supply(Z) = count of AVAILABLE drivers within zone Z
    demand(Z) = count of ride requests in zone Z over the last T minutes

    ratio(Z) = supply(Z) / demand(Z)

    If ratio >= 1.0:  enough drivers --> no surge
    If ratio < 1.0:   more demand than supply --> surge

    Example:
    Zone: SF Downtown
    supply = 45 available drivers
    demand = 120 ride requests in last 2 min
    ratio = 45 / 120 = 0.375
    
    This means only 37.5% of riders can get a ride at normal pricing.
    Surge pricing increases price, which:
    (a) Reduces demand (price-sensitive riders wait or choose alternatives)
    (b) Increases supply (off-duty drivers see high surge and come online)
```

### 12.2 Surge Multiplier Tiers

```
+---------------------+-------------------+----------------------------+
| Supply/Demand Ratio | Surge Multiplier  | Rider Sees                 |
+---------------------+-------------------+----------------------------+
| ratio >= 1.0        | 1.0x (no surge)   | Normal pricing             |
| 0.8 <= ratio < 1.0  | 1.2x              | "Prices are slightly higher"|
| 0.6 <= ratio < 0.8  | 1.5x              | "Prices are higher"        |
| 0.4 <= ratio < 0.6  | 2.0x              | "Demand is very high"      |
| 0.2 <= ratio < 0.4  | 2.5x              | "Peak pricing"             |
| ratio < 0.2         | 3.0x              | "Extreme demand"           |
+---------------------+-------------------+----------------------------+

Maximum cap: 5.0x (regulatory / PR reasons)
Minimum floor: 1.0x (never below normal price)
```

**Multiplier Calculation (Continuous):**

```
Instead of discrete tiers, use a continuous formula:

surge_multiplier = MAX(1.0, MIN(5.0, 1 / ratio^0.5))

Example:
  ratio = 0.375 --> 1 / 0.375^0.5 = 1 / 0.612 = 1.63x
  ratio = 0.10  --> 1 / 0.10^0.5  = 1 / 0.316 = 3.16x (capped at 5.0x)
  ratio = 1.50  --> 1 / 1.50^0.5  = 1 / 1.225 = 0.816 --> floored at 1.0x
```

### 12.3 Zone Definition

```
Approach 1: Hexagonal Grid (H3)
+---+---+---+---+
| / \ / \ / \ / |
||H1 ||H2 ||H3 ||     Each hexagon = one surge zone
| \ / \ / \ / \ |     Resolution 7: ~5.16 km^2 per zone
|  |   |   |   ||     A city like SF: ~200 zones
| / \ / \ / \ / |
||H4 ||H5 ||H6 ||     Advantage: equidistant neighbors,
| \ / \ / \ / \ |     smooth surge transitions
+---+---+---+---+

Approach 2: Rectangular Grid
+------+------+------+
|  Z1  |  Z2  |  Z3  |     Each rectangle = one surge zone
|      |      |      |     Typically 1 km x 1 km
+------+------+------+     Simpler to implement
|  Z4  |  Z5  |  Z6  |     Disadvantage: diagonal neighbors
|      |      |      |     are farther than edge neighbors
+------+------+------+

Uber uses H3 hexagonal zones in production.
For interviews, rectangular grid is simpler to explain.
```

### 12.4 Dynamic Adjustment Interval

```
Surge Recalculation Pipeline:

Every 2 minutes:

(1) Kafka consumer aggregates ride requests per zone (sliding window)
    Kafka topic: "ride.requested"
    Group by: H3 cell index at resolution 7
    Count: requests in [now-2min, now]

(2) Redis GEORADIUS counts available drivers per zone
    For each zone center: GEORADIUS driver_locations center.lat center.lng 3km
    Filter: status = AVAILABLE

(3) Calculate ratio and multiplier per zone
    For each zone:
        ratio = supply / demand
        multiplier = formula(ratio)

(4) Write updated multipliers to Redis (low-latency reads)
    SET "surge:zone:{zone_id}" = multiplier, EX 180

(5) Publish surge update events to Kafka
    Consumers: Pricing Service, Rider App (for UI updates)
```

**Smoothing -- Prevent Oscillation:**

```
Problem: Surge increases --> riders leave --> demand drops --> surge drops
         --> riders come back --> demand spikes --> surge increases again

Solution: Exponential Moving Average (EMA)

new_multiplier = alpha * calculated_multiplier + (1 - alpha) * previous_multiplier
Where alpha = 0.3 (30% weight on new calculation, 70% on previous)

This prevents rapid oscillation and provides a smooth user experience.

Also enforce:
  - Max increase per interval: +0.5x (prevents sudden 1.0x -> 3.0x jump)
  - Max decrease per interval: -0.5x (surge doesn't collapse instantly)
  - Cool-down period: 5 minutes minimum at elevated level before reducing
```

**Surge Pricing Visualization (Rider App):**

```
+------------------------------------------+
|  Map View                                |
|                                          |
|    [Normal]        [1.5x zone]           |
|     (green)        (orange hatch)        |
|                                          |
|        * <- Your pickup                  |
|                                          |
|    [2.0x zone]                           |
|     (red hatch)                          |
|                                          |
+------------------------------------------+
| Prices are higher due to increased       |
| demand. Surge: 1.5x                      |
|                                          |
| Estimated fare: $45.00 - $52.00          |
|                                          |
| [Accept & Request Ride]                  |
+------------------------------------------+
```

---

## 13. Concurrency

### 13.1 Concurrent Ride Requests for Same Driver

**Problem:** Two riders request rides simultaneously. Both find Driver X as the best match. Both try to assign Driver X.

```
Timeline without protection:
  T=0: Rider A --> Matching finds Driver X (available)
  T=0: Rider B --> Matching finds Driver X (available)
  T=1: Rider A --> Assigns Driver X to Ride A (writes to DB)
  T=1: Rider B --> Assigns Driver X to Ride B (writes to DB)
  T=2: Driver X now has TWO rides! Inconsistent state.
```

**Solution: Optimistic Locking + Redis Lock**

```
Layer 1: Redis distributed lock (fast, prevents offer collision)

    offerRideToDriver(rideId, driverId):
        locked = Redis.SET("driver_lock:" + driverId, rideId, NX, EX, 20)
        if (!locked):
            return DRIVER_BUSY  // cascade to next driver
        
        // Send offer, wait for accept/reject/timeout
        // On completion: Redis.DEL("driver_lock:" + driverId)

Layer 2: Database optimistic locking (safety net)

    UPDATE rides
    SET driver_id = ?, status = 'MATCHED', version = version + 1
    WHERE ride_id = ? AND status = 'REQUESTED' AND version = ?;
    
    -- If affected_rows == 0: concurrent modification, retry/cascade

Layer 3: Driver status column check

    UPDATE drivers
    SET status = 'ON_RIDE'
    WHERE driver_id = ? AND status = 'AVAILABLE';
    
    -- If affected_rows == 0: driver already on another ride
```

### 13.2 Concurrent Location Updates

**Problem:** 500K drivers sending GPS updates every 3 seconds = 167K writes/sec to Redis.

```
Solution: Pipelining + Sharding

(1) Redis Cluster: 10 shards, each handles ~17K writes/sec (well within limits)
    Key: driver_id determines shard via CRC16 hash slot

(2) WebSocket server batches updates:
    Buffer GPS updates for 100ms
    Send batch GEOADD command (one round-trip for ~50 updates)
    
    GEOADD driver_locations
        -122.4194 37.7749 driver_001
        -122.4180 37.7751 driver_002
        -122.4200 37.7740 driver_003
        ...

(3) Separate write and read paths:
    Writes: WebSocket server --> Redis GEOADD (fire-and-forget, no ACK needed)
    Reads:  Matching service --> Redis GEORADIUS (synchronous, needs result)
    
    This means a location update being 1-2 seconds stale is acceptable
    (eventual consistency for location data).
```

### 13.3 Ride State Machine Concurrency

**Problem:** Rider cancels at the exact moment driver accepts.

```
Timeline:
  T=0:   Ride status = REQUESTED
  T=5:   Driver offered ride
  T=10:  Rider taps "Cancel" on app
  T=10:  Driver taps "Accept" on app (simultaneous!)
  
  Server processes:
  T=10.001: Cancel request arrives:
            UPDATE rides SET status='CANCELLED' WHERE ride_id=? AND status='REQUESTED'
            affected_rows = 1 --> SUCCESS (status is now CANCELLED)
  
  T=10.002: Accept request arrives:
            UPDATE rides SET status='MATCHED' WHERE ride_id=? AND status='REQUESTED'
            affected_rows = 0 --> FAIL (status is CANCELLED, not REQUESTED)
            
            Return 409 to driver: "Ride was cancelled by rider"
```

The WHERE clause acts as a compare-and-swap. Only one concurrent operation succeeds because the status precondition can only be satisfied once.

---

## 14. Scaling

### 14.1 Scaling GPS Updates (Write Path)

```
Challenge: 167K GPS updates/sec at peak (500K drivers, 3s interval)

Architecture:

+------------------+     +------------------+     +------------------+
| Driver App       |     | Driver App       |     | Driver App       |
| (x 500,000)     |     | (x 500,000)     |     | (x 500,000)     |
+--------+---------+     +--------+---------+     +--------+---------+
         |                        |                        |
         | WebSocket              | WebSocket              | WebSocket
         |                        |                        |
+--------v---------+     +--------v---------+     +--------v---------+
| WS Server #1     |     | WS Server #2     |     | WS Server #50   |
| (10K connections)|     | (10K connections)|     | (10K connections)|
+--------+---------+     +--------+---------+     +--------+---------+
         |                        |                        |
         | Batch GEOADD           | Batch GEOADD           | Batch GEOADD
         | (100ms buffer)         | (100ms buffer)         | (100ms buffer)
         |                        |                        |
+--------v------------------------v------------------------v---------+
|                     Redis Cluster (10 shards)                      |
|  Shard 0: 50K drivers | Shard 1: 50K drivers | ... | Shard 9      |
|  ~17K writes/sec each                                              |
+--------------------------------------------------------------------+
         |
         | (Async) Publish to Kafka
         |
+--------v-----------------------------------------------------------+
|                     Kafka Cluster                                  |
|  Topic: driver.location.updates (partitioned by city_id)           |
|  Consumers: Analytics, Surge Engine, Ride Tracker, ETA Service     |
+--------------------------------------------------------------------+
```

**Scaling Strategy:**

| Component          | Scale Axis          | Approach                          |
|--------------------|---------------------|-----------------------------------|
| WebSocket Servers  | Horizontal (more pods)| Each handles 10K connections; stateless; sticky sessions via LB |
| Redis Cluster      | Horizontal (more shards)| CRC16 hash slots across shards; GEOADD is O(log N) |
| Kafka              | Horizontal (partitions)| Partition by city_id; each city processed independently |
| Location Service   | Horizontal (more instances)| Stateless; reads from Redis, writes to Redis |

### 14.2 Scaling Matching (Read Path)

```
Challenge: 1000 ride requests/sec at peak, each requiring a spatial query

Matching Service scaling:

(1) Stateless: Each instance reads from Redis GEORADIUS independently
    Scale by adding more pods (Kubernetes HPA on CPU/request-count)

(2) Per-city isolation: Partition matching by city
    SF riders --> SF Matching instances --> SF Redis shard
    NYC riders --> NYC Matching instances --> NYC Redis shard
    
    No cross-city queries needed (drivers don't teleport between cities)

(3) Caching hot zones:
    Downtown areas have 100+ available drivers. Cache the list for 5 seconds.
    Key: "available_drivers:zone:{zone_id}" TTL=5s
    Reduces GEORADIUS calls for high-demand areas.

(4) Pre-computed K-nearest:
    Background job periodically (every 5s) computes nearest 10 drivers
    for each active H3 cell. Stores in Redis hash.
    Matching service reads pre-computed result instead of live GEORADIUS.
```

### 14.3 WebSocket Connection Management

```
Challenge: 500K concurrent WebSocket connections

+--------------------+       +--------------------+
| Load Balancer      |       | Connection Registry|
| (L4, sticky by IP) |       | (Redis)            |
+----+-----+---------+       +--------+-----------+
     |     |                          |
     v     v                          |
+----+--+ +----+--+                   |
| WS #1 | | WS #2 | ... (50 servers) |
| 10K   | | 10K   |                   |
+---+---+ +---+---+                   |
    |         |                       |
    +----+----+                       |
         |                            |
    (1) On connect: register          |
    driver_id -> ws_server_id         |
    in Redis                     -----+
    
    (2) When Matching Service needs to push ride offer to Driver X:
        - Look up: which WS server has Driver X's connection?
        - Redis: GET "ws_conn:driver_X" --> "ws_server_3"
        - Send internal message to ws_server_3: "push to driver_X"
        
    (3) If WS server crashes:
        - LB detects via health check (5s)
        - Drivers on that server auto-reconnect (client retry logic)
        - New connections land on remaining healthy servers
        - Old entries in Redis expire (TTL 60s) or are cleaned up
```

### 14.4 Database Scaling

```
Read/Write Split:

                    Rides Table
                    
  +------------------+          +------------------+
  |  Primary (Write) |--------->| Replica 1 (Read) |
  |  PostgreSQL      | async    | PostgreSQL       |
  +--------+---------+ repl     +------------------+
           |                    +------------------+
           +------------------->| Replica 2 (Read) |
                    async       | PostgreSQL       |
                    repl        +------------------+

Write path: Ride state transitions (REQUESTED -> MATCHED -> COMPLETED)
            ~1000 writes/sec --> single primary handles this easily

Read path: Ride history queries, analytics dashboards
            Replicas handle read load

Sharding (if needed at extreme scale):
  Shard key: city_id
  Each city's rides go to a separate database shard
  No cross-city queries needed
  
  Shard 0: rides WHERE city_id IN ('sf', 'la', 'sd')  -- West Coast
  Shard 1: rides WHERE city_id IN ('nyc', 'bos', 'dc') -- East Coast
```

---

## 15. Database Choice

| Data Type                | Database            | Rationale                                            |
|--------------------------|---------------------|------------------------------------------------------|
| **Real-Time Driver Location** | **Redis** (GeoSet) | Sub-ms GEORADIUS queries, GEOADD for updates, in-memory, perfect for 167K writes/sec |
| **Ride State & History** | **PostgreSQL**      | ACID transactions for ride state machine, strong consistency, mature ecosystem |
| **Spatial Queries (if needed beyond Redis)** | **PostGIS** (PostgreSQL extension) | Complex geo queries, polygon containment, route analysis |
| **Location History (time-series)** | **TimescaleDB** or **InfluxDB** | Time-partitioned storage, efficient for "driver path over last 30 min", auto-compaction |
| **Event Stream**         | **Kafka**           | Location events, ride events, surge calculations -- high-throughput, durable, replayable |
| **Surge Zone State**     | **Redis**           | Low-latency reads for current surge multiplier, TTL-based expiry |
| **Payment Records**      | **PostgreSQL**      | Financial data requires ACID, audit trail, foreign keys to rides |
| **User Profiles**        | **PostgreSQL**      | Relational data, CRUD, indexed by email/phone |
| **Session / Auth Tokens**| **Redis**           | Fast lookup, TTL-based expiry |
| **Analytics / Reporting** | **ClickHouse** or **BigQuery** | Columnar storage for aggregations over billions of ride records |

**Why Not a Single Database?**

```
Polyglot persistence is necessary because:

(1) Location data is write-heavy (167K/sec), ephemeral, and needs geo queries
    --> Redis (in-memory, GEOADD/GEORADIUS, no persistence needed for "last known")

(2) Ride data is write-moderate (1K/sec), needs ACID, long-term storage
    --> PostgreSQL (transactions, constraints, JOIN for billing/audit)

(3) Event streaming for decoupled async processing
    --> Kafka (durable log, multiple consumers, replay capability)

(4) Time-series location history for analytics (not real-time)
    --> TimescaleDB (compression, automatic partitioning by time)

Using one database for all would either:
  - Be too slow for location writes (PostgreSQL can't sustain 167K geo-writes/sec)
  - Lack ACID for financial data (Redis has no transactions across keys)
  - Be too expensive (keeping all history in Redis RAM)
```

---

## 16. CAP Theorem

### CP: Ride State (Strong Consistency)

```
Ride state MUST be consistent. Two riders must never be matched with the same driver.

+------------------+     +------------------+
| Ride Service     |     | PostgreSQL       |
| Instance A       |---->| Primary          |
+------------------+     | (serializable    |
                         |  isolation or    |
+------------------+     |  row-level lock) |
| Ride Service     |---->|                  |
| Instance B       |     +------------------+
+------------------+

Strategy: 
  - Single primary PostgreSQL for writes
  - Optimistic locking (version column) on ride and driver tables
  - Serializable reads for matching (SELECT FOR UPDATE on driver row)
  
Tradeoff:
  - If primary goes down, writes are unavailable until failover (~30s)
  - This is acceptable: a 30-second ride request pause is better than
    double-booking a driver (data corruption)
```

### AP: Location Tracking (Eventual Consistency)

```
Driver location can tolerate staleness. A 3-second-old position is fine for matching.

+------------------+     +------------------+     +------------------+
| WS Server #1     |---->| Redis Shard 0    |     | Redis Shard 1    |
+------------------+     +------------------+     +------------------+
| WS Server #2     |---->|                  |     |                  |
+------------------+     | (independent     |     | (independent     |
| WS Server #3     |---->|  in-memory, no   |     |  replication     |
+------------------+     |  cross-shard     |     |  within shard    |
                         |  consistency)    |     |  only)           |
                         +------------------+     +------------------+

Strategy:
  - Redis Cluster with eventual consistency across replicas
  - Writes go to shard master, replicate async to shard replica
  - If a shard goes down, that shard's drivers temporarily disappear from search
    (they reappear when the shard recovers or drivers reconnect to another shard)
  
Tradeoff:
  - A driver might be matched based on a slightly stale position
  - This is acceptable: the ETA calculation uses real-time routing anyway,
    and a few seconds of staleness is negligible for a 3-5 minute pickup
```

### Summary: CP vs AP by Component

```
+-------------------------+-----------+-------------------------------------------+
| Component               | Model     | Rationale                                 |
+-------------------------+-----------+-------------------------------------------+
| Ride State Machine      | CP        | Double-booking is unacceptable             |
| Driver Assignment       | CP        | Exactly one driver per ride, one ride per  |
|                         |           | driver at a time                           |
| Payment Processing      | CP        | Financial transactions must be exact       |
| Driver Location         | AP        | Staleness of 3-5 seconds is fine           |
| Surge Multiplier        | AP        | Recalculated every 2 min; stale by         |
|                         |           | seconds is irrelevant                      |
| Push Notifications      | AP        | At-least-once delivery; duplicates OK      |
| ETA Estimates           | AP        | Approximate by nature; 10% variance normal |
| Ride History / Analytics| AP        | Eventual consistency for read replicas     |
+-------------------------+-----------+-------------------------------------------+
```

---

## 17. Cloud Services

| Component                    | AWS                          | GCP                        | Azure                       |
|------------------------------|------------------------------|----------------------------|-----------------------------|
| API Gateway                  | API Gateway + ALB            | Cloud Endpoints + GLB      | API Management + App Gateway|
| WebSocket Servers            | ECS/EKS (Fargate)           | GKE                        | AKS                         |
| Ride / Matching / Pricing Services | ECS/EKS (Fargate)     | Cloud Run / GKE            | AKS / Container Apps        |
| Redis (Location + Surge)     | ElastiCache (Redis Cluster) | Memorystore (Redis)        | Azure Cache for Redis       |
| PostgreSQL (Rides, Payments) | RDS Aurora PostgreSQL       | Cloud SQL (PostgreSQL)     | Azure DB for PostgreSQL     |
| PostGIS                      | RDS PostgreSQL + PostGIS     | Cloud SQL + PostGIS        | Azure PostgreSQL + PostGIS  |
| Kafka (Event Streaming)      | MSK (Managed Kafka)         | Pub/Sub / Confluent Cloud  | Event Hubs (Kafka mode)     |
| Time-Series (Location History)| Timestream                  | BigQuery (streaming insert)| Azure Data Explorer         |
| Push Notifications           | SNS + Firebase (FCM)        | Firebase Cloud Messaging   | Notification Hubs           |
| Object Storage (Trip Logs)   | S3                           | Cloud Storage              | Blob Storage                |
| CDN (Static Assets, Maps)    | CloudFront                   | Cloud CDN                  | Azure CDN                   |
| Monitoring                   | CloudWatch + X-Ray           | Cloud Monitoring + Trace   | Application Insights        |
| Maps / Routing               | Amazon Location Service      | Google Maps Platform       | Azure Maps                  |

---

## 18. Tradeoffs Summary

| Decision                        | Option A                        | Option B                        | Choice & Rationale                                  |
|---------------------------------|---------------------------------|---------------------------------|-----------------------------------------------------|
| Location storage                | PostgreSQL + PostGIS            | Redis GeoSet                    | **Redis** -- 167K writes/sec needs in-memory speed; PostGIS for cold analytics only |
| Spatial index                   | QuadTree (in-memory)            | GeoHash (Redis) / H3           | **GeoHash (Redis)** for simplicity + **H3** for surge zones; QuadTree for interview deep-dive |
| Matching metric                 | Raw distance (haversine)        | ETA (road network + traffic)   | **ETA** -- closer driver may have longer drive time due to traffic/one-way streets |
| Driver-server communication     | HTTP polling                    | WebSocket / gRPC stream        | **WebSocket** -- persistent connection avoids TCP handshake overhead at 167K updates/sec |
| Surge recalculation             | Per request (on-demand)         | Periodic batch (every 2 min)   | **Periodic batch** -- amortizes cost, prevents oscillation, cacheable |
| Ride state consistency          | Eventual (AP)                   | Strong (CP)                    | **CP** -- double-booking is worse than brief unavailability during failover |
| Payment timing                  | Charge at request               | Auth at request, capture at completion | **Auth + capture** -- rider not charged if ride cancelled; holds released automatically |
| Driver offer strategy           | Broadcast to all nearby drivers | Sequential offer (one at a time) | **Sequential** -- prevents multiple drivers racing to same pickup; controlled experience |
| Location data retention         | Keep all forever                | Hot (Redis) + cold (time-series, 30d) | **Tiered** -- only "last known" needs sub-ms access; history for analytics only |
| Zone shape for surge            | Rectangular grid                | Hexagonal grid (H3)            | **H3 hexagons** -- equidistant neighbors, no diagonal bias, industry standard |
| Fare locking                    | Surge at ride time              | Surge locked at request time   | **Locked at request** -- rider accepted the price; changing mid-ride breaks trust |
| Notification delivery           | At-most-once                    | At-least-once                  | **At-least-once** -- duplicate "driver arrived" is annoying but missed one is worse |

---

## 19. Interview Talking Points

### Opening Statement (30 seconds)

> "A ride-sharing system is a real-time, geo-distributed two-sided marketplace. The core challenges are: ingesting millions of GPS updates per second, performing fast spatial queries to match riders with the nearest available driver, managing a concurrent state machine for ride lifecycle, and implementing dynamic surge pricing. I'll start with the APIs and data model, then go deep on the spatial indexing and matching algorithm."

### Key Points to Hit

**1. Spatial Indexing (most likely deep-dive):**
- Start with GeoHash: explain encoding, prefix matching, 9-cell query for boundary handling.
- Upgrade to H3: explain why hexagons are better (equidistant neighbors, no diagonal bias).
- QuadTree: explain recursive subdivision, range query pruning, O(log N + K) complexity.
- Uber uses H3 in production. Redis uses GeoHash internally for GEORADIUS.

**2. Driver Matching:**
- ETA > distance. A driver 0.5 miles away across heavy traffic is worse than one 1.2 miles on a clear road.
- Sequential offer (not broadcast) to prevent races.
- Redis NX lock to prevent two rides from offering to the same driver.
- Cascade with 15-second timeout, max 3 attempts.

**3. Concurrency (favorite gotcha question):**
- Same driver matched to two rides: Redis SET NX as distributed lock + PostgreSQL optimistic locking.
- Rider cancels while driver accepts: WHERE clause on status acts as CAS.
- 167K GPS writes/sec: Redis pipelining + cluster sharding.

**4. Surge Pricing:**
- Supply/demand ratio per zone, recalculated every 2 minutes.
- Exponential moving average to prevent oscillation.
- Surge locked at request time (honor the price the rider accepted).

**5. Scaling:**
- 50 WebSocket servers (10K connections each) for 500K drivers.
- Redis Cluster (10 shards) for location writes.
- Kafka for decoupling location events from downstream consumers.
- Per-city partitioning: SF rides never touch NYC infrastructure.

**6. CAP Tradeoffs:**
- CP for ride state (double-booking is catastrophic).
- AP for location data (3-second staleness is fine).
- AP for surge pricing (it's recalculated every 2 minutes anyway).

### Common Follow-Up Questions

| Question                                    | Key Points in Answer                                    |
|---------------------------------------------|---------------------------------------------------------|
| "How do you handle a driver going offline mid-ride?" | Heartbeat timeout (60s). Ride transitions to DRIVER_UNREACHABLE. Notify rider. Wait 5 min for reconnect, then offer re-match. |
| "What if there are no drivers available?"   | Expand search radius (k-ring 1 -> 2 -> 3). If still none after 30s, notify rider "no drivers available." Surge should attract drivers within minutes. |
| "How do you prevent surge price manipulation?" | Lock surge at request time. Detect artificial demand patterns (bot requests). Rate-limit ride requests per user. Minimum ride completion rate for drivers. |
| "How would you add ride pooling (shared rides)?" | Extend matching algorithm to consider detour cost. Rider A's route is checked: can we pick up Rider B with < 5 min detour? Requires real-time route optimization (NP-hard approximation). |
| "What happens if Redis goes down?"          | Matching degrades (no spatial index). Fallback: query PostGIS directly (slower but functional). Rides in progress continue (state is in PostgreSQL). Location updates buffer in Kafka until Redis recovers. |
| "How do you test this system?"              | Unit tests: state machine transitions, fare calculation. Integration: Redis GEORADIUS with known driver positions. Load test: simulate 167K GPS updates/sec, measure p99 latency. Chaos: kill a Redis shard, verify graceful degradation. |
| "How do you handle GPS drift in tunnels/garages?" | Kalman filter smoothing. Mark location as stale after 30s of no update. Map-snap to nearest road. Speed validation (reject teleportation artifacts). |
| "Why not use a graph database for road networks?" | Road network data comes from third-party map providers (Google Maps, OSRM). We call their routing API for ETA. We don't store or query the road graph ourselves. |

### Whiteboard Drawing Order

```
Step 1: Draw the rider/driver apps + API gateway (2 min)
Step 2: Add the core services (Ride, Matching, Location, Pricing) (3 min)
Step 3: Add data stores (Redis, PostgreSQL, Kafka) (2 min)
Step 4: Draw the ride request flow with numbered steps (5 min)
Step 5: Deep-dive on spatial indexing (GeoHash/H3) if asked (5-10 min)
Step 6: Deep-dive on matching algorithm if asked (5 min)
Step 7: Discuss concurrency, scaling, CAP tradeoffs (5-10 min)
```

### Complexity Comparison for Spatial Structures

```
+--------------------+-------------+-------------+-------------+
| Operation          | QuadTree    | GeoHash     | H3          |
+--------------------+-------------+-------------+-------------+
| Point to cell/node | O(log N)    | O(1)        | O(1)        |
| Range query        | O(log N + K)| O(neighbors)| O(k-ring)   |
| Insert/Update      | O(log N)    | O(1)        | O(1)        |
| Delete             | O(log N)    | O(1)        | O(1)        |
| Distributed?       | Hard        | Easy        | Easy        |
+--------------------+-------------+-------------+-------------+
N = total points, K = results in range
```

### Red Flags to Avoid in Interviews

| Red Flag                                    | What to Say Instead                                   |
|---------------------------------------------|-------------------------------------------------------|
| "Match closest driver by distance"          | "Match by ETA, not raw distance -- traffic matters"   |
| "Store locations in PostgreSQL"             | "Redis GeoSet for real-time, PostgreSQL for history"  |
| "Broadcast ride to all nearby drivers"      | "Sequential offer with timeout + cascade"             |
| "Calculate surge on every request"          | "Pre-compute surge per zone every 2 minutes, cache in Redis" |
| "Use a single relational DB for everything" | "Polyglot persistence: Redis, PostgreSQL, Kafka, each for its strength" |
| "Square grid for zones"                     | "Hexagonal grid (H3) -- equidistant neighbors, Uber uses it" |
| Ignoring concurrency                        | Proactively mention: "Two rides can't match same driver -- Redis NX lock + optimistic locking" |

---

*End of High-Level Design: Ride-Sharing Service*
