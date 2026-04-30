# Interview Walkthrough -- E-Commerce System (Amazon)

> **Total time: ~35 minutes. The Saga deep dive is 50% of this interview.**
> This problem tests distributed transactions (Saga pattern), inventory consistency (overselling prevention), idempotent payments, state machines, CQRS, and scaling for extreme traffic (Black Friday, flash sales). This is the most comprehensive system design problem -- it touches everything.

---

## Phase 1: Clarify Requirements (2-3 min)

### Questions to Ask

- "What's the scale? 1M orders/day or 100M? This determines whether we need microservices or a monolith."
- "Do we need real-time inventory or is eventual consistency acceptable? Can we briefly show 'in stock' for an item that just sold out?"
- "What payment methods? Credit card only, or PayPal, Apple Pay, etc.? This affects the payment service design."
- "Do we need to handle flash sales -- thousands of users buying the same item simultaneously?"
- "International? Multi-currency, multi-language, multi-region?"
- "What's the return/refund policy? Does the system need to handle reverse logistics?"

### Clarified Scope

```
In scope:   Product catalog, shopping cart, checkout (order placement),
            inventory management, payment processing, order state machine,
            saga orchestration, flash sale handling
Out of scope: Recommendation engine, reviews/ratings, seller portal,
              advertising, warehouse management, delivery tracking (mention only)
```

### What This Signals

You understand this is a **multi-service coordination problem** where the hard part is **distributed transactions** (not CRUD). You're probing for the consistency requirements that drive the architecture.

**Common follow-up:** "Why does the flash sale question matter?"

**Answer:** "Flash sales change the inventory design entirely. Normally, DynamoDB conditional writes handle concurrent purchases fine at 1,000 TPS. But in a flash sale, 50,000 users hit 'Buy Now' in the same second for a single item. I'd use Redis DECR instead -- it's atomic, single-threaded, and sub-millisecond. The data structure choice depends on the concurrency pattern."

---

## Phase 2: High-Level Architecture (5-7 min)

### What to Say

> "I'll design this as an event-driven microservices architecture with five core services: Product Catalog, Cart, Order, Inventory, and Payment. The checkout flow is a distributed saga -- reserve inventory, process payment, create shipment -- with compensation on failure. CQRS separates the read-heavy catalog (DynamoDB + cache, AP) from the write-heavy order flow (Aurora, CP). Events flow through SNS/SQS for decoupled downstream processing."

### Draw This Diagram

```
                  +---------------------------+
                  |        Customer           |
                  |  Browse -> Cart -> Order  |
                  +------------+--------------+
                               |
              1. HTTPS (browse / add-to-cart / checkout)
                               |
                  +------------v--------------+
                  |     CloudFront (CDN)       |
                  |  Static: images, CSS/JS   |
                  |  API: GET /products cached |
                  +------------+--------------+
                               |
              2. Dynamic -> API Gateway (auth, rate limit)
                               |
     +-----------+------+------+------+-----------+
     |           |      |      |      |           |
     v           v      v      v      v           v
+---------+ +------+ +------+ +-----+ +------+ +--------+
| Product | | Cart | | Order| | Pay | | Inv  | | Search |
| Catalog | | Svc  | | Svc  | | Svc | | Svc  | | (Open  |
| (ECS)   | | (ECS)| | (ECS)| |(ECS)| | (ECS)| | Search)|
+----+----+ +--+---+ +--+---+ +--+--+ +--+---+ +--------+
     |         |         |        |       |
     |    3. DynamoDB    |        |       |
     |       (cart)      |        |       |
     |                   |        |       |
     |         4. Checkout triggers Saga:
     |              +----+--------+-------+
     |              |    SAGA ORCHESTRATOR |
     |              | 5a. Reserve Inventory|----> Inventory Svc
     |              | 5b. Process Payment  |----> Payment Svc
     |              | 5c. Create Shipment  |----> Shipping Svc
     |              | 5d. Confirm Order    |
     |              | On fail: compensate  |
     |              +----------+-----------+
     |                         |
     v                         v
+---------+  +---------+  +---------+  +---------+
| DynamoDB|  | Aurora  |  | DynamoDB|  | Redis   |
| (catalog|  | (orders,|  | (inven- |  | (cache, |
|  + cart)|  |  users) |  |  tory)  |  |  flash  |
+---------+  +---------+  +---------+  |  stock) |
                                       +---------+
                  |
     6. Order events -> SNS -> SQS (fan-out)
              |            |           |
              v            v           v
         +---------+  +---------+  +----------+
         | Email   |  |Analytics|  | Search   |
         | (SES)   |  | (Kinesis|  | Index    |
         |         |  |  -> S3) |  | Update   |
         +---------+  +---------+  +----------+
```

### Components to Name

| Component | Role | CAP |
|-----------|------|-----|
| Product Catalog Service | Read-heavy, cached. DynamoDB + ElastiCache. Millions of reads/sec. | AP (stale price OK, re-validate at checkout) |
| Cart Service | DynamoDB per-user cart. Merge duplicates, price snapshot. TTL for abandoned carts. | AP (losing a cart item is annoying, not catastrophic) |
| Order Service | Creates orders, manages state machine. Aurora for ACID. Financial record of truth. | CP (orders must be consistent) |
| Inventory Service | Reserve/confirm/release. available = total - reserved. Synchronized. | CP (overselling is catastrophic) |
| Payment Service | Strategy pattern. Idempotency key = orderId. Never charge twice. | CP (double-charging is catastrophic) |
| Saga Orchestrator | Step Functions. Coordinates checkout: reserve -> pay -> ship. Compensates on failure. | CP (must complete or fully compensate) |

### What This Signals

You separate **AP services** (catalog, cart -- stale data is harmless) from **CP services** (inventory, payment, orders -- consistency is critical). This is the key architectural insight for e-commerce.

**Common follow-up:** "Why microservices instead of a monolith?"

**Answer:** "At 100M orders/day, different services have wildly different scaling needs. The catalog serves 50x more traffic than checkout. With microservices, I scale the catalog service to 100 instances while keeping the payment service at 20. In a monolith, I'd over-provision everything. Also, the catalog team can deploy independently of the payment team -- deployment velocity matters at Amazon's scale. But for a startup doing 10K orders/day, a monolith is the right choice -- microservices add operational complexity that isn't worth it below a certain scale."

---

## Phase 3: Saga Pattern Deep Dive (8-10 min)

**This is the star of the interview. Spend the most time here.**

### Part A: Why Sagas? The Distributed Transaction Problem

> "In a monolith, checkout is a single database transaction: BEGIN -> deduct inventory -> charge payment -> create order -> COMMIT. If anything fails, ROLLBACK. Simple. In microservices, each service has its own database -- there's no single transaction boundary. A 2-Phase Commit (2PC) across services is too slow and fragile. The Saga pattern replaces one ACID transaction with a sequence of local transactions, each with a compensating action."

```
Monolith (single transaction):
  BEGIN
    UPDATE inventory SET stock = stock - 1 WHERE product_id = 'PS5';
    INSERT INTO payments (order_id, amount) VALUES ('ORD-1', 499);
    INSERT INTO orders (id, status) VALUES ('ORD-1', 'CONFIRMED');
  COMMIT   <-- all or nothing, simple!

Microservices (no shared DB):
  Inventory DB    Payment DB    Order DB
  (DynamoDB)      (Aurora)      (Aurora)
       |               |             |
       +--- NO single COMMIT across these ---+

  Solution: Saga pattern
    Step 1: Inventory Service -> reserve stock    (local transaction)
    Step 2: Payment Service  -> charge customer   (local transaction)
    Step 3: Shipping Service -> create shipment   (local transaction)
    Step 4: Order Service    -> confirm order      (local transaction)

    If Step 2 fails:
      Compensate Step 1: release reserved stock   (compensating transaction)
      Mark order as FAILED
```

### Part B: Orchestration vs Choreography

> "There are two styles. Orchestration: a central coordinator (Step Functions) tells each service what to do and handles failures. Choreography: each service publishes events and other services react. For checkout, I prefer orchestration -- the flow is linear, compensation must happen in order, and I want centralized visibility for debugging failed orders."

```
ORCHESTRATION (this design):

  +---------------------------+
  |    Saga Orchestrator      |
  |    (Step Functions)       |
  |                           |
  |  1. Call: reserveInventory|---------> Inventory Service
  |     Wait for response     |<--------- { reservationId }
  |                           |
  |  2. Call: processPayment  |---------> Payment Service
  |     Wait for response     |<--------- { paymentId }
  |                           |
  |  3. Call: createShipment  |---------> Shipping Service
  |     Wait for response     |<--------- { shipmentId }
  |                           |
  |  4. Call: confirmOrder    |---------> Order Service
  |                           |
  |  On failure at step N:    |
  |  Compensate N-1, N-2, ...|           (reverse order)
  +---------------------------+

  Pros: Central visibility, easy to debug, clear compensation flow
  Cons: Single point of failure (mitigated by Step Functions durability)
        Orchestrator couples to all services

CHOREOGRAPHY (alternative):

  Order Service                Inventory Service
  publishes:                   listens for:
  "order.created"  ----------> "order.created"
                               reserves stock
                               publishes: "inventory.reserved"
                                          |
  Payment Service  <-----------------------+
  listens for:
  "inventory.reserved"
  charges payment
  publishes: "payment.captured"
                    |
  Shipping Service <+
  listens for:
  "payment.captured"
  creates shipment

  Pros: Fully decoupled, no single coordinator, scales independently
  Cons: Hard to debug (events scattered across services)
        Compensation is complex (who compensates when shipping fails?)
        No centralized view of saga state
```

### Part C: The Saga Happy Path (Numbered)

```
Customer clicks "Place Order"
    |
    1. Order Service creates order: status = CREATED
       Publishes to Saga Orchestrator (Step Functions)
       Input: { orderId: "ORD-123", items: [{PS5, qty:1}], paymentMethod: "visa_4242" }
    |
    v
    2. RESERVE INVENTORY
       Orchestrator calls Inventory Service:
         POST /inventory/reserve
         Body: { orderId: "ORD-123", items: [{productId: "PS5", qty: 1}] }

       Inventory Service:
         synchronized {
           available = total - reserved;     // available = 100 - 45 = 55
           if (available >= requestedQty) {  // 55 >= 1? YES
             reserved += requestedQty;       // reserved = 46
             return { reservationId: "RES-789", status: "RESERVED" };
           } else {
             throw InsufficientStockException;
           }
         }

       Order status: CREATED -> INVENTORY_RESERVED
    |
    v
    3. PROCESS PAYMENT
       Orchestrator calls Payment Service:
         POST /payments/charge
         Body: { orderId: "ORD-123", amount: 499.00,
                 paymentMethod: "visa_4242", idempotencyKey: "ORD-123" }

       Payment Service:
         // Check idempotency: has "ORD-123" been charged before?
         existing = db.findByIdempotencyKey("ORD-123");
         if (existing != null) return existing;  // already charged, return same result

         // New charge
         result = paymentGateway.charge(amount, paymentMethod);  // Stripe API call
         db.save({ idempotencyKey: "ORD-123", paymentId: "PAY-456", status: "CAPTURED" });
         return { paymentId: "PAY-456", status: "CAPTURED" };

       Order status: INVENTORY_RESERVED -> PAYMENT_CONFIRMED
    |
    v
    4. CREATE SHIPMENT
       Orchestrator calls Shipping Service:
         POST /shipping/create
         Body: { orderId: "ORD-123", items: [...], address: {...} }
         Response: { shipmentId: "SHIP-321", estimatedDelivery: "2024-04-01" }

       Order status: PAYMENT_CONFIRMED -> SHIPPED
    |
    v
    5. CONFIRM ORDER
       Orchestrator calls Order Service:
         PUT /orders/ORD-123/status
         Body: { status: "CONFIRMED", paymentId: "PAY-456", shipmentId: "SHIP-321" }

       Inventory Service: confirm(reservationId: "RES-789")
         // Convert reservation to permanent decrement
         // reserved -= qty; total -= qty;
         // (stock is now permanently reduced)

    6. Publish event: "order.confirmed" -> SNS
       Downstream consumers:
         - Email Service: send confirmation email
         - Analytics: update revenue dashboard
         - Search: update "X sold" badge on product page
```

### Part D: The Saga Failure Path (Compensation)

```
Scenario: Payment FAILS after inventory is already reserved.

    1. Order Service: status = CREATED
    |
    2. Reserve Inventory: SUCCESS (RES-789, stock reserved)
       Order status: CREATED -> INVENTORY_RESERVED
    |
    3. Process Payment: FAILED!
       Stripe returns: { error: "card_declined" }
    |
    v
    === COMPENSATION BEGINS (reverse order) ===
    |
    4. Compensate Step 2: RELEASE INVENTORY
       Orchestrator calls:
         POST /inventory/release
         Body: { reservationId: "RES-789" }

       Inventory Service:
         reserved -= qty;  // reserved = 46 - 1 = 45
         // Stock is available again for other customers
         return { status: "RELEASED" };
    |
    5. Mark Order as FAILED
       PUT /orders/ORD-123/status = FAILED
       Reason: "Payment declined"
    |
    6. Publish event: "order.failed" -> SNS
       - Email: "Sorry, your payment was declined."
       - Analytics: update failure metrics

    === COMPENSATION COMPLETE ===
    
    Key principle: Each saga step has EXACTLY ONE compensating action.
      reserve()  <-->  release()
      charge()   <-->  refund()
      ship()     <-->  cancelShipment()
      
    Compensation is NOT rollback -- it's a new forward transaction
    that semantically undoes the previous step.
```

### Part E: Saga Edge Cases

```
Q: What if the compensation itself fails?
A: Retry with exponential backoff. Compensation MUST eventually succeed.
   If it keeps failing, alert operations team + dead-letter queue.
   This is why compensation actions must be idempotent.

Q: What if the orchestrator crashes mid-saga?
A: Step Functions persists state. When it recovers, it resumes
   from the last completed step. This is why we use Step Functions
   instead of a hand-rolled orchestrator in application code.

Q: What about the "dual write" problem?
A: When Order Service updates its DB AND publishes an event,
   one can succeed while the other fails.
   Solution: Transactional outbox pattern.
     1. Write order + event to same DB in one transaction
     2. Separate poller reads outbox table, publishes to SNS
     3. Guarantees at-least-once delivery

Q: Can two sagas reserve the LAST item simultaneously?
A: No. The reserve() operation is synchronized (Java) or uses
   DynamoDB conditional write:
     UpdateItem WHERE available > 0
     SET reserved = reserved + 1
   Only one conditional write succeeds. The other gets
   ConditionalCheckFailedException -> return "out of stock".
```

**Common follow-up:** "When would you use choreography instead?"

**Answer:** "When the workflow is non-linear or highly parallel. For example, after an order is confirmed, I need to update the search index, send an email, update analytics, and trigger the recommendation engine -- all independently. That's choreography via SNS fan-out. But the core checkout flow (reserve -> pay -> ship) is linear and sequential, so orchestration is cleaner."

---

## Phase 4: Inventory & Payment Deep Dive (5-7 min)

### Inventory: Preventing Overselling

> "The key insight is the reserve/confirm/release pattern. Never directly decrement stock. Reserve first, confirm after payment succeeds, release if payment fails. This prevents both overselling and 'phantom stock' (stock decremented but payment never completed)."

```
Inventory States per Item:

  total = 100 (units in warehouse, set by warehouse system)
  reserved = 15 (units reserved by in-progress sagas)
  available = total - reserved = 85 (units customers can buy)

  === RESERVE (checkout starts) ===
  Customer A buys 2:
    available = 100 - 15 = 85 >= 2? YES
    reserved = 15 + 2 = 17
    available now = 100 - 17 = 83
    Return: reservationId = RES-001

  Customer B buys 1 (concurrent):
    available = 100 - 17 = 83 >= 1? YES
    reserved = 17 + 1 = 18
    Return: reservationId = RES-002

  === CONFIRM (payment succeeds) ===
  Customer A payment succeeds:
    confirm(RES-001):
      reserved = 18 - 2 = 16  (remove from reserved)
      total = 100 - 2 = 98    (permanently reduce)
      available = 98 - 16 = 82

  === RELEASE (payment fails) ===
  Customer B payment fails:
    release(RES-002):
      reserved = 16 - 1 = 15  (remove from reserved)
      total unchanged = 98
      available = 98 - 15 = 83  (stock is available again!)

  === RESERVATION EXPIRY ===
  If saga takes > 10 minutes (timeout), auto-release:
    release(reservationId) called by scheduler
    Prevents "phantom reservations" from holding stock forever
```

### DynamoDB Conditional Write for Inventory

```
Normal flow (not flash sale):

  DynamoDB UpdateItem:
    TableName: "inventory"
    Key: { productId: "PS5" }
    UpdateExpression: "SET reserved = reserved + :qty"
    ConditionExpression: "(total - reserved) >= :qty"
    ExpressionAttributeValues: { ":qty": 1 }

  If condition is true:  Update succeeds, reservation granted
  If condition is false: ConditionalCheckFailedException -> "out of stock"

  This is ATOMIC at the DynamoDB level. No application-level locking needed.
  DynamoDB handles concurrent conditional writes correctly.
```

### Redis DECR for Flash Sales

```
Flash sale: 1,000 PS5 units, 50,000 concurrent buyers

  Why not DynamoDB?
    DynamoDB conditional write: ~5ms per request
    50,000 requests: many will retry due to contention on same item
    Hot partition problem: all writes go to the same partition key
    DynamoDB adaptive capacity can't scale fast enough for a 1-second burst

  Why Redis DECR?
    Redis is single-threaded. DECR is atomic. No contention.
    Sub-millisecond per operation. 100K+ ops/sec on single key.

  Flow:
    1. Before sale: SET flash:PS5:stock 1000
    
    2. Each buyer:
       result = DECR flash:PS5:stock
       if result >= 0:
         // Reserved! Proceed to payment saga.
       else:
         // Sold out. Undo: INCR flash:PS5:stock
         // Return 503 "SOLD OUT"

    3. After all reserved:
       Sync final count back to DynamoDB (async)
       Each reservation enters the normal saga flow
       (payment might still fail -> INCR to release)

  Timeline (1,000 units, 50,000 buyers):
    T+0.0s:  50,000 DECR commands arrive
    T+0.1s:  1,000 get result >= 0 (reserved)
             49,000 get result < 0 (sold out, INCR back)
    T+0.2s:  Circuit breaker OPENS for PS5
             All subsequent requests: 503 from application, no Redis call
    T+0.3s:  CDN caches "sold out" response, TTL = 60s
             Zero backend load for PS5
```

### Payment: Idempotency

> "Every payment request carries an idempotency key -- in our case, the orderId. If the network fails after charging but before the response reaches us, we'll retry. The payment service checks: 'Have I processed this idempotency key before?' If yes, return the original result. If no, process and store."

```
Payment Idempotency Flow:

  Request 1 (original):
    POST /payments/charge
    Body: { orderId: "ORD-123", amount: 499.00, idempotencyKey: "ORD-123" }

    Payment Service:
      1. SELECT * FROM payments WHERE idempotency_key = 'ORD-123';
         Result: empty (first time)
      2. Call Stripe: stripe.charges.create({ amount: 49900, ... })
         Stripe returns: { id: "ch_abc123", status: "succeeded" }
      3. INSERT INTO payments (idempotency_key, payment_id, status, amount)
         VALUES ('ORD-123', 'PAY-456', 'CAPTURED', 499.00);
      4. Return: { paymentId: "PAY-456", status: "CAPTURED" }

  Request 2 (retry, network failed on response):
    POST /payments/charge
    Body: { orderId: "ORD-123", amount: 499.00, idempotencyKey: "ORD-123" }

    Payment Service:
      1. SELECT * FROM payments WHERE idempotency_key = 'ORD-123';
         Result: { paymentId: "PAY-456", status: "CAPTURED" }  (found!)
      2. Skip Stripe call. Return stored result.
      4. Return: { paymentId: "PAY-456", status: "CAPTURED" }  (same as original!)

  Customer is charged EXACTLY ONCE regardless of retries.

Payment Strategy Pattern:
  interface PaymentStrategy {
    PaymentResult charge(String orderId, BigDecimal amount);
    PaymentResult refund(String paymentId);
  }

  class CreditCardStrategy implements PaymentStrategy { ... }  // Stripe
  class PayPalStrategy implements PaymentStrategy { ... }       // PayPal SDK
  class ApplePayStrategy implements PaymentStrategy { ... }     // Apple Pay

  // Selected at runtime based on customer's choice:
  PaymentStrategy strategy = factory.create(paymentMethod);
  PaymentResult result = strategy.charge(orderId, amount);
```

**Common follow-up:** "What if the DB insert (step 3) fails after Stripe charges?"

**Answer:** "This is the crash-between-charge-and-persist problem. The customer is charged but we have no record. Two solutions: (1) Stripe's own idempotency keys -- pass our orderId to Stripe, and if we retry, Stripe returns the original charge. (2) Reconciliation job: every hour, compare our payments table with Stripe's charge list. Any charge in Stripe but not in our DB gets back-filled. In practice, Stripe's idempotency handles 99.9% of cases."

---

## Phase 5: Scaling & Edge Cases (5-8 min)

### Black Friday Architecture

> "Black Friday is 5x normal traffic sustained for 12 hours, with 10x spikes during flash sales. The three pillars: pre-warming (scale resources BEFORE traffic hits), queue-based checkout (backpressure when overloaded), and circuit breakers (fast-fail for sold-out items)."

```
Black Friday Timeline:

  T-48h:  PRE-WARM
    - Scale ECS: 200 -> 500 tasks (don't rely on auto-scale lag)
    - Scale DynamoDB: on-demand -> provisioned 500K WCU
    - Scale ElastiCache: 10 -> 20 shards
    - Scale RDS: add 5 more read replicas
    - Pre-populate CDN: crawl top 10K product pages
    - Pre-populate Redis: load top 100K products
    - Load test at 2x expected peak (verify no bottlenecks)

  T-0:    BLACK FRIDAY STARTS
    - Traffic ramps from 1x to 3x in first hour
    - Auto-scaling handles gradual ramp (pre-warmed baseline absorbs initial spike)

  T+2h:   FLASH SALE STARTS (PS5 at 50% off)
    - Traffic spikes to 10x on PS5 product page
    - CDN absorbs 95% of product page views (cached)
    - 50,000 users click "Buy Now" in 10 seconds

    Queue-based checkout activates:
    +--------------------------------------------------+
    | if (orderQPS > 3000) {                           |
    |   // Queue mode: absorb spike, drain at safe rate|
    |   sqs.sendMessage(checkoutQueue, orderPayload);  |
    |   return HTTP 202 "Order queued, ~2 min wait";   |
    | } else {                                         |
    |   // Normal mode: process immediately            |
    |   saga.execute(order);                           |
    |   return HTTP 200 "Order confirmed";             |
    | }                                                |
    +--------------------------------------------------+

    Checkout workers drain queue at 2,000 orders/sec (controlled rate)
    Users see: "Your order is being processed..." with a progress bar
    Average wait: 30 seconds (acceptable for Black Friday)

  T+2h 10s:  PS5 SOLD OUT
    - Redis DECR returns negative -> SOLD OUT
    - Circuit breaker OPENS for PS5
    - CDN caches "sold out" page for 60 seconds
    - 49,000 disappointed users, 1,000 happy ones, 0 oversold

  T+12h:  BLACK FRIDAY ENDS
    - Drain remaining SQS messages
    - Scale down over 2 hours (gradual, not sudden)
    - Run reconciliation: compare inventory DB vs payment DB vs shipping DB
    - Generate Black Friday report: orders, revenue, failures, compensation count
```

### Order State Machine

```
Order States and Valid Transitions:

  CREATED -----(inventory reserved)-----> INVENTORY_RESERVED
  INVENTORY_RESERVED --(payment OK)-----> PAYMENT_CONFIRMED
  PAYMENT_CONFIRMED ---(shipped)--------> SHIPPED
  SHIPPED -----------(delivered)--------> DELIVERED

  Failure paths:
  CREATED -----(inventory failed)-------> FAILED
  INVENTORY_RESERVED --(payment fail)---> FAILED  (+ release inventory)
  PAYMENT_CONFIRMED ---(ship fail)------> FAILED  (+ refund + release)

  Cancellation:
  CREATED -------(user cancels)---------> CANCELLED
  INVENTORY_RESERVED -(user cancels)----> CANCELLED (+ release)
  PAYMENT_CONFIRMED --(user cancels)----> CANCELLED (+ refund + release)
  SHIPPED ---------(user cancels)-------> Not allowed! (ship already in transit)

  State machine enforces:
    - No skipping states (CREATED -> SHIPPED is INVALID)
    - No backward transitions (SHIPPED -> RESERVED is INVALID)
    - Guards on each transition (e.g., can only SHIP if payment confirmed)
    - Event emitted on every transition (for downstream consumers)

  Implementation:
    class OrderStateMachine {
      private static final Map<OrderStatus, Set<OrderStatus>> VALID_TRANSITIONS = Map.of(
        CREATED,              Set.of(INVENTORY_RESERVED, FAILED, CANCELLED),
        INVENTORY_RESERVED,   Set.of(PAYMENT_CONFIRMED, FAILED, CANCELLED),
        PAYMENT_CONFIRMED,    Set.of(SHIPPED, FAILED, CANCELLED),
        SHIPPED,              Set.of(DELIVERED),
        DELIVERED,            Set.of(),  // terminal
        FAILED,               Set.of(),  // terminal
        CANCELLED,            Set.of()   // terminal
      );

      public OrderStatus transition(OrderStatus current, OrderStatus next) {
        if (!VALID_TRANSITIONS.get(current).contains(next)) {
          throw new IllegalStateException(current + " -> " + next + " is invalid");
        }
        return next;  // + emit event
      }
    }
```

### CQRS: Read vs Write Path

```
READ PATH (Catalog Browsing):
  Customer searches "laptop" or browses "Electronics"
    |
    1. CDN (CloudFront): cached product pages, TTL = 60s
       HIT rate: 80% for popular categories
    |
    2. ElastiCache Redis: product detail cache, TTL = 60s
       HIT rate: 95%
    |
    3. DynamoDB (catalog): GSI on category, full product data
       Only 5% of requests reach here (after CDN + Redis miss)

  Characteristics:
    - 50x more traffic than write path
    - Stale by 60 seconds is fine (AP)
    - Scales horizontally: add more Redis shards, DynamoDB auto-scales reads

WRITE PATH (Place Order):
  Customer clicks "Place Order"
    |
    1. API Gateway -> Order Service
    |
    2. Saga: reserve -> pay -> ship (described in Phase 3)
    |
    3. Aurora (PostgreSQL): ACID writes for orders, payments
       Strong consistency. No caching. Every write hits DB.

  Characteristics:
    - 50x less traffic than read path
    - Consistency critical (CP) -- can't lose orders, can't double-charge
    - Scales vertically (Aurora) + saga parallelism

  Why separate?
    - Read path: optimize for throughput and latency (cache everything)
    - Write path: optimize for correctness and durability (ACID, no cache)
    - Different databases: DynamoDB (reads) vs Aurora (writes)
    - Different scaling: horizontal (reads) vs vertical (writes)
```

**Common follow-up:** "How do you keep the read model in sync with writes?"

**Answer:** "Event-driven. When an order is confirmed, the Order Service publishes an 'order.confirmed' event to SNS. A Lambda consumer updates the search index (OpenSearch), updates the 'X sold' counter in DynamoDB, and invalidates the product cache in Redis. There's a propagation delay of 1-5 seconds, but that's fine -- a customer seeing '99 sold' instead of '100 sold' doesn't matter. If the Lambda fails, the event goes to a dead-letter queue for retry."

---

## Phase 6: Tradeoffs (3-5 min)

### Saga: Orchestration vs Choreography

| Aspect | Orchestration (Step Functions) | Choreography (Events) |
|--------|-------------------------------|----------------------|
| Visibility | Central: see entire saga state in one place | Distributed: events scattered across services |
| Debugging | Easy: Step Functions console shows each step | Hard: correlate events across 5 services by correlationId |
| Coupling | Orchestrator knows all services | Services know each other's events |
| Compensation | Sequential, ordered, clear | Complex: who compensates when? |
| Scalability | Orchestrator can be bottleneck (mitigated by Step Functions) | Each service scales independently |
| Best for | Linear workflows (checkout) | Non-linear, parallel workflows (post-order processing) |

**Say:** "I use orchestration for the checkout saga because it's a linear, sequential flow where compensation order matters. But for post-order processing (email, analytics, search update), I use choreography via SNS fan-out -- those are independent, parallel tasks that don't need coordination."

### CP vs AP by Service

| Service | CP or AP | Why | What if Wrong? |
|---------|----------|-----|----------------|
| Inventory | **CP** | Overselling is catastrophic | Customer buys PS5 that doesn't exist. Shipping fails. Refund + apology email. |
| Payment | **CP** | Double-charging loses trust | Customer charged twice. Disputes. Chargebacks. Legal risk. |
| Orders | **CP** | Financial record of truth | Lost order = lost revenue. Audit failure. |
| Catalog | **AP** | Stale price/description is minor | Customer sees old price. Re-validate at checkout. Minor UX annoyance. |
| Cart | **AP** | Losing cart item is annoying, not critical | Customer re-adds item. Or gets an abandon-cart email. |
| Search | **AP** | Search index lags by seconds | Customer doesn't see product added 2 seconds ago. Acceptable. |

**Say:** "The rule of thumb: if wrong data costs money or trust, it's CP. If wrong data costs convenience, it's AP. Inventory, payment, and orders are the money path -- CP. Catalog, cart, and search are the browsing path -- AP."

### Synchronous vs Queue-Based Checkout

| Aspect | Synchronous | Queue-Based (SQS) |
|--------|-------------|-------------------|
| User experience | Instant confirmation | "Order queued, ~2 min wait" |
| Throughput | Limited by slowest saga step | Limited by queue consumer rate (controlled) |
| Failure mode | 503 when overloaded | Graceful degradation, orders buffered |
| Complexity | Simple | More complex (queue, workers, status polling) |
| Best for | Normal traffic | Black Friday, flash sales |

**Say:** "I use adaptive switching. Normally, checkout is synchronous -- instant confirmation is a better UX. When order QPS exceeds a threshold (e.g., 3x normal peak), I switch to queue-based -- the system absorbs the spike and drains at a safe rate. The customer sees 'Order is being processed...' which is far better than a 503 error page."

### Monolith vs Microservices for E-Commerce

| Aspect | Monolith | Microservices |
|--------|----------|---------------|
| Complexity | Simple | Complex (networking, service discovery, sagas) |
| Deployment | Single deploy | Independent per service |
| Scaling | Scale everything together | Scale services independently |
| Data consistency | Single DB, ACID transactions | Distributed, eventual consistency + sagas |
| Team structure | Single team, shared codebase | Multiple teams, service ownership |
| Best for | < 1M orders/day, small team | > 10M orders/day, multiple teams |

**Say:** "I'd start as a modular monolith with clear domain boundaries (catalog module, order module, inventory module). When we hit 10M orders/day and have 5+ teams, I'd extract the inventory and payment modules into separate services first -- those have the most critical consistency requirements and the most different scaling needs. The catalog stays in the monolith longer because it's read-heavy and benefits from local function calls."

---

## Red Flags (What NOT to Do)

- Using 2-Phase Commit (2PC) across microservices -- too slow, too fragile
- Decrementing stock directly without reserve/confirm/release -- leads to phantom stock
- No idempotency key on payments -- network retries cause double-charging
- Making the entire system CP -- catalog doesn't need strong consistency
- No state machine for orders -- allows invalid transitions (CREATED -> DELIVERED)
- Ignoring Black Friday / flash sale scenarios -- shows you haven't worked at scale
- Hand-rolling the saga orchestrator -- use Step Functions / Temporal for durability
- No compensation logic -- "what happens when payment fails?" is the #1 follow-up

## Green Flags (What Interviewers Want to Hear)

- Draw the saga flow clearly: reserve -> pay -> ship, compensate in reverse
- Explain reserve/confirm/release pattern for inventory (not direct decrement)
- Mention idempotency key for payments unprompted
- Distinguish CP (inventory, payment, orders) from AP (catalog, cart, search)
- State machine with guards for order lifecycle
- Black Friday: pre-warming, queue-based checkout, Redis DECR for flash sales
- CQRS: read path (cache, DynamoDB, AP) vs write path (Aurora, ACID, CP)
- Proactively discuss orchestration vs choreography and when to use each

---

## 30-Second Elevator Pitch

> "For an Amazon-scale e-commerce system, I'd use **microservices** with an **event-driven architecture**. The checkout flow is a **Saga** orchestrated by Step Functions: reserve inventory, process payment, create shipment -- with compensation in reverse on failure. Inventory uses a **reserve/confirm/release** pattern to prevent overselling. Payments are **idempotent** (orderId as idempotency key, never charge twice). Orders follow a **state machine** with guards on every transition. **CQRS** separates the read path (DynamoDB + Redis cache, AP) from the write path (Aurora, CP). For Black Friday: **pre-warm** resources, switch to **queue-based checkout** via SQS under load, and use **Redis DECR** for flash-sale stock with a circuit breaker that returns 503 when sold out."

**Time: Under 30 seconds. Covers: Saga, inventory, payment, state machine, CQRS, scaling.**

---

## Phase-by-Phase Timing Cheat Sheet

```
Phase 1:  Clarify Requirements            2-3 min   (scale, consistency, flash sales, payments)
Phase 2:  High-Level Architecture          5-7 min   (microservices, event-driven, CQRS, CP vs AP)
Phase 3:  Saga Pattern Deep Dive           8-10 min  (orchestration, happy path, compensation, edge cases)
Phase 4:  Inventory & Payment              5-7 min   (reserve/confirm/release, idempotency, Redis DECR)
Phase 5:  Scaling & Edge Cases             5-8 min   (Black Friday, queue checkout, state machine, CQRS)
Phase 6:  Tradeoffs Discussion             3-5 min   (orchestration vs choreography, CP vs AP, sync vs queue)
-----------------------------------------------------------------------------------
Total:                                     ~35 min
```

If short on time, shorten Phase 5 (scaling) and Phase 6 (tradeoffs). Never skip Phase 3 (saga deep dive) -- that's the core of the interview.
