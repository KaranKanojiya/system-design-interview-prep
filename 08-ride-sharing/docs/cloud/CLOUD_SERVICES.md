# Ride-Sharing -- Cloud Service Mapping

## Component-to-Service Mapping

| Our Component | AWS | Azure | GCP | Notes |
|---------------|-----|-------|-----|-------|
| **API Gateway** | API Gateway (REST + WebSocket) | API Management + SignalR | Cloud Endpoints + Firebase | WebSocket for real-time tracking |
| **Ride Service / Matching** | ECS/EKS (Fargate) | AKS | GKE | Stateless microservices, auto-scale |
| **Location Service** | ECS + ElastiCache Redis (GEO) | AKS + Azure Cache Redis | GKE + Memorystore | Redis GEOADD/GEOSEARCH for spatial |
| **Ride DB (state)** | RDS PostgreSQL + PostGIS | Azure Database for PostgreSQL | Cloud SQL (PostGIS) | CP -- ride state must be consistent |
| **Driver Location Store** | ElastiCache Redis (GEO commands) | Azure Cache for Redis | Memorystore (Redis) | AP -- stale location OK, 3s TTL |
| **Spatial Index** | ElastiCache Redis GEOSEARCH | Azure Cache GEOSEARCH | Memorystore GEOSEARCH | O(log N + K) range queries |
| **Event Streaming** | Kinesis Data Streams / MSK (Kafka) | Event Hubs / Kafka on HDInsight | Pub/Sub / Confluent on GCP | Location updates, ride events |
| **Trip History** | DynamoDB | Cosmos DB | Bigtable / Firestore | High-volume append-only ride logs |
| **Object Storage** | S3 | Blob Storage | Cloud Storage | Receipts, invoices, ML training data |
| **Monitoring** | CloudWatch + X-Ray | Azure Monitor + App Insights | Cloud Monitoring + Trace | Latency, match rate, surge metrics |
| **Push Notifications** | SNS (mobile push) + SQS (queue) | Notification Hubs + Service Bus | FCM + Cloud Tasks | Driver/rider notifications |
| **Real-time Updates** | API Gateway WebSocket / AppSync | SignalR Service | Firebase Realtime DB | Live driver position on rider map |
| **Surge Pricing Cache** | ElastiCache Redis (key-value) | Azure Cache Redis | Memorystore | Surge multiplier per zone, 30s TTL |
| **Payment Processing** | Lambda + Step Functions + Stripe | Azure Functions + Stripe | Cloud Functions + Stripe | Async fare calculation + charge |

---

## Location Tracking Architecture on AWS (Numbered Flow)

```
Driver App (GPS every 3-5 seconds)
    │
    1. WebSocket connection to API Gateway (persistent)
    │
    ▼
┌──────────────────────────────────────────────┐
│          API Gateway (WebSocket)              │
│   Maintains persistent connection per driver  │
└──────────────────┬───────────────────────────┘
                   │
    2. Route message to Location Lambda/ECS
                   │
                   ▼
┌──────────────────────────────────────────────┐
│          Location Service (ECS)              │
│   Validates, deduplicates, rate-limits       │
└─────┬───────────────────┬────────────────────┘
      │                   │
      │    3. Write to     │    4. Publish to
      │    Redis GEO       │    Kinesis stream
      │                   │
      ▼                   ▼
┌─────────────────┐  ┌────────────────────────┐
│ ElastiCache     │  │  Kinesis Data Stream    │
│ Redis (GEO)     │  │  (location-updates)     │
│                 │  │                          │
│ GEOADD drivers  │  │  Shard by driver_id     │
│   lng lat id    │  │  Retention: 24 hours     │
│                 │  │                          │
│ GEOSEARCH       │  └─────┬──────────┬────────┘
│   FROMLONLAT    │        │          │
│   BYRADIUS 5km  │        │          │
└─────────────────┘        │          │
                           │          │
            5. Consumer:   │          │  6. Consumer:
            ETA Service    │          │  Analytics/ML
                           ▼          ▼
                    ┌────────────┐  ┌────────────────┐
                    │ ETA Lambda │  │ Kinesis Firehose│
                    │ recalculate│  │ --> S3 (data    │
                    │ arrival    │  │     lake)       │
                    │ times      │  └────────────────┘
                    └────────────┘

    7. Rider requests live tracking:
       API Gateway WebSocket pushes driver location
       every 3 seconds from Redis GEO snapshot
```

### Redis GEO Commands for Location

```
# Driver sends location update
GEOADD drivers -73.985428 40.748817 "driver:d001"

# Find 5 nearest drivers within 5km of rider
GEOSEARCH drivers FROMLONLAT -73.985428 40.748817 BYRADIUS 5 km
    ASC COUNT 5 WITHCOORD WITHDIST

# Result:
#   1) "driver:d003" -- 0.8 km -- (-73.983, 40.750)
#   2) "driver:d007" -- 1.2 km -- (-73.990, 40.745)
#   3) "driver:d001" -- 1.9 km -- (-73.978, 40.752)

# Remove driver when they go offline
ZREM drivers "driver:d001"

# TTL trick: Use a separate key per driver with EXPIRE
SET driver:d001:active 1 EX 30   # auto-offline after 30s no update
```

---

## Real-Time Architecture Options

### Option A: API Gateway WebSocket (Recommended)

```
┌──────────────┐    1. $connect       ┌────────────────────┐
│  Rider App   │ ───────────────────► │  API Gateway        │
│              │ ◄─────────────────── │  (WebSocket API)    │
│              │    6. push location   │                    │
└──────────────┘                      └─────┬──────────────┘
                                            │
                                   2. store connectionId
                                      in DynamoDB
                                            │
                                            ▼
                                     ┌──────────────┐
                                     │  DynamoDB     │
                                     │  connections  │
                                     │  table        │
                                     └──────────────┘
                                            │
                     3. Location Service publishes to SNS/Kinesis
                                            │
                     4. Lambda consumes event
                                            │
                     5. Lambda calls @connections API
                        to push to specific rider's WebSocket
```

**Pros:** Serverless, auto-scaling, pay-per-message, no infra to manage
**Cons:** 500 connections/sec connect rate limit, 10-min idle timeout, ~100ms overhead

### Option B: AppSync (GraphQL Subscriptions)

```
Rider App --> AppSync subscription --> onDriverLocationUpdated(rideId)
                                          │
                                    Auto-pushes when
                                    mutation updates
                                    driver location
```

**Pros:** Built-in subscriptions, GraphQL flexibility, offline support
**Cons:** Higher latency (~200ms), less control, more expensive at scale

### Recommendation for Interview

> "For real-time driver tracking, I'd use **API Gateway WebSocket** for the rider-to-driver live map, and **Kinesis** for the backend event stream. WebSocket gives sub-100ms push latency, and Kinesis handles the firehose of 500K driver location updates per second with ordered processing per driver shard key."

---

## Uber's Actual Tech Stack (Reference)

| Component | Uber's Choice | Why |
|-----------|--------------|-----|
| **Spatial Index** | H3 (hexagonal grid) | Uniform area cells, no edge distortion, open-source |
| **Service Mesh** | Ringpop (Swim protocol) | Consistent hashing for stateful services, peer-to-peer gossip |
| **Database** | Schemaless (on MySQL) | Append-only, eventually consistent, cell-based architecture |
| **Time-series** | M3 (on top of etcd) | Metrics at scale, open-source (donated to CNCF) |
| **Message Queue** | Cherami / Kafka | Cherami for at-least-once, Kafka for streaming analytics |
| **Geospatial** | H3 + DISCO (dispatch) | H3 for spatial indexing, DISCO for driver-rider matching |
| **Maps** | Self-built (post-Google Maps) | Cost control, routing customization |
| **Surge** | Real-time demand/supply per H3 cell | Dynamic pricing per hexagonal zone |
| **Language** | Go, Java, Python, Node.js | Go for services, Java for Android, Python for ML |

### H3 vs QuadTree vs GeoHash

```
                QuadTree              GeoHash              H3 (Uber)
Shape:          Rectangles            Rectangles            Hexagons
Precision:      Variable depth        Fixed prefix length   Fixed resolution (0-15)
Neighbors:      Complex (8 neighbors  Prefix-based          Simple (6 neighbors,
                 at different levels)  (edge cases at        always equidistant)
                                       boundaries)
Area:           Non-uniform           Non-uniform           ~Uniform (within 
                (at same depth)       (distortion at poles)  resolution level)
Use case:       In-memory spatial     Redis/DB indexing     Uber dispatch, ride
                queries, gaming                             pricing zones
Interview:      Best to explain       Best for DB storage   Mention as "what Uber
                (visual, intuitive)   (string comparison)   actually uses"
```

---

## Multi-Region Deployment for Ride-Sharing

```
                         ┌───────────────────────────────┐
                         │       Route 53 (DNS)          │
                         │  Geolocation-based routing    │
                         │  US riders → us-east-1        │
                         │  EU riders → eu-west-1        │
                         │  APAC riders → ap-southeast-1 │
                         └──────┬──────────┬─────────────┘
                                │          │
              ┌─────────────────▼──┐  ┌────▼──────────────────┐
              │    us-east-1       │  │    eu-west-1          │
              │                    │  │                        │
              │  API Gateway (WS)  │  │  API Gateway (WS)     │
              │  ECS (services)    │  │  ECS (services)       │
              │  Redis (drivers)   │  │  Redis (drivers)      │
              │  RDS (rides) ◄─────┼──┼─ RDS Read Replica     │
              │  (PRIMARY)         │  │                        │
              │  Kinesis (events)  │  │  Kinesis (events)     │
              │  DynamoDB Global   │──┼─ DynamoDB Global      │
              │  Table (history)   │  │  Table (history)      │
              └────────────────────┘  └────────────────────────┘
```

### Key Multi-Region Decisions

| Decision | Choice | Reason |
|----------|--------|--------|
| Driver locations | **Regional only** | Drivers are physical -- no need to replicate NYC drivers to London |
| Ride state (RDS) | **Single-region primary** | CP requirement. Cross-region writes too slow for real-time matching |
| Trip history | **DynamoDB Global Tables** | Eventually consistent, riders travel across regions |
| Payments | **Regional with global ledger** | Process locally, reconcile globally (async) |
| Surge pricing | **Regional** | Demand/supply is inherently local |
| User profiles | **DynamoDB Global Tables** | Users travel, need profile in any region |

---

## Cost Estimation at Scale (1M Daily Rides)

### Assumptions

```
Daily rides:              1,000,000
Active drivers:           200,000 (sending GPS every 4 seconds)
Active riders:            500,000 (requesting rides, tracking)
Location updates/sec:     200,000 / 4 = 50,000 updates/sec
Peak multiplier:          3x (rush hour) = 150,000 updates/sec
Ride requests/sec:        1,000,000 / 86,400 * 3 (peak) ~ 35 req/sec
WebSocket connections:    700,000 concurrent (drivers + active riders)
```

### Monthly Cost Breakdown

| Resource | Spec | Monthly Cost |
|----------|------|-------------|
| **API Gateway (WebSocket)** | 700K connections, 50B messages/month | ~$15,000 |
| **ECS/Fargate (services)** | 20 tasks, 4 vCPU / 8 GB each | ~$8,000 |
| **ElastiCache Redis** | 3 shards, r6g.xlarge, 1 replica each | ~$6,000 |
| **RDS PostgreSQL (PostGIS)** | db.r6g.2xlarge, Multi-AZ, 1 read replica | ~$4,500 |
| **Kinesis Data Streams** | 50 shards (150K records/sec peak) | ~$3,600 |
| **DynamoDB (trip history)** | 5K WCU, 10K RCU (on-demand) | ~$3,000 |
| **S3 (receipts, logs)** | 5 TB storage, 50M requests/month | ~$200 |
| **CloudWatch + X-Ray** | Detailed metrics, tracing, dashboards | ~$500 |
| **SNS + SQS (notifications)** | 50M notifications/month | ~$300 |
| **NAT Gateway + data transfer** | Cross-AZ, internet egress | ~$2,000 |
| **Total** | | **~$43,000/month** |

### Cost Optimization Strategies

1. **Reserved Instances** -- 1-year for RDS and ElastiCache saves 30-40%
2. **Spot instances for ECS** -- non-critical tasks (analytics, ML) on Spot saves 60-70%
3. **Kinesis vs Kafka (MSK)** -- MSK is cheaper above ~100 shards; Kinesis cheaper below
4. **DynamoDB reserved capacity** -- predictable trip volume, save 50-75%
5. **Redis connection pooling** -- fewer connections = smaller instance needed
6. **Location update batching** -- batch 3-5 GPS points per message, reduce Kinesis costs 3-5x
7. **WebSocket idle timeout** -- disconnect idle riders after 5 min, reconnect on activity

### Cost at Different Scales

| Scale | Daily Rides | Monthly Cost | Cost/Ride |
|-------|-------------|-------------|-----------|
| Startup | 10K | ~$3,000 | $0.30 |
| Growth | 100K | ~$12,000 | $0.12 |
| Scale | 1M | ~$43,000 | $0.043 |
| Enterprise | 10M | ~$250,000 | $0.025 |

---

## Interview Tip

> "For a ride-sharing system on AWS, the critical path is: **API Gateway WebSocket** for persistent driver connections, **ElastiCache Redis with GEO commands** for O(log N + K) nearest-driver queries, **RDS PostGIS** for ride state with CP guarantees, and **Kinesis** for the firehose of 50K location updates per second. Driver locations are AP -- stale by 3-4 seconds is fine. Ride state is CP -- no double-booking a driver. I'd use H3 hexagonal grid for surge zones, just like Uber does."

This shows you understand **spatial data, CAP tradeoffs per component, and real-time streaming** -- the three pillars of ride-sharing infrastructure.

---

## Quick Reference: Which Service When

| Decision Point | Service | Config | Why |
|---------------|---------|--------|-----|
| Nearest driver query | ElastiCache Redis GEO | GEOSEARCH BYRADIUS 5km | O(log N + K), sub-ms |
| Driver location stream | Kinesis (or MSK) | 50 shards, shard key = driver_id | Ordered per driver, 150K/sec |
| Ride state machine | RDS PostgreSQL | Multi-AZ, row-level locking | CP, ACID transactions |
| Trip history | DynamoDB | Partition key = rider_id, sort key = timestamp | Append-only, infinite scale |
| Real-time push | API Gateway WebSocket | Persistent connections | Sub-100ms latency |
| Surge pricing | ElastiCache Redis | Key = zone_id, TTL = 30s | Recalculated every 30s |
| Push notifications | SNS (mobile) | Platform-specific ARNs | iOS APNs + Android FCM |
