# E-Commerce System (Amazon) -- Cloud Service Mapping

## Component-to-Service Mapping

| Our Component | AWS | Azure | GCP | Notes |
|---------------|-----|-------|-----|-------|
| **API Gateway** | API Gateway (REST/HTTP) + CloudFront | API Management + Front Door | Cloud Endpoints + Cloud CDN | Rate limiting, auth, request routing |
| **Product Catalog Service** | ECS/EKS (Fargate) | AKS | GKE | Stateless, read-heavy, cacheable |
| **Order Service** | ECS/EKS (Fargate) | AKS | GKE | Write-heavy, saga orchestrator |
| **Inventory Service** | ECS/EKS (Fargate) | AKS | GKE | Synchronized reserve/confirm/release |
| **Payment Service** | ECS/EKS (Fargate) | AKS | GKE | Idempotent, strategy pattern for providers |
| **Cart Service** | ECS/EKS (Fargate) + DynamoDB | AKS + Cosmos DB | GKE + Firestore | Session-based, DynamoDB for fast R/W |
| **Search Service** | OpenSearch (Elasticsearch) | Cognitive Search | Vertex AI Search | Full-text product search, faceted |
| **Notification Service** | SNS + SES (email) + Pinpoint (push) | Notification Hubs + SendGrid | Firebase Cloud Messaging + SendGrid | Order confirmations, shipping updates |
| **Saga Orchestrator** | Step Functions | Durable Functions (Logic Apps) | Workflows | Distributed transaction coordination |
| **Serverless Workers** | Lambda | Azure Functions | Cloud Functions | Event-driven: resize images, send emails |
| **Relational DB (orders, users)** | RDS Aurora (MySQL/PostgreSQL) | Azure SQL | Cloud SQL / AlloyDB | ACID for orders, payments, users |
| **NoSQL (catalog, cart)** | DynamoDB | Cosmos DB | Bigtable / Firestore | High-throughput reads, flexible schema |
| **Cache** | ElastiCache Redis | Azure Cache for Redis | Memorystore | Product cache, session store, inventory hot count |
| **Message Queue** | SQS (point-to-point) + SNS (pub/sub) | Service Bus + Event Grid | Pub/Sub + Cloud Tasks | Order events, async processing |
| **Event Streaming** | Kinesis Data Streams / MSK (Kafka) | Event Hubs / Kafka | Pub/Sub / Confluent | Real-time order pipeline, analytics |
| **Object Storage** | S3 | Blob Storage | Cloud Storage | Product images, invoices, reports |
| **CDN** | CloudFront | Azure CDN / Front Door | Cloud CDN | Static assets, product images, catalog pages |
| **Monitoring** | CloudWatch + X-Ray | Azure Monitor + App Insights | Cloud Monitoring + Trace | Latency p50/p99, order success rate, saga failures |
| **DNS** | Route 53 (latency-based) | Traffic Manager | Cloud DNS | Multi-region routing |

---

## Microservices Deployment on AWS (Numbered Architecture)

```
Customer browses catalog, adds to cart, places order
    |
    1. HTTPS request (browse/cart/checkout)
    |
    v
+---------------------------------------------------------------+
|              CloudFront (CDN)                                  |
|   Static assets: product images, CSS/JS                       |
|   API caching: GET /products (TTL 60s)                        |
+---------------------------+-----------------------------------+
                            |
    2. Dynamic requests -> API Gateway
                            |
                            v
+---------------------------------------------------------------+
|              API Gateway (REST)                                |
|   Auth: Cognito JWT validation                                |
|   Rate limit: 100 req/sec per user                            |
|   Routes: /products, /cart, /orders, /payments                |
+------+--------+--------+--------+--------+-------------------+
       |        |        |        |        |
  3a. Product  3b. Cart  3c. Order 3d. Pay  3e. Search
       |        |        |        |        |
       v        v        v        v        v
+----------+ +-------+ +--------+ +------+ +------------+
| Product  | | Cart  | | Order  | | Pay  | | OpenSearch |
| Service  | | Svc   | | Svc    | | Svc  | | (Search)   |
| (ECS)    | | (ECS) | | (ECS)  | | (ECS)| | Cluster    |
+----+-----+ +---+---+ +---+----+ +--+---+ +------------+
     |            |         |         |
     |    4a. DynamoDB  4b. Step Functions (saga)
     |        (cart)        |
     |                 +----+----+
     |                 | Saga:   |
     |                 | 5a. Reserve Inventory (Lambda/ECS)
     |                 | 5b. Process Payment   (Lambda/ECS)
     |                 | 5c. Create Shipment   (Lambda/ECS)
     |                 | On failure: compensate in reverse
     |                 +---------+
     |                           |
     v                           v
+----------+  +----------+  +----------+  +----------+
| RDS      |  | RDS      |  | DynamoDB |  | SQS      |
| Aurora   |  | Aurora   |  | (invent- |  | (order   |
| (product |  | (order   |  |  ory     |  |  events) |
|  catalog)|  |  master) |  |  counts) |  |          |
+----------+  +----------+  +----------+  +----+-----+
                                               |
    6. Order events published to SNS/SQS
       |               |              |
       v               v              v
  +----------+   +-----------+   +----------+
  | Lambda:  |   | Lambda:   |   | Kinesis  |
  | Send     |   | Update    |   | (real-   |
  | email    |   | search    |   |  time    |
  | (SES)    |   | index     |   |  analytics)
  +----------+   +-----------+   +----------+
                                      |
    7. Kinesis -> analytics dashboard (order metrics, revenue)
```

---

## AWS Step Functions for Saga Orchestration

### Why Step Functions for E-Commerce Sagas?

```
The checkout flow is a distributed transaction across 3+ services:
  1. Inventory Service: reserve stock
  2. Payment Service: charge customer
  3. Shipping Service: create shipment

If payment fails AFTER inventory is reserved, we MUST compensate
(release the reserved stock). This is the Saga pattern.

Step Functions vs Manual Saga:
  - Step Functions: visual workflow, automatic retries, built-in error handling
  - Manual (choreography): events between services, each service listens and reacts
  - Step Functions wins for orchestration-style sagas (centralized control)
```

### Step Functions Workflow (Numbered)

```
Order placed by customer
    |
    1. API Gateway -> Order Service -> Start Step Functions execution
       Input: { orderId, userId, items: [{productId, qty, price}], paymentMethod }
    |
    v
+===================================================================+
|                   Step Functions State Machine                      |
|                                                                    |
|   2. ReserveInventory (Task State)                                |
|      Lambda: POST /inventory/reserve                              |
|      Input:  { orderId, items }                                   |
|      Output: { reservationId, reservedItems }                     |
|      Retry:  3 attempts, exponential backoff (1s, 2s, 4s)        |
|      Catch:  -> CompensateInventory (if all retries fail)         |
|          |                                                         |
|          v                                                         |
|   3. ProcessPayment (Task State)                                  |
|      Lambda: POST /payments/charge                                |
|      Input:  { orderId, userId, amount, paymentMethod, idempKey } |
|      Output: { paymentId, status: "CAPTURED" }                    |
|      Retry:  2 attempts, 5s backoff                               |
|      Catch:  -> CompensatePayment                                 |
|          |                                                         |
|          v                                                         |
|   4. CreateShipment (Task State)                                  |
|      Lambda: POST /shipping/create                                |
|      Input:  { orderId, items, shippingAddress }                  |
|      Output: { shipmentId, estimatedDelivery }                    |
|      Retry:  3 attempts                                           |
|      Catch:  -> CompensateShipment                                |
|          |                                                         |
|          v                                                         |
|   5. ConfirmOrder (Task State)                                    |
|      Lambda: PUT /orders/{orderId}/status = CONFIRMED             |
|      Publish SNS: "order.confirmed"                               |
|          |                                                         |
|          v                                                         |
|      SUCCESS                                                       |
|                                                                    |
|   ====== COMPENSATION BRANCH (reverse order) ======               |
|                                                                    |
|   CompensateShipment:                                             |
|      Lambda: DELETE /shipping/{shipmentId}                        |
|          |                                                         |
|          v                                                         |
|   CompensatePayment:                                              |
|      Lambda: POST /payments/refund                                |
|      Input: { paymentId, orderId }                                |
|          |                                                         |
|          v                                                         |
|   CompensateInventory:                                            |
|      Lambda: POST /inventory/release                              |
|      Input: { reservationId }                                     |
|          |                                                         |
|          v                                                         |
|   MarkOrderFailed:                                                |
|      Lambda: PUT /orders/{orderId}/status = FAILED                |
|      Publish SNS: "order.failed"                                  |
|          |                                                         |
|          v                                                         |
|      FAILURE (with compensation complete)                          |
+===================================================================+
```

### Step Functions State Machine Definition (Simplified)

```json
{
  "StartAt": "ReserveInventory",
  "States": {
    "ReserveInventory": {
      "Type": "Task",
      "Resource": "arn:aws:lambda:us-east-1:123:function:reserveInventory",
      "Retry": [{"ErrorEquals": ["TransientError"], "MaxAttempts": 3, "BackoffRate": 2}],
      "Catch": [{"ErrorEquals": ["States.ALL"], "Next": "CompensateInventory"}],
      "Next": "ProcessPayment"
    },
    "ProcessPayment": {
      "Type": "Task",
      "Resource": "arn:aws:lambda:us-east-1:123:function:processPayment",
      "Retry": [{"ErrorEquals": ["TransientError"], "MaxAttempts": 2, "BackoffRate": 2}],
      "Catch": [{"ErrorEquals": ["States.ALL"], "Next": "ReleaseInventory"}],
      "Next": "CreateShipment"
    },
    "CreateShipment": {
      "Type": "Task",
      "Resource": "arn:aws:lambda:us-east-1:123:function:createShipment",
      "Catch": [{"ErrorEquals": ["States.ALL"], "Next": "RefundPayment"}],
      "Next": "ConfirmOrder"
    },
    "ConfirmOrder": {
      "Type": "Task",
      "Resource": "arn:aws:lambda:us-east-1:123:function:confirmOrder",
      "End": true
    },
    "RefundPayment": {
      "Type": "Task",
      "Resource": "arn:aws:lambda:us-east-1:123:function:refundPayment",
      "Next": "ReleaseInventory"
    },
    "ReleaseInventory": {
      "Type": "Task",
      "Resource": "arn:aws:lambda:us-east-1:123:function:releaseInventory",
      "Next": "MarkOrderFailed"
    },
    "CompensateInventory": {
      "Type": "Task",
      "Resource": "arn:aws:lambda:us-east-1:123:function:releaseInventory",
      "Next": "MarkOrderFailed"
    },
    "MarkOrderFailed": {
      "Type": "Task",
      "Resource": "arn:aws:lambda:us-east-1:123:function:markOrderFailed",
      "End": true
    }
  }
}
```

---

## Cost Estimation at Scale (100M Orders/Day)

### Assumptions

```
Daily orders:            100,000,000 (100M)
Average items per order: 2.5
Daily catalog views:     5,000,000,000 (5B page views)
Peak multiplier:         5x (Black Friday)
Peak order TPS:          100M / 86400 * 5 = ~5,800 orders/sec
Peak catalog QPS:        5B / 86400 * 5 = ~290,000 req/sec
Product catalog size:    500M products
Cart sessions/day:       300M (3x orders, many carts are abandoned)
Cache hit rate (catalog): 95% (products rarely change)
Average order value:     $45
```

### Monthly Cost Breakdown

| Resource | Spec | Monthly Cost |
|----------|------|-------------|
| **CloudFront (CDN)** | 5B requests/day, images + API caching | ~$45,000 |
| **API Gateway** | 5B requests/day (after CDN), REST + WebSocket | ~$35,000 |
| **ECS/Fargate (all services)** | 200 tasks total, 4 vCPU / 8 GB avg | ~$80,000 |
| **RDS Aurora (orders + users)** | Multi-AZ, db.r6g.4xlarge, 3 read replicas | ~$25,000 |
| **RDS Aurora (payments)** | Separate cluster, db.r6g.2xlarge, Multi-AZ | ~$12,000 |
| **DynamoDB (catalog + cart)** | 500K RCU, 100K WCU (on-demand) | ~$60,000 |
| **ElastiCache Redis** | 10 shards, r6g.2xlarge, 1 replica each | ~$30,000 |
| **OpenSearch (product search)** | 10 data nodes, r6g.2xlarge, 3 master | ~$25,000 |
| **Step Functions (saga)** | 100M executions/month, ~5 transitions each | ~$12,500 |
| **Lambda (saga steps)** | 500M invocations/month, 256MB, 200ms avg | ~$15,000 |
| **SQS/SNS (events)** | 1B messages/day (order events, notifications) | ~$12,000 |
| **Kinesis (analytics stream)** | 50 shards, 100M records/day | ~$4,000 |
| **S3 (images, invoices, logs)** | 100 TB storage, 500M requests/month | ~$5,000 |
| **CloudWatch + X-Ray** | Metrics, traces, dashboards, alarms | ~$5,000 |
| **Data transfer** | Cross-AZ, NAT, internet egress | ~$25,000 |
| **Total** | | **~$390,500/month** |

### Cost Optimization Strategies

1. **Reserved Instances** -- 1-year RI for RDS, ElastiCache saves 30-40% (~$25K/month saved)
2. **DynamoDB reserved capacity** -- Provisioned mode + reserved for predictable workloads saves 50%+
3. **Spot instances** -- ECS Spot for non-critical workers (email, analytics) saves 60-70%
4. **CDN caching** -- Cache product pages at CloudFront, reduce API Gateway + ECS load by 60%
5. **Step Functions Express** -- Use Express Workflows for short sagas (< 5 min), 80% cheaper per execution
6. **Lambda ARM (Graviton)** -- 20% cheaper, 20% faster than x86 for saga steps
7. **S3 Intelligent-Tiering** -- Auto-move old images/invoices to cheaper tiers
8. **Request collapsing** -- Identical product lookups share a single backend call

### Cost at Different Scales

| Scale | Daily Orders | Monthly Cost | Cost/Order |
|-------|-------------|-------------|-----------|
| Startup | 10K | ~$5,000 | $0.017 |
| Growth | 1M | ~$25,000 | $0.0008 |
| Scale | 10M | ~$95,000 | $0.0003 |
| Amazon-scale | 100M | ~$390,500 | $0.00013 |

---

## Black Friday Architecture

### The Challenge

```
Normal day:    100M orders/day  ->  ~1,200 orders/sec
Black Friday:  500M orders/day  ->  ~5,800 orders/sec (sustained)
Flash sale:    10x spike        -> ~58,000 orders/sec (30-second burst)

Failures that MUST NOT happen:
  1. Overselling (selling more than available stock)
  2. Double-charging (payment processed twice)
  3. Site goes down (lost revenue = $1M+ per minute)
  4. Cart disappears (user rage)
```

### Black Friday Architecture (Numbered)

```
    === PRE-WARMING (1-2 days before) ===

    1. Scale ECS tasks from 200 -> 500 (pre-warm, don't wait for auto-scale)
    2. Scale RDS read replicas from 3 -> 8
    3. Scale ElastiCache from 10 -> 20 shards
    4. Pre-warm DynamoDB: increase WCU from 100K -> 500K
    5. Pre-warm API Gateway: request AWS support to raise account limits
    6. Pre-populate CDN cache: crawl top 10K product pages, warm CloudFront
    7. Pre-populate Redis cache: load top 100K products into ElastiCache

    === QUEUE-BASED CHECKOUT ===

    Customer clicks "Place Order"
        |
        8. API Gateway -> Order Service
        |
        v
    +-----------------------------------------------+
    |  Order Service (ECS)                          |
    |                                                |
    |  if (currentQPS > threshold) {                |
    |    // Queue-based checkout: don't process now |
    |    9a. Write order to SQS (checkout queue)    |
    |    9b. Return HTTP 202 Accepted               |
    |        { "message": "Order queued",           |
    |          "estimatedWait": "2 min",            |
    |          "trackingUrl": "/orders/ORD-123/status" }
    |  } else {                                     |
    |    // Normal checkout: process immediately    |
    |    9c. Start Step Functions saga              |
    |  }                                            |
    +------------------+----------------------------+
                       |
        10. SQS checkout queue (FIFO)
            Visibility timeout: 5 min
            Max receive count: 3
            DLQ for poison messages
                       |
                       v
    +-----------------------------------------------+
    |  Checkout Workers (ECS, auto-scaled)          |
    |  Pull from SQS at controlled rate             |
    |  11. For each message:                        |
    |      Start Step Functions saga                |
    |      (reserve -> pay -> ship -> confirm)      |
    +-----------------------------------------------+

    === FLASH SALE: ATOMIC STOCK DECREMENT ===

    Flash sale item: 1,000 units of PS5
        |
        12. Stock counter in Redis (not DynamoDB for speed):
            SET flash:PS5:stock 1000
        |
        v
    +-----------------------------------------------+
    |  Inventory Service (flash sale mode)          |
    |                                                |
    |  13. DECR flash:PS5:stock                     |
    |      (Redis DECR is atomic, single-threaded)  |
    |                                                |
    |  if (result >= 0) {                           |
    |    14a. Reserve granted!                      |
    |         Proceed to payment                    |
    |  } else {                                     |
    |    14b. INCR flash:PS5:stock  (undo)          |
    |         Return HTTP 503: "SOLD OUT"           |
    |         Show "sold out" page immediately      |
    |  }                                            |
    +-----------------------------------------------+
```

### Auto-Scaling Configuration

```
ECS Auto-Scaling (Order Service):
  Normal:      50 tasks,  target CPU 60%
  Pre-warmed:  150 tasks, target CPU 50%  (lower threshold = faster response)
  Black Friday: min=150, max=500, scale-out cooldown=60s, scale-in cooldown=300s

RDS Auto-Scaling (read replicas):
  Normal:      3 read replicas
  Black Friday: 8 read replicas, auto-add at 70% CPU

ElastiCache Auto-Scaling:
  Normal:      10 shards
  Pre-warmed:  20 shards (scale shards BEFORE traffic hits -- resharding takes time)

DynamoDB Auto-Scaling:
  Normal:      On-demand (auto-scales, but has warm-up delay)
  Black Friday: Provisioned + pre-warmed to 500K WCU (avoids throttling during ramp)

API Gateway Throttling:
  Normal:      10,000 req/sec account limit
  Black Friday: Request limit increase to 50,000 req/sec (submit AWS support ticket 2 weeks prior)

Queue-Based Checkout Activation:
  Threshold:   Order QPS > 3,000 (normal peak is ~1,200)
  Action:      Switch to queue-based checkout automatically
  Recovery:    When QPS drops below 1,500 for 5 min, switch back to synchronous
```

### Flash Sale Circuit Breaker

```
Flash sale for "PS5" -- 1,000 units

Timeline:
  T+0s:    Sale starts. 50,000 concurrent users hit "Buy Now".
  T+0.1s:  Redis DECR processes ~10,000 requests. 1,000 succeed, 9,000 get SOLD OUT.
  T+0.2s:  Circuit breaker OPENS: all new requests for PS5 return 503 immediately.
           (Don't even hit Redis -- save resources.)
  T+0.3s:  CDN edge caches the "sold out" response for PS5. TTL = 60s.
           40,000 remaining users get "sold out" from CDN, zero backend load.

Circuit breaker states:
  CLOSED:   Normal operation. All requests go through.
  OPEN:     Item sold out. Return 503 instantly. Check Redis every 10s
            (in case of cancellations that release stock).
  HALF-OPEN: A cancellation freed 5 units. Allow 5 requests through.
             If they succeed, stay HALF-OPEN until stock = 0 again.
```

---

## Multi-Region E-Commerce

```
                         +-------------------------------+
                         |       Route 53 (DNS)          |
                         |  Latency-based routing        |
                         |  US users  -> us-east-1       |
                         |  EU users  -> eu-west-1       |
                         |  APAC users -> ap-southeast-1 |
                         +------+---------------+-------+
                                |               |
              +-----------------v--+   +--------v-----------------+
              |    us-east-1       |   |    eu-west-1             |
              |    (PRIMARY)       |   |    (SECONDARY)           |
              |                    |   |                          |
              |  CloudFront (CDN)  |   |  CloudFront (CDN)       |
              |  API Gateway       |   |  API Gateway             |
              |  ECS (all svcs)    |   |  ECS (all svcs)         |
              |  ElastiCache Redis |   |  ElastiCache Redis      |
              |  OpenSearch        |   |  OpenSearch              |
              |                    |   |                          |
              |  RDS Aurora Global |   |  RDS Aurora Global      |
              |  (Primary Writer)  |   |  (Read Replica)         |
              |                    |   |                          |
              |  DynamoDB Global   |   |  DynamoDB Global        |
              |  Tables (multi-    |   |  Tables (multi-         |
              |   master writes)   |   |   master writes)        |
              |                    |   |                          |
              |  SQS (regional)    |   |  SQS (regional)         |
              |  Step Functions    |   |  Step Functions          |
              |  (regional sagas)  |   |  (regional sagas)       |
              +--------------------+   +--------------------------+
```

### Multi-Region Decisions

| Decision | Choice | Reason |
|----------|--------|--------|
| Product catalog | **DynamoDB Global Tables (multi-master)** | Catalog reads must be local for low latency; writes are rare |
| Cart | **DynamoDB Global Tables** | User may switch devices/regions; cart must follow |
| Orders (write) | **Aurora Global Database (single primary writer)** | Orders need ACID; write to primary, replicate async |
| Orders (read) | **Aurora read replicas (per region)** | Order history reads are local; 1-2s replication lag OK |
| Inventory | **Single-region primary + cross-region reserve via API** | Cannot split inventory across regions (overselling risk) |
| Payments | **Regional (each region processes independently)** | Payment providers are regional; no cross-region dependency |
| Search index | **Regional OpenSearch cluster** | Catalog synced via DynamoDB Streams -> Lambda -> OpenSearch |
| Session / cache | **Regional ElastiCache (independent)** | Cache warms per-region traffic; no cross-region sync needed |

### Cross-Region Inventory Challenge

```
Problem: PS5 has 1,000 units globally. Three regions can all sell it.
         If each region tracks its own count, overselling is guaranteed.

Solution 1: Centralized inventory (single-region primary)
  - All inventory writes go to us-east-1
  - EU/APAC users have +50-100ms latency for inventory reserve
  - Simple, no overselling risk
  - Acceptable: reserve is one step in checkout, not user-facing latency

Solution 2: Partitioned inventory (allocate per region)
  - US: 500 units, EU: 300 units, APAC: 200 units
  - Each region manages its allocation independently
  - If EU sells out, request rebalance from US (async)
  - More complex, but lower latency for reserve step

This design uses Solution 1 (centralized) for simplicity.
The +50-100ms for cross-region inventory reserve is hidden
inside the saga -- user sees "order queued" immediately.
```

---

## Interview Tip

> "For an Amazon-scale e-commerce system on AWS, I'd use a **microservices architecture** on ECS with **API Gateway** for routing and **CloudFront** for static assets and catalog caching. The checkout flow is a **distributed saga** orchestrated by **Step Functions**: reserve inventory, process payment, create shipment -- with compensation steps in reverse on failure. **DynamoDB** for the product catalog and cart (high throughput, flexible schema), **Aurora** for orders and payments (ACID required). **ElastiCache Redis** caches hot products and holds flash-sale stock counters (DECR is atomic). For Black Friday: pre-warm all resources, switch to **queue-based checkout** via SQS when load exceeds thresholds, and use Redis atomic DECR for flash sales with a circuit breaker that returns 503 when sold out. Multi-region via **DynamoDB Global Tables** for catalog and **Aurora Global Database** for orders."

This shows you understand **saga orchestration, inventory consistency, idempotent payments, and Black Friday scaling** -- the four pillars of e-commerce infrastructure.

---

## Quick Reference: Which Service When

| Decision Point | Service | Config | Why |
|---------------|---------|--------|-----|
| Checkout saga | Step Functions (Standard) | 5 states, retry + catch, 5 min timeout | Visual workflow, built-in compensation |
| Flash sale stock | ElastiCache Redis (DECR) | Single key per item, atomic decrement | Sub-ms, single-threaded, no race conditions |
| Product catalog | DynamoDB + ElastiCache | GSI on category, cache TTL 60s | Millions of reads/sec, 95% cache hit |
| Order storage | RDS Aurora (PostgreSQL) | Multi-AZ, 3 read replicas | ACID for financial records, strong consistency |
| Cart storage | DynamoDB | Partition = userId, TTL = 30 days | Fast R/W, auto-expire abandoned carts |
| Product search | OpenSearch | 10 data nodes, keyword + category facets | Full-text search, filters, relevance ranking |
| Order events | SQS + SNS (fan-out) | SNS topic -> SQS queues per consumer | Decouple: email, analytics, search index update |
| Async processing | Lambda | 256 MB, 30s timeout, triggered by SQS/SNS | Email, image resize, search index sync |
| Queue-based checkout | SQS FIFO | Order grouping by userId, dedup by orderId | Backpressure during traffic spikes |
| Real-time analytics | Kinesis + Lambda/Firehose | 50 shards, Firehose -> S3 -> Athena | Order metrics, revenue dashboards |
