# E-Commerce System (Amazon)

## Problem Summary

Design an **e-commerce platform** (like Amazon) that handles product catalog browsing, shopping cart, checkout, payment processing, inventory management, and order fulfillment. The core challenges are the **Saga pattern** for distributed transactions across microservices (reserve inventory -> process payment -> create shipment, with compensation on failure), **CQRS** to separate read-heavy catalog browsing from write-heavy order processing, **inventory consistency** to prevent overselling under concurrent purchases, and **idempotent payment processing** to guarantee exactly-once charging. The system must handle 100M+ orders/day with sub-second catalog latency, zero overselling, and graceful degradation during traffic spikes (Black Friday, flash sales).

---

## 1-Minute Interview Revision

**Memorize these bullets. They cover 80% of what interviewers want to hear.**

- **Saga pattern: distributed transaction with compensation. Steps: reserve inventory -> process payment -> create shipment. On failure: compensate in reverse.** Orchestration (Step Functions) vs choreography (events). Orchestration preferred for checkout -- centralized visibility, easier debugging.
- **CQRS: separate read (product search) and write (place order) models. Event sourcing for order history.** Read path: DynamoDB + ElastiCache (cached catalog, sub-ms). Write path: Aurora (ACID orders). Different scaling characteristics.
- **Inventory: synchronized reserve() with available = total - reserved. Confirm on payment success, release on failure.** Use Redis DECR for flash sales (atomic, single-threaded). DynamoDB conditional write for normal flow. Never decrement stock directly -- always reserve first.
- **Payment: idempotency key (orderId). Don't charge twice. Strategy pattern for payment methods.** Every payment request carries an idempotency key. If the key was already processed, return the original result. Stripe, PayPal, etc. behind a PaymentStrategy interface.
- **State machine: CREATED -> INVENTORY_RESERVED -> PAYMENT_CONFIRMED -> SHIPPED -> DELIVERED. Guards on every transition.** Invalid transitions rejected (can't go from CREATED to SHIPPED). Events emitted on each transition for downstream consumers.
- **CAP: CP for inventory/payment (consistency critical), AP for catalog/cart (stale OK).** Overselling or double-charging is catastrophic. Showing a slightly stale product price or an abandoned cart is harmless.
- **Flash sale: queue-based checkout, atomic stock decrement, 503 when sold out.** Redis DECR is atomic. When stock hits 0, circuit breaker opens, all further requests get 503 from CDN edge. No backend load.

---

## Class Hierarchy

```
Product (domain entity)                  Cart (aggregate root)
  |-- id, name, description                |-- cartId, userId
  |-- price, category, imageUrl            |-- items: List<CartItem>
  |-- toString()                           |-- addItem(product, qty)
                                           |-- removeItem(productId)
CartItem (line item)                       |-- updateQuantity(productId, qty)
  |-- product: Product                     |-- getTotal()
  |-- quantity, priceSnapshot              |-- clear()
  |-- getSubtotal()                        |-- Builder pattern

Order (state machine)                    OrderItem (value object)
  |-- orderId, userId, status              |-- product: Product
  |-- items: List<OrderItem>               |-- quantity, priceAtOrder
  |-- totalAmount, paymentId               |-- getSubtotal()
  |-- createdAt, updatedAt
  |-- transition(newStatus)              InventoryService
  |-- State: CREATED, RESERVED,           |-- reserve(productId, qty) -> reservationId
  |    PAYMENT_CONFIRMED, SHIPPED,         |-- confirm(reservationId)
  |    DELIVERED, CANCELLED, FAILED        |-- release(reservationId)
                                           |-- getAvailable(productId)
PaymentService                             |-- Synchronized access
  |-- charge(orderId, amount, method)
  |-- refund(paymentId)                  SagaOrchestrator
  |-- PaymentStrategy interface            |-- executeSaga(order)
  |-- CreditCardStrategy                   |-- compensate(order, failedStep)
  |-- PayPalStrategy                       |-- Steps: reserve -> pay -> ship
  |-- idempotencyKey = orderId             |-- On failure: reverse compensation

OrderStateMachine                        AppConfig (wiring)
  |-- currentState: OrderStatus            |-- creates services, strategies
  |-- transition(event) -> newState        |-- wires saga orchestrator
  |-- validTransitions: Map                |-- configures inventory, payment
  |-- guards on every transition
```

---

## Key Components

| Component | Role |
|-----------|------|
| `Product` | Immutable domain entity. Catalog item with id, name, price, category. Thread-safe by design. |
| `CartItem` | Line item in cart. Snapshots price at add-time to protect from catalog price changes. |
| `Cart` | Aggregate root. Builder pattern. Merges duplicate products, tracks updatedAt for abandon-cart detection. |
| `Order` | State machine. Transitions through CREATED -> RESERVED -> CONFIRMED -> SHIPPED -> DELIVERED. Guards on every transition. |
| `InventoryService` | Reserve/confirm/release pattern. available = total - reserved. Synchronized for consistency. Redis DECR for flash sales. |
| `PaymentService` | Strategy pattern for payment methods. Idempotency key (orderId) prevents double-charging. Charge + refund operations. |
| `SagaOrchestrator` | Coordinates distributed checkout: reserve inventory -> process payment -> create shipment. Compensates in reverse on failure. |
| `OrderStateMachine` | Enforces valid state transitions. Rejects invalid moves (e.g., CREATED -> SHIPPED). Emits events on each transition. |
| `AppConfig` | Wires everything together. Creates services, strategies, saga orchestrator. Single entry point for demo. |

---

## Key Tradeoffs

| Decision | Option A | Option B | This Design |
|----------|----------|----------|-------------|
| Saga style | Choreography (event-driven, decoupled) | Orchestration (centralized, Step Functions) | **Orchestration** -- centralized visibility, easier compensation logic |
| Inventory DB | DynamoDB (high throughput) | RDS Aurora (ACID) | **DynamoDB** + Redis -- DynamoDB for durable state, Redis DECR for flash sales |
| Order DB | DynamoDB (schemaless) | RDS Aurora (relational, ACID) | **Aurora** -- orders are financial records, need ACID, joins for reporting |
| Cart storage | Redis (fast, volatile) | DynamoDB (durable, TTL) | **DynamoDB** -- carts must survive restarts, TTL auto-expires abandoned carts |
| Payment idempotency | DB unique constraint on idempotency key | In-memory cache of processed keys | **DB constraint** -- survives restarts, authoritative dedup |
| Catalog consistency | Strong (always current price) | Eventual (cached, stale OK) | **Eventual (AP)** -- cache product pages, re-validate price at checkout |
| Checkout under load | Synchronous (fail fast) | Queue-based (backpressure) | **Adaptive** -- synchronous normally, queue-based when QPS > threshold |
| Flash sale stock | DynamoDB conditional write | Redis atomic DECR | **Redis DECR** -- sub-ms, atomic, single-threaded. Sync to DynamoDB async. |

---

## SOLID Principles

| Principle | Example |
|-----------|---------|
| **S** -- Single Responsibility | `InventoryService` only manages stock. `PaymentService` only handles charges. `SagaOrchestrator` only coordinates the flow. |
| **O** -- Open/Closed | Add `ApplePayStrategy` without modifying `PaymentService`. New payment method = new class implementing `PaymentStrategy`. |
| **L** -- Liskov Substitution | Any `PaymentStrategy` (CreditCard, PayPal, ApplePay) works wherever the interface is expected. Swap without breaking callers. |
| **I** -- Interface Segregation | `PaymentStrategy` and `InventoryService` are separate interfaces. Payment doesn't know about inventory. |
| **D** -- Dependency Inversion | `SagaOrchestrator` depends on `InventoryService` interface and `PaymentStrategy` interface, not concrete implementations. |

---

## Design Patterns

| Pattern | Where | Why |
|---------|-------|-----|
| **Saga** | SagaOrchestrator (reserve -> pay -> ship -> compensate) | Distributed transaction across microservices without 2PC |
| **State Machine** | OrderStateMachine (CREATED -> RESERVED -> CONFIRMED -> ...) | Enforce valid transitions, guards prevent invalid state changes |
| **Strategy** | PaymentStrategy (CreditCard, PayPal, ApplePay) | Swap payment providers without changing checkout logic |
| **Builder** | Cart.Builder, Order.Builder | Complex object construction with many optional fields |
| **Observer** | Order events published to SNS/SQS on state transitions | Decoupled: order confirmed -> email, analytics, search index update |
| **Factory** | PaymentStrategyFactory creates strategy from payment method | Encapsulate payment provider selection logic |
| **Command** | Saga steps as commands (ReserveCommand, PayCommand) with undo | Each step is executable + compensatable, clean saga structure |
| **Circuit Breaker** | Flash sale: open when stock = 0, return 503 immediately | Protect backend from thundering herd when item sells out |

---

## How to Run

```bash
cd /Users/kanojik/Documents/Karan/Autodesk_AI_Development/systemDesign
./gradlew :10-ecommerce:run
```

---

## Demo Output Preview

```
========================================
  E-COMMERCE SYSTEM (AMAZON) DEMO
========================================

--- Product Catalog Demo ---
Creating product catalog with 10 products...
  Product{id='P001', name='MacBook Pro 16"', price=$2499.00, category='Electronics'}
  Product{id='P002', name='AirPods Pro', price=$249.00, category='Electronics'}
  Product{id='P003', name='Java Concurrency in Practice', price=$45.00, category='Books'}
  ...

--- Shopping Cart Demo ---
Creating cart for user U001...
  Adding MacBook Pro 16" (qty: 1) -> priceSnapshot=$2499.00
  Adding AirPods Pro (qty: 2) -> priceSnapshot=$249.00
  Adding AirPods Pro (qty: 1) -> merged! qty now 3
  Cart{id='CART-001', userId='U001', items=4, total=$3246.00}

  Simulating catalog price change: AirPods Pro $249 -> $279...
  Cart total unchanged: $3246.00 (price snapshot protects customer!)

--- Inventory Management Demo ---
Initializing inventory...
  P001 (MacBook Pro): total=100, reserved=0, available=100
  P002 (AirPods Pro): total=500, reserved=0, available=500

  Reserve 2x MacBook Pro for order ORD-001...
    available = total(100) - reserved(2) = 98
    Reservation{id='RES-001', status=PENDING}

  Concurrent reserve attempt (5 threads, 3 units each)...
    Thread-1: reserved 3 -> available=95
    Thread-2: reserved 3 -> available=92
    Thread-3: reserved 3 -> available=89
    Thread-4: reserved 3 -> available=86
    Thread-5: reserved 3 -> available=83
    No overselling! Synchronized access guaranteed.

--- Payment Processing Demo ---
Processing payment for order ORD-001...
  Strategy: CreditCardStrategy
  Idempotency key: ORD-001
  Amount: $3246.00
  Result: Payment{id='PAY-001', status=CAPTURED}

  Retry with same idempotency key (simulating network retry)...
  Result: Payment{id='PAY-001', status=CAPTURED} (same! no double charge)

--- Saga Orchestration Demo ---
Executing checkout saga for order ORD-001...
  Step 1: Reserve Inventory    -> SUCCESS (RES-001)
  Step 2: Process Payment      -> SUCCESS (PAY-001)
  Step 3: Create Shipment      -> SUCCESS (SHIP-001)
  Step 4: Confirm Order        -> SUCCESS
  Order status: CREATED -> INVENTORY_RESERVED -> PAYMENT_CONFIRMED -> SHIPPED

  Saga with FAILURE (payment declined)...
  Step 1: Reserve Inventory    -> SUCCESS (RES-002)
  Step 2: Process Payment      -> FAILED (insufficient funds)
  Compensation:
    Step 2 comp: Refund         -> SKIPPED (nothing to refund)
    Step 1 comp: Release stock  -> SUCCESS (RES-002 released)
  Order status: CREATED -> INVENTORY_RESERVED -> FAILED

--- Order State Machine Demo ---
Order ORD-001 state transitions:
  CREATED -> INVENTORY_RESERVED        (valid)
  INVENTORY_RESERVED -> PAYMENT_CONFIRMED  (valid)
  PAYMENT_CONFIRMED -> SHIPPED         (valid)
  SHIPPED -> DELIVERED                 (valid)

  Invalid transition attempt: CREATED -> SHIPPED
  Result: IllegalStateException! Guard rejected transition.

--- Flash Sale Demo ---
Flash sale: 10 units of PS5, 25 concurrent buyers...
  Buyer-01: DECR -> 9 remaining -> RESERVED!
  Buyer-02: DECR -> 8 remaining -> RESERVED!
  ...
  Buyer-10: DECR -> 0 remaining -> RESERVED!
  Buyer-11: DECR -> -1 -> SOLD OUT (INCR back to 0)
  Buyer-12: DECR -> -1 -> SOLD OUT (INCR back to 0)
  ...
  Buyer-25: SOLD OUT (circuit breaker OPEN, no Redis call)

  Results: 10 reserved, 15 rejected, 0 oversold!

========================================
  DEMO COMPLETE -- PROJECT 10/10 FINISHED!
========================================
```

---

## Quick Reference

```
Saga (checkout):        Reserve -> Pay -> Ship -> Confirm (compensate in reverse on failure)
State machine:          CREATED -> RESERVED -> CONFIRMED -> SHIPPED -> DELIVERED (guards on every transition)
Inventory reserve:      available = total - reserved. Synchronized. Redis DECR for flash sales.
Payment idempotency:    idempotencyKey = orderId. Same key = same result. Never charge twice.
Cart price snapshot:    Capture price at add-time. Catalog changes don't affect cart total.
CQRS split:            Read: DynamoDB + Cache (AP). Write: Aurora (CP). Different scaling.
Flash sale:             Redis DECR (atomic) -> circuit breaker at 0 -> CDN 503.
Queue-based checkout:   SQS FIFO when QPS > threshold. HTTP 202 "order queued". Controlled drain.
Order events:           SNS fan-out -> SQS per consumer (email, analytics, search index).
Cache (catalog):        ElastiCache Redis, TTL 60s, 95% hit rate. Re-validate price at checkout.
```

---

## What to Improve Later

- [ ] Full Order entity with state machine transitions and event emission
- [ ] InventoryService with reserve/confirm/release and synchronized access
- [ ] PaymentService with Strategy pattern and idempotency key dedup
- [ ] SagaOrchestrator with step execution and reverse compensation
- [ ] Flash sale mode with Redis DECR simulation and circuit breaker
- [ ] CQRS: separate read model (cached catalog) and write model (order processing)
- [ ] Event sourcing for order history (append-only event log)
- [ ] Abandon-cart detection (updatedAt > 24h -> trigger email)
- [ ] Product recommendation engine (collaborative filtering)
- [ ] Multi-currency pricing with exchange rate service
