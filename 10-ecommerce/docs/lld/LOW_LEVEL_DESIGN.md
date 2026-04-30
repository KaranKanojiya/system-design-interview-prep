# Low-Level Design: E-Commerce System (Amazon)

> **Difficulty**: HARD | **Target**: Senior Java Developer (7+ years) | **Focus**: Saga Orchestration, State Machines, Inventory Reservation, Strategy Pattern, Concurrency
> This is a top-tier system design question. It tests distributed transaction patterns (Saga with compensation), order state machines, inventory concurrency (reserve/confirm/release), pricing strategies, and Builder pattern mastery.

---

## Table of Contents

1. [Core Modules Overview](#1-core-modules-overview)
2. [Package Structure](#2-package-structure)
3. [Class Diagram](#3-class-diagram)
4. [Entity Design](#4-entity-design)
5. [Interface Contracts](#5-interface-contracts)
6. [Strategy Implementations](#6-strategy-implementations)
7. [Service Layer Design](#7-service-layer-design)
8. [Concurrency Considerations](#8-concurrency-considerations)
9. [SOLID Principles Applied](#9-solid-principles-applied)
10. [Sample Workflows](#10-sample-workflows)
11. [Design Patterns Used](#11-design-patterns-used)
12. [Extensibility Points](#12-extensibility-points)

---

## 1. Core Modules Overview

| Module | Package | Responsibility |
|--------|---------|----------------|
| **Model** | `model/` | Domain entities: Product (id, name, price, stock, category), Cart (Builder, items list, calculateTotal), CartItem (product snapshot, quantity), Order (Builder + state machine), OrderItem (productId, quantity, unitPrice), Payment (orderId, amount, method, status), Inventory (productId, totalStock, reservedStock, availableStock), Shipment (tracking, carrier, status). Enums: OrderStatus, PaymentStatus, PaymentMethod, ShipmentStatus. |
| **Saga** | `saga/` | Distributed transaction coordination: SagaOrchestrator interface, OrderSagaOrchestrator (THE key class -- coordinates inventory reserve -> payment -> shipping with compensation on failure), SagaResult (success/failure, completed/compensated steps), SagaStep enum. |
| **Strategy (Pricing)** | `strategy/pricing/` | Pluggable pricing: StandardPricingStrategy (base price * quantity), DiscountPricingStrategy (bulk, seasonal, coupon rules). Strategy pattern -- swap pricing algorithm without touching service logic. |
| **Strategy (Payment)** | `strategy/payment/` | Pluggable payment processing: CreditCardPaymentStrategy, WalletPaymentStrategy, CODPaymentStrategy. Each encapsulates payment provider logic. |
| **Strategy (Shipping)** | `strategy/shipping/` | Pluggable shipping calculation: StandardShippingStrategy (5-7 days), ExpressShippingStrategy (1-2 days, higher cost). |
| **Service** | `service/` | Business logic: OrderService (Facade -- orchestrates cart -> order -> saga), CartService (add/remove/update items), ProductService (catalog operations), InventoryService (reserve/confirm/release stock, thread-safe), PaymentService (process/refund), ShippingService (create/track), NotificationService (order/shipping notifications). |
| **Repository** | `repository/` | Data access layer: ProductRepository, OrderRepository, InventoryRepository, CartRepository, PaymentRepository interfaces with InMemory implementations. ConcurrentHashMap-backed stores. |
| **Controller** | `controller/` | REST-like API entry point: ECommerceController maps requests to OrderService calls. |
| **Config** | `config/` | Factory wiring: AppConfig creates all objects and injects dependencies. No framework -- pure constructor injection. |
| **Display** | `display/` | OrderStatsDisplay: order counts by status, revenue, inventory levels, saga success/failure rates. |
| **Exception** | `exception/` | Domain exceptions: ECommerceException (base), InsufficientStockException, PaymentFailedException, OrderNotFoundException, InvalidOrderStateException. |

### Why E-Commerce Is a Top-Tier Interview Question

```
Interviewer's checklist when evaluating your answer:

  1. Do you model order checkout as a Saga (not a single transaction)?  --> Distributed Txn
  2. Can you implement compensation (rollback) when a saga step fails? --> Saga Pattern
  3. Is the Order a proper state machine with guarded transitions?      --> State Machine
  4. Is inventory reservation thread-safe (reserve/confirm/release)?    --> Concurrency
  5. Is pricing pluggable (standard vs discount vs bulk)?              --> Strategy Pattern
  6. Does your Cart use Builder for clean construction?                 --> Builder Pattern
  7. Can you add a new payment method without changing PaymentService?  --> Open-Closed
  8. Is your OrderService a clean Facade?                              --> Facade Pattern
  9. Do you separate reserved stock from available stock?              --> Domain Modeling
  10. Can you trace a saga failure and show which steps compensated?    --> Observability
```

---

## 2. Package Structure

```
com.systemdesign.ecommerce
│
├── model/
│   ├── Product.java            -- id, name, description, price, category, stock
│   ├── CartItem.java           -- product, quantity, price snapshot at time of add
│   ├── Cart.java               -- userId, items list, total, Builder pattern
│   ├── Order.java              -- Builder pattern, full lifecycle state machine
│   ├── OrderStatus.java        -- enum: CREATED → INVENTORY_RESERVED → PAYMENT_PENDING → ...
│   ├── OrderItem.java          -- productId, quantity, unitPrice, subtotal
│   ├── Payment.java            -- orderId, amount, method, status, transactionId
│   ├── PaymentStatus.java      -- enum: PENDING, PROCESSING, COMPLETED, FAILED, REFUNDED
│   ├── PaymentMethod.java      -- enum: CREDIT_CARD, DEBIT_CARD, WALLET, COD
│   ├── Inventory.java          -- productId, totalStock, reservedStock, availableStock
│   ├── Shipment.java           -- orderId, trackingId, carrier, status, estimatedDelivery
│   ├── ShipmentStatus.java     -- enum: PREPARING, SHIPPED, IN_TRANSIT, DELIVERED
│   └── SagaStep.java           -- stepName, status (PENDING/COMPLETED/COMPENSATED), timestamp
│
├── saga/
│   ├── SagaOrchestrator.java        -- interface: execute(Order) → SagaResult
│   ├── OrderSagaOrchestrator.java   -- THE key class: inventory → payment → shipping + compensation
│   ├── SagaResult.java              -- success/failure, completedSteps, failedStep, compensatedSteps
│   └── SagaStep.java                -- enum: RESERVE_INVENTORY, PROCESS_PAYMENT, CREATE_SHIPMENT
│
├── strategy/
│   ├── pricing/
│   │   ├── PricingStrategy.java          -- interface: calculatePrice(items) → total
│   │   ├── StandardPricingStrategy.java  -- base price * quantity
│   │   └── DiscountPricingStrategy.java  -- apply discount rules (bulk, seasonal, coupon)
│   │
│   ├── payment/
│   │   ├── PaymentStrategy.java          -- interface: processPayment(amount) → Payment
│   │   ├── CreditCardPaymentStrategy.java
│   │   ├── WalletPaymentStrategy.java
│   │   └── CODPaymentStrategy.java
│   │
│   └── shipping/
│       ├── ShippingStrategy.java         -- interface: calculateShipping(order) → Shipment
│       ├── StandardShippingStrategy.java -- 5-7 days, base shipping cost
│       └── ExpressShippingStrategy.java  -- 1-2 days, higher cost
│
├── service/
│   ├── OrderService.java       -- FACADE: orchestrates cart → order → saga
│   ├── CartService.java        -- add/remove/update cart items
│   ├── ProductService.java     -- product catalog operations
│   ├── InventoryService.java   -- reserve/confirm/release stock (synchronized!)
│   ├── PaymentService.java     -- process/refund payments via strategy
│   ├── ShippingService.java    -- create/track shipments via strategy
│   └── NotificationService.java -- order/shipping notifications (simulated)
│
├── repository/
│   ├── ProductRepository.java, InMemoryProductRepository.java
│   ├── OrderRepository.java, InMemoryOrderRepository.java
│   ├── InventoryRepository.java, InMemoryInventoryRepository.java
│   ├── CartRepository.java, InMemoryCartRepository.java
│   └── PaymentRepository.java, InMemoryPaymentRepository.java
│
├── controller/
│   └── ECommerceController.java   -- REST-like entry point
│
├── config/
│   └── AppConfig.java             -- factory wiring, pure constructor injection
│
├── display/
│   └── OrderStatsDisplay.java     -- formatted order/inventory/revenue stats
│
├── exception/
│   ├── ECommerceException.java         -- base exception for all e-commerce errors
│   ├── InsufficientStockException.java -- thrown when reserve() fails
│   ├── PaymentFailedException.java     -- thrown when payment processing fails
│   ├── OrderNotFoundException.java     -- thrown when order lookup fails
│   └── InvalidOrderStateException.java -- thrown on illegal state transition
│
└── ECommerceApp.java              -- Main demo: wires everything, runs order scenarios
```

---

## 3. Class Diagram

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║                    SAGA ORCHESTRATION (THE Core Pattern)                          ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    +-----------------------------------------------------------+
    |          <<interface>>  SagaOrchestrator                   |
    |-----------------------------------------------------------|
    | + execute(order: Order): SagaResult                       |
    | + getOrchestratorName(): String                           |
    +-----------------------------------------------------------+
              ^
              |
         implements
              |
    +---------+-----------------------------------------+
    |         OrderSagaOrchestrator                      |
    |---------------------------------------------------|
    | - inventoryService: InventoryService               |
    | - paymentService: PaymentService                   |
    | - shippingService: ShippingService                 |
    | - notificationService: NotificationService         |
    |---------------------------------------------------|
    | + execute(order: Order): SagaResult                |
    | - reserveInventory(order): void                    |
    | - processPayment(order): Payment                   |
    | - createShipment(order): Shipment                  |
    | - compensateInventory(order): void                 |
    | - compensatePayment(payment): void                 |
    | - compensateShipment(shipment): void               |
    +---------------------------------------------------+
              |
              | orchestrates (in order)
              |
    +---------+---------+---------+
    |         |         |         |
    v         v         v         v
  Reserve   Process   Create   Notify
  Inventory Payment   Shipment (on success
                                or failure)

    SAGA EXECUTION FLOW:

    Step 1: RESERVE_INVENTORY
       success → go to Step 2
       failure → DONE (nothing to compensate)

    Step 2: PROCESS_PAYMENT
       success → go to Step 3
       failure → compensate Step 1 (release inventory) → DONE

    Step 3: CREATE_SHIPMENT
       success → DONE (all steps completed)
       failure → compensate Step 2 (refund) → compensate Step 1 (release) → DONE


╔═══════════════════════════════════════════════════════════════════════════════════╗
║                     ORDER STATE MACHINE                                           ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    ┌──────────┐ reserveInventory() ┌────────────────────┐ initiatePayment()
    │ CREATED  │───────────────────→│ INVENTORY_RESERVED │──────────────────→
    └──────────┘                    └────────────────────┘
         │                                   │
         │ cancel()                          │ cancel()
         │                                   │ (releases inventory)
         ▼                                   ▼
    ┌───────────┐                       ┌───────────┐
    │ CANCELLED │                       │ CANCELLED │
    └───────────┘                       └───────────┘

    ┌──────────────────┐ confirmPayment() ┌───────────────────┐
 ──→│ PAYMENT_PENDING  │────────────────→│ PAYMENT_CONFIRMED │
    └──────────────────┘                  └───────────────────┘
         │                                        │
         │ paymentFailed()                        │ ship()
         │                                        │
         ▼                                        ▼
    ┌───────────┐                           ┌──────────┐ deliver() ┌───────────┐
    │ CANCELLED │                           │ SHIPPED  │──────────→│ DELIVERED │
    └───────────┘                           └──────────┘           └───────────┘
                                                                        │
                                                                        │ refund()
                                                                        ▼
                                                                   ┌──────────┐
                                                                   │ REFUNDED │
                                                                   └──────────┘

    Valid transitions (enforced by Order.transitionTo()):
      CREATED              → INVENTORY_RESERVED, CANCELLED
      INVENTORY_RESERVED   → PAYMENT_PENDING, CANCELLED
      PAYMENT_PENDING      → PAYMENT_CONFIRMED, CANCELLED
      PAYMENT_CONFIRMED    → SHIPPED, CANCELLED
      SHIPPED              → DELIVERED
      DELIVERED            → REFUNDED
      CANCELLED            → (terminal state)
      REFUNDED             → (terminal state)


╔═══════════════════════════════════════════════════════════════════════════════════╗
║                    PRICING STRATEGY HIERARCHY (Strategy Pattern)                  ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    +-----------------------------------------------------------+
    |          <<interface>>  PricingStrategy                    |
    |-----------------------------------------------------------|
    | + calculatePrice(items: List<OrderItem>): double          |
    | + getStrategyName(): String                               |
    +-----------------------------------------------------------+
          ^                            ^
          |                            |
     implements                   implements
          |                            |
    +-----+----------+   +------------+-----------+
    | StandardPricing|   | DiscountPricing        |
    |   Strategy     |   |   Strategy             |
    |----------------|   |------------------------|
    |                |   | -bulkThreshold: int    |
    |                |   | -bulkDiscount: double  |
    |                |   | -seasonalDiscount: d   |
    |----------------|   |------------------------|
    | +calculatePrice|   | +calculatePrice:       |
    |  sum(unitPrice |   |  apply bulk discount   |
    |  * quantity)   |   |  if qty > threshold,   |
    |                |   |  then seasonal %       |
    +----------------+   +------------------------+


╔═══════════════════════════════════════════════════════════════════════════════════╗
║                    PAYMENT STRATEGY HIERARCHY (Strategy Pattern)                  ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    +-----------------------------------------------------------+
    |          <<interface>>  PaymentStrategy                    |
    |-----------------------------------------------------------|
    | + processPayment(orderId: String, amount: double):Payment |
    | + refundPayment(payment: Payment): boolean                |
    | + getPaymentMethodName(): String                          |
    +-----------------------------------------------------------+
          ^                   ^                    ^
          |                   |                    |
     implements          implements           implements
          |                   |                    |
    +-----+------+   +-------+--------+   +-------+------+
    | CreditCard |   | Wallet         |   | COD          |
    |  Payment   |   |  Payment       |   |  Payment     |
    |  Strategy  |   |  Strategy      |   |  Strategy    |
    |------------|   |----------------|   |--------------|
    | -gateway:  |   | -walletBalance:|   |              |
    |  String    |   |  Map<userId,   |   |              |
    |            |   |       balance> |   |              |
    |------------|   |----------------|   |--------------|
    | charges    |   | deducts from   |   | marks as     |
    | via card   |   | wallet balance |   | COD_PENDING, |
    | gateway    |   | atomically     |   | confirm on   |
    |            |   |                |   | delivery     |
    +------------+   +----------------+   +--------------+


╔═══════════════════════════════════════════════════════════════════════════════════╗
║                    SHIPPING STRATEGY HIERARCHY (Strategy Pattern)                 ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    +-----------------------------------------------------------+
    |          <<interface>>  ShippingStrategy                   |
    |-----------------------------------------------------------|
    | + createShipment(order: Order): Shipment                  |
    | + calculateCost(order: Order): double                     |
    | + getEstimatedDays(): int                                 |
    | + getStrategyName(): String                               |
    +-----------------------------------------------------------+
          ^                            ^
          |                            |
     implements                   implements
          |                            |
    +-----+----------+   +------------+-----------+
    | StandardShip   |   | ExpressShip            |
    |   Strategy     |   |   Strategy             |
    |----------------|   |------------------------|
    | -baseCost: 5.0 |   | -baseCost: 15.0       |
    | -perItemCost:  |   | -perItemCost: 3.0     |
    |    1.0         |   | -priorityFee: 5.0     |
    | -days: 5-7     |   | -days: 1-2            |
    |----------------|   |------------------------|
    | +createShipment|   | +createShipment:       |
    |  base + items  |   |  base + items +        |
    |  * perItem     |   |  priorityFee           |
    +----------------+   +------------------------+


╔═══════════════════════════════════════════════════════════════════════════════════╗
║                          SERVICE LAYER (Facade Pattern)                           ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    ┌─────────────────────────────────────────────────────────────────────┐
    │                    ECommerceController                               │
    │   placeOrder() │ cancelOrder() │ getOrder() │ addToCart()           │
    └────────┬────────────────┬──────────────┬─────────────────┬─────────┘
             │                │              │                 │
             ▼                ▼              ▼                 ▼
    ┌─────────────────────────────────────────────────────────────────────┐
    │                    OrderService (FACADE)                             │
    │   Orchestrates: cart → order → saga (inventory, payment, shipping)  │
    └──┬──────────┬───────────┬──────────┬───────────┬──────────┬────────┘
       │          │           │          │           │          │
       ▼          ▼           ▼          ▼           ▼          ▼
    Cart       Product    Inventory   Payment    Shipping   Notification
    Service    Service    Service     Service    Service    Service
       │          │           │          │           │          │
       ▼          ▼           ▼          ▼           ▼          ▼
    Cart       Product    Inventory  Payment    Shipping   (console
    Repo       Repo       Repo       Strategy   Strategy    logging)
                                     (pluggable)(pluggable)
```

---

## 4. Entity Design

### 4.1 Product

```java
/**
 * Represents a product in the catalog.
 *
 * Immutable record: once a product is created, its fields do not change.
 * In a real system, price changes would create a new product version.
 * For our interview scope, we keep it simple with a record.
 *
 * Used by:
 *   - ProductService: CRUD operations on the catalog
 *   - CartService: looks up product to create CartItem (snapshot price)
 *   - InventoryService: maps productId -> Inventory for stock tracking
 *
 * Why a record?
 *   - Products are value objects: identity is the productId
 *   - Immutable by default: no setter bugs, thread-safe reads
 *   - Automatic equals/hashCode based on all fields
 */
public record Product(
    String productId,
    String name,
    String description,
    double price,          // current catalog price
    String category,       // "Electronics", "Books", "Clothing", etc.
    int initialStock       // stock at time of catalog entry
) {
    /**
     * Compact constructor: validates all fields.
     * Records run this on every instantiation.
     */
    public Product {
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("productId cannot be null or blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name cannot be null or blank");
        }
        if (price < 0) {
            throw new IllegalArgumentException("price cannot be negative: " + price);
        }
        if (initialStock < 0) {
            throw new IllegalArgumentException("initialStock cannot be negative: " + initialStock);
        }
    }
}
```

---

### 4.2 Inventory (The Concurrency-Critical Entity)

> **This is where interviews get hard.** The inventory model must track three numbers: totalStock, reservedStock, and availableStock. Reserve operations must be atomic. Two users buying the last item must not both succeed.

```
     ┌──────────────────────────────────────────────────────────────────┐
     │           INVENTORY STOCK MODEL                                  │
     │                                                                  │
     │   totalStock = 100                                              │
     │   reservedStock = 30  (reserved for pending orders)             │
     │   availableStock = 70 (totalStock - reservedStock)              │
     │                                                                  │
     │   OPERATION: reserve(qty=10)                                    │
     │     BEFORE: total=100, reserved=30, available=70                │
     │     CHECK:  available >= qty?  70 >= 10? YES                    │
     │     AFTER:  total=100, reserved=40, available=60                │
     │                                                                  │
     │   OPERATION: confirmReservation(qty=10)                         │
     │     (order completed -- stock is truly consumed)                 │
     │     BEFORE: total=100, reserved=40, available=60                │
     │     AFTER:  total=90,  reserved=30, available=60                │
     │                                                                  │
     │   OPERATION: releaseReservation(qty=10)                         │
     │     (order cancelled -- put reserved stock back to available)    │
     │     BEFORE: total=100, reserved=40, available=60                │
     │     AFTER:  total=100, reserved=30, available=70                │
     │                                                                  │
     │   WHY three numbers?                                            │
     │     If we only tracked "stock = 100" and two users buy the      │
     │     last item simultaneously, both see stock=1, both succeed,   │
     │     and we oversell. Reserve/confirm/release prevents this.     │
     └──────────────────────────────────────────────────────────────────┘
```

#### Anti-Pattern: Naive Stock Tracking

```
     ┌──────────────────────────────────────────────────────────────────┐
     │           ANTI-PATTERN: Single Stock Counter                     │
     │                                                                  │
     │   // DANGEROUS: no reservation concept                          │
     │   public class NaiveInventory {                                 │
     │       private int stock = 100;                                  │
     │                                                                  │
     │       // Thread A reads stock=1, Thread B reads stock=1         │
     │       // Both pass the check, both decrement → stock = -1!      │
     │       public boolean purchase(int qty) {                        │
     │           if (stock >= qty) {    // <-- CHECK                   │
     │               stock -= qty;      // <-- ACT (not atomic!)       │
     │               return true;                                      │
     │           }                                                     │
     │           return false;                                         │
     │       }                                                         │
     │   }                                                             │
     │                                                                  │
     │   PROBLEMS:                                                      │
     │     1. Race condition: check-then-act is NOT atomic             │
     │     2. No reservation: payment takes 5 seconds, stock could     │
     │        be grabbed by another user in the meantime               │
     │     3. No rollback: if payment fails, stock is already gone     │
     │     4. Overselling: two threads can both pass the check         │
     └──────────────────────────────────────────────────────────────────┘
```

#### Clean Solution: Reserve/Confirm/Release Model

```java
/**
 * Tracks inventory for a single product with reservation semantics.
 *
 * THREE operations, each modifying different counters:
 *
 *   reserve(qty):
 *     - Check: availableStock >= qty
 *     - Mutate: reservedStock += qty (available auto-decreases)
 *     - When: Saga Step 1 -- before payment
 *
 *   confirmReservation(qty):
 *     - Mutate: totalStock -= qty, reservedStock -= qty
 *     - When: payment confirmed -- stock is truly sold
 *
 *   releaseReservation(qty):
 *     - Mutate: reservedStock -= qty (available auto-increases)
 *     - When: payment failed or order cancelled -- give stock back
 *
 * INVARIANT: availableStock = totalStock - reservedStock (always)
 *
 * Thread safety: ALL mutations go through InventoryService which
 * uses synchronized blocks keyed by productId. See Section 8.
 */
public class Inventory {

    private final String productId;
    private int totalStock;
    private int reservedStock;

    public Inventory(String productId, int totalStock) {
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("productId cannot be null or blank");
        }
        if (totalStock < 0) {
            throw new IllegalArgumentException("totalStock cannot be negative: " + totalStock);
        }
        this.productId = productId;
        this.totalStock = totalStock;
        this.reservedStock = 0;
    }

    /** Available = total - reserved. Always computed, never stored. */
    public int getAvailableStock() {
        return totalStock - reservedStock;
    }

    /**
     * Reserve qty units. Does NOT reduce totalStock.
     * The reserved units are "held" for this order during payment processing.
     *
     * @throws InsufficientStockException if availableStock < qty
     */
    public void reserve(int qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("qty must be positive: " + qty);
        }
        if (getAvailableStock() < qty) {
            throw new InsufficientStockException(
                "Cannot reserve " + qty + " units of product " + productId
                + ". Available: " + getAvailableStock()
            );
        }
        // Move qty from "available" to "reserved"
        this.reservedStock += qty;
    }

    /**
     * Confirm a previous reservation. Stock is truly consumed.
     * Called after payment succeeds.
     *
     * totalStock goes DOWN, reservedStock goes DOWN.
     * Net effect on availableStock: zero (it was already reduced by reserve()).
     */
    public void confirmReservation(int qty) {
        if (qty <= 0 || qty > reservedStock) {
            throw new IllegalArgumentException(
                "Cannot confirm " + qty + ". Reserved: " + reservedStock
            );
        }
        this.totalStock -= qty;
        this.reservedStock -= qty;
    }

    /**
     * Release a previous reservation. Stock goes back to "available."
     * Called when payment fails or order is cancelled.
     *
     * reservedStock goes DOWN, availableStock goes UP.
     * totalStock stays the same.
     */
    public void releaseReservation(int qty) {
        if (qty <= 0 || qty > reservedStock) {
            throw new IllegalArgumentException(
                "Cannot release " + qty + ". Reserved: " + reservedStock
            );
        }
        this.reservedStock -= qty;
    }

    // --- Getters ---
    public String getProductId() { return productId; }
    public int getTotalStock() { return totalStock; }
    public int getReservedStock() { return reservedStock; }

    @Override
    public String toString() {
        return String.format("Inventory[product=%s, total=%d, reserved=%d, available=%d]",
            productId, totalStock, reservedStock, getAvailableStock());
    }
}
```

**Interview follow-up**: "What happens if the server crashes between reserve() and confirmReservation()?" Answer: The reservation times out. A background cleanup job (not shown in LLD) would periodically scan for reservations older than N minutes and call releaseReservation(). In a real system, this would be a scheduled task or TTL-based expiry.

---

### 4.3 CartItem and Cart (Builder Pattern)

> **Builder pattern**: Cart has multiple fields and a list of items. Builder avoids telescoping constructors and provides a fluent API for construction. CartItem snapshots the price at the time of adding to cart, protecting against catalog price changes during the shopping session.

#### Anti-Pattern: Telescoping Constructors

```
     ┌──────────────────────────────────────────────────────────────────┐
     │           ANTI-PATTERN: Telescoping Cart Constructor              │
     │                                                                  │
     │   // Which parameter is which? Completely unreadable.            │
     │   Cart cart = new Cart(                                          │
     │       "cart-001",              // cartId                         │
     │       "user-123",             // userId                          │
     │       items,                  // items list                      │
     │       249.97,                 // subtotal                        │
     │       12.50,                  // tax                             │
     │       5.00,                   // shippingCost                    │
     │       267.47,                 // total                           │
     │       Instant.now(),          // createdAt                       │
     │       Instant.now()           // updatedAt                       │
     │   );                                                             │
     │                                                                  │
     │   PROBLEMS:                                                      │
     │     1. 9 constructor params -- unreadable at the call site       │
     │     2. subtotal, tax, shippingCost, total all doubles --         │
     │        easy to swap by accident                                  │
     │     3. Total should be computed, not passed in                   │
     │     4. Items list can be mutated externally after construction   │
     │     5. No validation: total could be negative                   │
     └──────────────────────────────────────────────────────────────────┘
```

#### Clean Solution: Builder Pattern

```java
/**
 * A snapshot of a product added to a cart.
 *
 * WHY snapshot the price?
 *   - Product catalog price may change while user is shopping.
 *   - CartItem.priceSnapshot locks in the price at time of add-to-cart.
 *   - When user checks out, they pay the snapshotted price.
 *   - This is exactly how Amazon works: "Price when added to cart."
 *
 * Immutable: once created, a CartItem does not change.
 * To change quantity, CartService removes the old CartItem and adds a new one.
 */
public record CartItem(
    String productId,
    String productName,
    int quantity,
    double priceSnapshot    // price at time of adding to cart
) {
    public CartItem {
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("productId required");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }
        if (priceSnapshot < 0) {
            throw new IllegalArgumentException("price cannot be negative: " + priceSnapshot);
        }
    }

    /** Subtotal for this line item = price * quantity. */
    public double getSubtotal() {
        return priceSnapshot * quantity;
    }
}
```

```java
/**
 * Shopping cart for a single user.
 *
 * Uses the BUILDER PATTERN for clean construction:
 *   Cart cart = new Cart.Builder("user-123")
 *       .addItem(new CartItem("prod-1", "Laptop", 1, 999.99))
 *       .addItem(new CartItem("prod-2", "Mouse", 2, 29.99))
 *       .build();
 *
 * Key design decisions:
 *   1. Items list is defensively copied -- external mutation is impossible.
 *   2. Total is COMPUTED from items, never passed in. Eliminates bugs
 *      where total and items disagree.
 *   3. Builder validates all fields before creating the Cart.
 *   4. Cart is effectively immutable after construction.
 *      CartService creates a new Cart when items change.
 */
public class Cart {

    private final String cartId;
    private final String userId;
    private final List<CartItem> items;      // defensively copied
    private final double subtotal;           // computed: sum of item subtotals
    private final Instant createdAt;
    private final Instant updatedAt;

    /** Private constructor: only Builder can create Cart instances. */
    private Cart(Builder builder) {
        this.cartId = builder.cartId;
        this.userId = builder.userId;
        this.items = List.copyOf(builder.items);  // immutable copy!
        this.subtotal = calculateSubtotal();
        this.createdAt = builder.createdAt;
        this.updatedAt = Instant.now();
    }

    /**
     * Computes subtotal from items. Called once at construction.
     * This is the SINGLE SOURCE OF TRUTH for cart total.
     * No "setTotal()" method -- total always matches items.
     */
    private double calculateSubtotal() {
        return items.stream()
            .mapToDouble(CartItem::getSubtotal)
            .sum();
    }

    // --- Getters ---
    public String getCartId() { return cartId; }
    public String getUserId() { return userId; }
    public List<CartItem> getItems() { return items; }  // already immutable
    public double getSubtotal() { return subtotal; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public boolean isEmpty() { return items.isEmpty(); }
    public int getItemCount() { return items.size(); }

    @Override
    public String toString() {
        return String.format("Cart[id=%s, user=%s, items=%d, subtotal=%.2f]",
            cartId, userId, items.size(), subtotal);
    }

    // ======================== BUILDER ========================

    /**
     * Builder for Cart.
     *
     * Usage:
     *   Cart cart = new Cart.Builder("user-123")
     *       .addItem(laptopCartItem)
     *       .addItem(mouseCartItem)
     *       .build();
     *
     * WHY Builder here?
     *   - Cart has many fields, some computed (subtotal)
     *   - Items are added incrementally
     *   - Validation happens at build() time, not scattered across setters
     *   - Fluent API reads naturally: builder.addItem(x).addItem(y).build()
     */
    public static class Builder {
        // Required
        private final String userId;
        private String cartId;
        private Instant createdAt;

        // Optional, accumulated
        private final List<CartItem> items = new ArrayList<>();

        public Builder(String userId) {
            if (userId == null || userId.isBlank()) {
                throw new IllegalArgumentException("userId required");
            }
            this.userId = userId;
            this.cartId = "cart-" + UUID.randomUUID().toString().substring(0, 8);
            this.createdAt = Instant.now();
        }

        public Builder cartId(String cartId) {
            this.cartId = cartId;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        /** Add an item to the cart. Fluent: returns this Builder. */
        public Builder addItem(CartItem item) {
            if (item == null) {
                throw new IllegalArgumentException("CartItem cannot be null");
            }
            this.items.add(item);
            return this;
        }

        /** Add multiple items at once. Fluent: returns this Builder. */
        public Builder addItems(List<CartItem> items) {
            if (items == null) {
                throw new IllegalArgumentException("items list cannot be null");
            }
            this.items.addAll(items);
            return this;
        }

        /** Validate and build the Cart. */
        public Cart build() {
            if (items.isEmpty()) {
                throw new IllegalStateException("Cart must have at least one item");
            }
            return new Cart(this);
        }
    }
}
```

---

### 4.4 Order (Builder Pattern + State Machine)

> **Builder + State Machine = the most interview-tested combo.** Order has many fields (some set at creation, some at different lifecycle stages). The state machine enforces that you cannot skip steps: you cannot ship an order that has not been paid for.

#### Anti-Pattern: Unguarded State Transitions

```
     ┌──────────────────────────────────────────────────────────────────┐
     │           ANTI-PATTERN: No State Machine                         │
     │                                                                  │
     │   // Anyone can set any status at any time!                     │
     │   public class NaiveOrder {                                     │
     │       private OrderStatus status;                               │
     │                                                                  │
     │       // No guard: status can jump from CREATED to DELIVERED    │
     │       public void setStatus(OrderStatus status) {               │
     │           this.status = status;  // <-- NO VALIDATION!          │
     │       }                                                         │
     │   }                                                             │
     │                                                                  │
     │   // Bug: skip payment entirely!                                │
     │   order.setStatus(OrderStatus.CREATED);                         │
     │   order.setStatus(OrderStatus.DELIVERED);  // <-- OOPS!        │
     │                                                                  │
     │   PROBLEMS:                                                      │
     │     1. No transition validation: any state can jump to any other │
     │     2. Business rules scattered across callers, not centralized  │
     │     3. Easy to skip critical steps (payment, inventory check)    │
     │     4. Impossible to audit: no record of state transitions       │
     │     5. Testing nightmare: every caller must be checked            │
     └──────────────────────────────────────────────────────────────────┘
```

#### Clean Solution: Guarded State Machine + Builder

```java
/**
 * Represents a customer order from creation through delivery/refund.
 *
 * Uses TWO patterns:
 *   1. BUILDER: for construction (many fields, some optional, some computed)
 *   2. STATE MACHINE: for lifecycle transitions (guarded by VALID_TRANSITIONS map)
 *
 * An Order progresses through:
 *   CREATED → INVENTORY_RESERVED → PAYMENT_PENDING → PAYMENT_CONFIRMED
 *           → SHIPPED → DELIVERED
 * with CANCELLED possible from CREATED through PAYMENT_CONFIRMED,
 * and REFUNDED possible from DELIVERED.
 *
 * State transitions are enforced in transitionTo(). Invalid transitions
 * throw InvalidOrderStateException. This prevents bugs like:
 *   - Shipping an order that was never paid for
 *   - Delivering a cancelled order
 *   - Paying for an order with no inventory reserved
 *
 * INTERVIEW TIP: Draw the state diagram first, then implement
 * the transition map. The map IS the specification.
 */
public class Order {

    // --- Immutable fields (set at construction) ---
    private final String orderId;
    private final String userId;
    private final List<OrderItem> items;       // defensive copy
    private final Instant createdAt;

    // --- Mutable fields (set during lifecycle by transitionTo + setters) ---
    private OrderStatus status;
    private double subtotal;
    private double tax;
    private double shippingCost;
    private double totalAmount;
    private Payment payment;                    // set after PAYMENT_CONFIRMED
    private Shipment shipment;                  // set after SHIPPED
    private Instant updatedAt;
    private final List<SagaStep> sagaHistory;   // tracks saga execution steps

    /**
     * VALID TRANSITIONS: defines the state machine.
     * Map<CurrentState, Set<AllowedNextStates>>
     *
     * This is loaded ONCE at class initialization.
     * transitionTo() checks this map before allowing any state change.
     *
     * Think of this as the "truth table" for the order lifecycle.
     * Every state lists exactly which states it can move to.
     * Terminal states (CANCELLED, REFUNDED) have empty sets.
     */
    private static final Map<OrderStatus, Set<OrderStatus>> VALID_TRANSITIONS = Map.of(
        OrderStatus.CREATED,             Set.of(OrderStatus.INVENTORY_RESERVED,
                                                OrderStatus.CANCELLED),
        OrderStatus.INVENTORY_RESERVED,  Set.of(OrderStatus.PAYMENT_PENDING,
                                                OrderStatus.CANCELLED),
        OrderStatus.PAYMENT_PENDING,     Set.of(OrderStatus.PAYMENT_CONFIRMED,
                                                OrderStatus.CANCELLED),
        OrderStatus.PAYMENT_CONFIRMED,   Set.of(OrderStatus.SHIPPED,
                                                OrderStatus.CANCELLED),
        OrderStatus.SHIPPED,             Set.of(OrderStatus.DELIVERED),
        OrderStatus.DELIVERED,           Set.of(OrderStatus.REFUNDED),
        OrderStatus.CANCELLED,           Set.of(),    // terminal
        OrderStatus.REFUNDED,            Set.of()     // terminal
    );

    /** Private constructor: only Builder can create Order instances. */
    private Order(Builder builder) {
        this.orderId = builder.orderId;
        this.userId = builder.userId;
        this.items = List.copyOf(builder.items);  // immutable copy!
        this.status = OrderStatus.CREATED;
        this.subtotal = builder.subtotal;
        this.tax = builder.tax;
        this.shippingCost = builder.shippingCost;
        this.totalAmount = subtotal + tax + shippingCost;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.sagaHistory = new ArrayList<>();
    }

    /**
     * THE state machine transition method.
     *
     * CALL CHAIN:
     *   OrderSagaOrchestrator.execute()
     *     → order.transitionTo(INVENTORY_RESERVED)
     *     → order.transitionTo(PAYMENT_PENDING)
     *     → order.transitionTo(PAYMENT_CONFIRMED)
     *     → order.transitionTo(SHIPPED)
     *
     * Each call validates: "is newStatus reachable from current status?"
     * If not, throws InvalidOrderStateException.
     *
     * @param newStatus the target state
     * @throws InvalidOrderStateException if the transition is not allowed
     */
    public void transitionTo(OrderStatus newStatus) {
        Set<OrderStatus> allowed = VALID_TRANSITIONS.get(this.status);

        if (allowed == null || !allowed.contains(newStatus)) {
            throw new InvalidOrderStateException(
                "Cannot transition from " + this.status + " to " + newStatus
                + ". Allowed transitions: " + allowed
            );
        }

        // Log the transition for audit/debugging
        OrderStatus previousStatus = this.status;
        this.status = newStatus;
        this.updatedAt = Instant.now();

        System.out.println("[Order " + orderId + "] "
            + previousStatus + " → " + newStatus
            + " at " + updatedAt);
    }

    /** Record a saga step execution for observability. */
    public void addSagaStep(SagaStep step) {
        this.sagaHistory.add(step);
    }

    /** Check if order is in a terminal state. */
    public boolean isTerminal() {
        return VALID_TRANSITIONS.getOrDefault(status, Set.of()).isEmpty();
    }

    /** Check if a specific transition is valid without performing it. */
    public boolean canTransitionTo(OrderStatus newStatus) {
        Set<OrderStatus> allowed = VALID_TRANSITIONS.get(this.status);
        return allowed != null && allowed.contains(newStatus);
    }

    // --- Setters for mutable lifecycle fields ---
    public void setPayment(Payment payment) { this.payment = payment; }
    public void setShipment(Shipment shipment) { this.shipment = shipment; }
    public void setShippingCost(double cost) {
        this.shippingCost = cost;
        this.totalAmount = subtotal + tax + shippingCost;
    }

    // --- Getters ---
    public String getOrderId() { return orderId; }
    public String getUserId() { return userId; }
    public List<OrderItem> getItems() { return items; }  // already immutable
    public OrderStatus getStatus() { return status; }
    public double getSubtotal() { return subtotal; }
    public double getTax() { return tax; }
    public double getShippingCost() { return shippingCost; }
    public double getTotalAmount() { return totalAmount; }
    public Payment getPayment() { return payment; }
    public Shipment getShipment() { return shipment; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<SagaStep> getSagaHistory() {
        return Collections.unmodifiableList(sagaHistory);
    }

    @Override
    public String toString() {
        return String.format("Order[id=%s, user=%s, status=%s, items=%d, total=%.2f]",
            orderId, userId, status, items.size(), totalAmount);
    }

    // ======================== BUILDER ========================

    /**
     * Builder for Order.
     *
     * Usage:
     *   Order order = new Order.Builder("user-123")
     *       .addItem(new OrderItem("prod-1", 2, 49.99))
     *       .addItem(new OrderItem("prod-2", 1, 999.99))
     *       .tax(87.50)
     *       .build();
     *
     * The Builder:
     *   - Computes subtotal from items (single source of truth)
     *   - Sets initial status to CREATED
     *   - Generates orderId automatically
     *   - Validates: must have at least one item, userId required
     */
    public static class Builder {
        // Required
        private final String userId;
        private String orderId;

        // Accumulated
        private final List<OrderItem> items = new ArrayList<>();

        // Optional with defaults
        private double subtotal = 0.0;
        private double tax = 0.0;
        private double shippingCost = 0.0;

        public Builder(String userId) {
            if (userId == null || userId.isBlank()) {
                throw new IllegalArgumentException("userId required");
            }
            this.userId = userId;
            this.orderId = "order-" + UUID.randomUUID().toString().substring(0, 8);
        }

        public Builder orderId(String orderId) {
            this.orderId = orderId;
            return this;
        }

        public Builder addItem(OrderItem item) {
            if (item == null) {
                throw new IllegalArgumentException("OrderItem cannot be null");
            }
            this.items.add(item);
            return this;
        }

        public Builder addItems(List<OrderItem> items) {
            this.items.addAll(items);
            return this;
        }

        public Builder tax(double tax) {
            this.tax = tax;
            return this;
        }

        public Builder shippingCost(double shippingCost) {
            this.shippingCost = shippingCost;
            return this;
        }

        /**
         * Validate and build the Order.
         *
         * Computes subtotal from items so it is never out of sync.
         * Initial status is always CREATED -- never passed in.
         */
        public Order build() {
            if (items.isEmpty()) {
                throw new IllegalStateException("Order must have at least one item");
            }
            // Compute subtotal from items -- single source of truth
            this.subtotal = items.stream()
                .mapToDouble(OrderItem::getSubtotal)
                .sum();
            return new Order(this);
        }
    }
}
```

---

### 4.5 OrderItem, Payment, Shipment, and Enums

```java
/**
 * A single line item in an order.
 *
 * OrderItem captures the price at the time of order creation (unitPrice),
 * not the current catalog price. This is critical: if the product price
 * changes after the order is placed, the customer still pays what they saw.
 */
public record OrderItem(
    String productId,
    int quantity,
    double unitPrice       // price locked at order creation time
) {
    public OrderItem {
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("productId required");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (unitPrice < 0) {
            throw new IllegalArgumentException("unitPrice cannot be negative");
        }
    }

    /** Line item subtotal. */
    public double getSubtotal() {
        return unitPrice * quantity;
    }
}
```

```java
/**
 * Order lifecycle states.
 *
 * This enum defines the nodes in the Order state machine.
 * The edges (transitions) are defined in Order.VALID_TRANSITIONS.
 *
 *   CREATED → INVENTORY_RESERVED → PAYMENT_PENDING → PAYMENT_CONFIRMED
 *           → SHIPPED → DELIVERED → REFUNDED
 *
 * CANCELLED is reachable from CREATED through PAYMENT_CONFIRMED.
 */
public enum OrderStatus {
    CREATED,
    INVENTORY_RESERVED,
    PAYMENT_PENDING,
    PAYMENT_CONFIRMED,
    SHIPPED,
    DELIVERED,
    CANCELLED,
    REFUNDED
}
```

```java
/**
 * Payment record associated with an order.
 *
 * Created by PaymentService after the payment strategy processes the charge.
 * Linked to the order via orderId.
 * transactionId is the external payment provider's reference.
 */
public class Payment {

    private final String paymentId;
    private final String orderId;
    private final double amount;
    private final PaymentMethod method;
    private PaymentStatus status;
    private String transactionId;           // from external payment gateway
    private final Instant createdAt;
    private Instant updatedAt;

    public Payment(String paymentId, String orderId, double amount,
                   PaymentMethod method) {
        this.paymentId = paymentId;
        this.orderId = orderId;
        this.amount = amount;
        this.method = method;
        this.status = PaymentStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void markProcessing(String transactionId) {
        this.transactionId = transactionId;
        this.status = PaymentStatus.PROCESSING;
        this.updatedAt = Instant.now();
    }

    public void markCompleted() {
        this.status = PaymentStatus.COMPLETED;
        this.updatedAt = Instant.now();
    }

    public void markFailed() {
        this.status = PaymentStatus.FAILED;
        this.updatedAt = Instant.now();
    }

    public void markRefunded() {
        this.status = PaymentStatus.REFUNDED;
        this.updatedAt = Instant.now();
    }

    // --- Getters ---
    public String getPaymentId() { return paymentId; }
    public String getOrderId() { return orderId; }
    public double getAmount() { return amount; }
    public PaymentMethod getMethod() { return method; }
    public PaymentStatus getStatus() { return status; }
    public String getTransactionId() { return transactionId; }
    public Instant getCreatedAt() { return createdAt; }
}
```

```java
public enum PaymentStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    REFUNDED
}

public enum PaymentMethod {
    CREDIT_CARD,
    DEBIT_CARD,
    WALLET,
    COD           // Cash on Delivery
}
```

```java
/**
 * Shipment tracking for an order.
 *
 * Created by ShippingService after payment is confirmed.
 * Tracks the physical delivery from warehouse to customer.
 */
public class Shipment {

    private final String shipmentId;
    private final String orderId;
    private String trackingId;
    private String carrier;
    private ShipmentStatus status;
    private final Instant createdAt;
    private Instant estimatedDelivery;
    private Instant actualDelivery;

    public Shipment(String shipmentId, String orderId, String carrier,
                    Instant estimatedDelivery) {
        this.shipmentId = shipmentId;
        this.orderId = orderId;
        this.carrier = carrier;
        this.status = ShipmentStatus.PREPARING;
        this.trackingId = "TRACK-" + UUID.randomUUID().toString().substring(0, 8);
        this.createdAt = Instant.now();
        this.estimatedDelivery = estimatedDelivery;
    }

    public void markShipped() {
        this.status = ShipmentStatus.SHIPPED;
    }

    public void markInTransit() {
        this.status = ShipmentStatus.IN_TRANSIT;
    }

    public void markDelivered() {
        this.status = ShipmentStatus.DELIVERED;
        this.actualDelivery = Instant.now();
    }

    // --- Getters ---
    public String getShipmentId() { return shipmentId; }
    public String getOrderId() { return orderId; }
    public String getTrackingId() { return trackingId; }
    public String getCarrier() { return carrier; }
    public ShipmentStatus getStatus() { return status; }
    public Instant getEstimatedDelivery() { return estimatedDelivery; }
    public Instant getActualDelivery() { return actualDelivery; }
}
```

```java
public enum ShipmentStatus {
    PREPARING,
    SHIPPED,
    IN_TRANSIT,
    DELIVERED
}
```

```java
/**
 * Records a single step in a saga execution.
 *
 * Used for observability: after a saga completes (success or failure),
 * you can inspect the SagaStep list to see what happened:
 *   - Which steps completed?
 *   - Which step failed?
 *   - Which steps were compensated?
 *
 * This is the "audit trail" for distributed transactions.
 */
public class SagaStep {

    public enum StepStatus { PENDING, COMPLETED, FAILED, COMPENSATED }

    private final String stepName;
    private StepStatus status;
    private final Instant timestamp;
    private String failureReason;

    public SagaStep(String stepName) {
        this.stepName = stepName;
        this.status = StepStatus.PENDING;
        this.timestamp = Instant.now();
    }

    public void markCompleted() { this.status = StepStatus.COMPLETED; }
    public void markFailed(String reason) {
        this.status = StepStatus.FAILED;
        this.failureReason = reason;
    }
    public void markCompensated() { this.status = StepStatus.COMPENSATED; }

    // --- Getters ---
    public String getStepName() { return stepName; }
    public StepStatus getStatus() { return status; }
    public Instant getTimestamp() { return timestamp; }
    public String getFailureReason() { return failureReason; }

    @Override
    public String toString() {
        return String.format("SagaStep[%s: %s%s]",
            stepName, status,
            failureReason != null ? " (" + failureReason + ")" : "");
    }
}
```

---

## 5. Interface Contracts

### 5.1 SagaOrchestrator

```java
/**
 * Orchestrates a multi-step distributed transaction (Saga).
 *
 * A Saga is a sequence of local transactions where each step has
 * a corresponding compensation (rollback) action. If step N fails,
 * steps N-1 through 1 are compensated in reverse order.
 *
 * WHY Saga instead of a single transaction?
 *   In microservices, you CANNOT use a single database transaction
 *   across Inventory DB, Payment Gateway, and Shipping Service.
 *   Each is a separate system. Saga coordinates them with
 *   compensation instead of rollback.
 *
 * Even in our monolith LLD, the Saga pattern demonstrates that
 * you understand distributed transaction design.
 *
 * Contract:
 *   - execute() runs all saga steps in order
 *   - If a step fails, all previous steps are compensated in reverse order
 *   - Returns a SagaResult with full execution trace
 */
public interface SagaOrchestrator {

    /**
     * Execute the saga for the given order.
     *
     * @param order the order to process
     * @return SagaResult with success/failure status and step details
     */
    SagaResult execute(Order order);

    /** Returns the name of this saga orchestrator (for logging). */
    String getOrchestratorName();
}
```

### 5.2 Repository Interfaces

```java
/**
 * Data access interface for products.
 *
 * Why an interface?
 *   - Decouples service layer from storage implementation
 *   - InMemoryProductRepository for LLD/testing
 *   - Could swap to JdbcProductRepository or DynamoProductRepository
 *   - Services depend on the interface, not the implementation (DIP)
 */
public interface ProductRepository {

    void save(Product product);
    Optional<Product> findById(String productId);
    List<Product> findByCategory(String category);
    List<Product> findAll();
    void deleteById(String productId);
    boolean existsById(String productId);
    long count();
}
```

```java
/**
 * Data access interface for orders.
 */
public interface OrderRepository {

    void save(Order order);
    Optional<Order> findById(String orderId);
    List<Order> findByUserId(String userId);
    List<Order> findByStatus(OrderStatus status);
    List<Order> findAll();
    long count();
}
```

```java
/**
 * Data access interface for inventory.
 *
 * Critical: InventoryService wraps calls to this repository
 * inside synchronized blocks. The repository itself is NOT
 * thread-safe -- thread safety is the service's responsibility.
 */
public interface InventoryRepository {

    void save(Inventory inventory);
    Optional<Inventory> findByProductId(String productId);
    List<Inventory> findAll();
    boolean existsByProductId(String productId);
}
```

```java
/**
 * Data access interface for shopping carts.
 */
public interface CartRepository {

    void save(Cart cart);
    Optional<Cart> findByUserId(String userId);
    void deleteByUserId(String userId);
    boolean existsByUserId(String userId);
}
```

```java
/**
 * Data access interface for payments.
 */
public interface PaymentRepository {

    void save(Payment payment);
    Optional<Payment> findById(String paymentId);
    List<Payment> findByOrderId(String orderId);
    List<Payment> findAll();
}
```

### 5.3 Strategy Interfaces

```java
/**
 * Strategy for calculating the total price of order items.
 *
 * WHY Strategy?
 *   - Different customers see different prices (standard, employee discount,
 *     prime member, bulk buyer, seasonal sale).
 *   - The pricing ALGORITHM changes, but the service layer code stays the same.
 *   - To add a new pricing rule, create a new PricingStrategy implementation.
 *     Do NOT modify OrderService or PaymentService.
 *
 * CALL CHAIN:
 *   OrderService.placeOrder()
 *     → PricingStrategy.calculatePrice(items)
 *     → returns computed total
 */
public interface PricingStrategy {

    /**
     * Calculate the total price for the given order items.
     *
     * @param items the order items to price
     * @return total price after applying this strategy's rules
     */
    double calculatePrice(List<OrderItem> items);

    /** Returns the name of this pricing strategy (for logging/display). */
    String getStrategyName();
}
```

```java
/**
 * Strategy for processing payments.
 *
 * WHY Strategy?
 *   - Different payment methods have different processing logic:
 *     Credit card: charge via gateway, get transactionId
 *     Wallet: deduct from balance atomically
 *     COD: mark as pending, confirm on delivery
 *   - PaymentService delegates to the strategy based on PaymentMethod.
 *   - Adding a new payment method (e.g., cryptocurrency) requires only
 *     a new PaymentStrategy implementation, not changes to PaymentService.
 *
 * CALL CHAIN:
 *   OrderSagaOrchestrator.processPayment()
 *     → PaymentService.processPayment(orderId, amount, method)
 *       → PaymentStrategy.processPayment(orderId, amount)
 *       → returns Payment object with status
 */
public interface PaymentStrategy {

    /**
     * Process a payment for the given order.
     *
     * @param orderId the order being paid for
     * @param amount the amount to charge
     * @return Payment object with transactionId and status
     * @throws PaymentFailedException if the payment cannot be processed
     */
    Payment processPayment(String orderId, double amount);

    /**
     * Refund a previously completed payment.
     *
     * @param payment the payment to refund
     * @return true if refund succeeded
     */
    boolean refundPayment(Payment payment);

    /** Returns the name of this payment method (for logging/display). */
    String getPaymentMethodName();
}
```

```java
/**
 * Strategy for calculating shipping cost and creating shipments.
 *
 * WHY Strategy?
 *   - Standard shipping: 5-7 days, low cost
 *   - Express shipping: 1-2 days, higher cost
 *   - Future: same-day, drone delivery, pickup-in-store
 *   - ShippingService delegates to the strategy without knowing the details.
 */
public interface ShippingStrategy {

    /**
     * Create a shipment for the given order.
     *
     * @param order the order to ship
     * @return Shipment with tracking ID, carrier, and estimated delivery
     */
    Shipment createShipment(Order order);

    /**
     * Calculate the shipping cost for the given order.
     *
     * @param order the order to calculate shipping for
     * @return shipping cost in dollars
     */
    double calculateCost(Order order);

    /** Estimated delivery time in days. */
    int getEstimatedDays();

    /** Returns the name of this shipping strategy. */
    String getStrategyName();
}
```

---

## 6. Strategy Implementations

### 6.1 Pricing Strategies

```java
/**
 * Standard pricing: simply sums up unitPrice * quantity for each item.
 *
 * No discounts, no special rules. This is the baseline.
 * Used for: regular customers with no promotions.
 *
 * CALL CHAIN:
 *   OrderService.placeOrder()
 *     → pricingStrategy.calculatePrice(items)
 *     → StandardPricingStrategy: sum(unitPrice * qty)
 */
public class StandardPricingStrategy implements PricingStrategy {

    @Override
    public double calculatePrice(List<OrderItem> items) {
        // Simple sum: each item's subtotal = unitPrice * quantity
        return items.stream()
            .mapToDouble(item -> item.unitPrice() * item.quantity())
            .sum();
    }

    @Override
    public String getStrategyName() {
        return "STANDARD";
    }
}
```

```java
/**
 * Discount pricing: applies bulk, seasonal, and coupon discounts.
 *
 * Discount rules (applied in order):
 *   1. BULK discount: if any single item quantity >= bulkThreshold,
 *      that item gets bulkDiscountPercent off.
 *   2. SEASONAL discount: if seasonal sale is active, apply
 *      seasonalDiscountPercent to the entire order.
 *
 * WHY separate from StandardPricingStrategy?
 *   - Open-Closed Principle: adding a new discount type does NOT
 *     modify StandardPricingStrategy.
 *   - Each strategy is independently testable.
 *   - The service just calls calculatePrice() -- it does not know
 *     whether discounts are applied or not.
 *
 * INTERVIEW TIP: The interviewer may ask "how would you add a coupon
 * code system?" Answer: add a coupon parameter to calculatePrice()
 * or create a CouponPricingStrategy that wraps another strategy
 * (Decorator pattern on top of Strategy).
 */
public class DiscountPricingStrategy implements PricingStrategy {

    private final int bulkThreshold;              // e.g., 10 units
    private final double bulkDiscountPercent;      // e.g., 0.10 = 10%
    private final double seasonalDiscountPercent;  // e.g., 0.05 = 5%
    private final boolean seasonalSaleActive;

    public DiscountPricingStrategy(int bulkThreshold, double bulkDiscountPercent,
                                    double seasonalDiscountPercent,
                                    boolean seasonalSaleActive) {
        this.bulkThreshold = bulkThreshold;
        this.bulkDiscountPercent = bulkDiscountPercent;
        this.seasonalDiscountPercent = seasonalDiscountPercent;
        this.seasonalSaleActive = seasonalSaleActive;
    }

    @Override
    public double calculatePrice(List<OrderItem> items) {
        double total = 0.0;

        for (OrderItem item : items) {
            double itemTotal = item.unitPrice() * item.quantity();

            // Rule 1: Bulk discount for high-quantity items
            if (item.quantity() >= bulkThreshold) {
                double discount = itemTotal * bulkDiscountPercent;
                itemTotal -= discount;
                System.out.printf("  [Discount] Bulk discount on %s: -$%.2f (qty=%d >= %d)%n",
                    item.productId(), discount, item.quantity(), bulkThreshold);
            }

            total += itemTotal;
        }

        // Rule 2: Seasonal discount on entire order
        if (seasonalSaleActive) {
            double seasonalDiscount = total * seasonalDiscountPercent;
            total -= seasonalDiscount;
            System.out.printf("  [Discount] Seasonal discount: -$%.2f (%.0f%% off)%n",
                seasonalDiscount, seasonalDiscountPercent * 100);
        }

        return total;
    }

    @Override
    public String getStrategyName() {
        return "DISCOUNT";
    }
}
```

---

### 6.2 Payment Strategies

```java
/**
 * Processes credit card payments via an external gateway (simulated).
 *
 * In a real system, this would call Stripe/PayPal/Adyen API.
 * For our LLD, we simulate success/failure based on amount thresholds.
 *
 * CALL CHAIN:
 *   OrderSagaOrchestrator.processPayment()
 *     → PaymentService.processPayment(orderId, amount, CREDIT_CARD)
 *       → CreditCardPaymentStrategy.processPayment(orderId, amount)
 *         → simulate gateway call
 *         → return Payment with transactionId
 */
public class CreditCardPaymentStrategy implements PaymentStrategy {

    /**
     * Simulated failure threshold: payments over $10,000 fail.
     * In a real system, this would be the gateway response.
     */
    private static final double FAILURE_THRESHOLD = 10_000.00;

    @Override
    public Payment processPayment(String orderId, double amount) {
        System.out.printf("  [CreditCard] Processing $%.2f for order %s%n", amount, orderId);

        Payment payment = new Payment(
            "pay-" + UUID.randomUUID().toString().substring(0, 8),
            orderId, amount, PaymentMethod.CREDIT_CARD
        );

        // Simulate gateway processing
        if (amount > FAILURE_THRESHOLD) {
            payment.markFailed();
            throw new PaymentFailedException(
                "Credit card payment failed for order " + orderId
                + ": amount $" + amount + " exceeds limit"
            );
        }

        // Simulate successful charge
        String transactionId = "TXN-CC-" + UUID.randomUUID().toString().substring(0, 8);
        payment.markProcessing(transactionId);
        payment.markCompleted();

        System.out.printf("  [CreditCard] Payment completed: %s%n", transactionId);
        return payment;
    }

    @Override
    public boolean refundPayment(Payment payment) {
        System.out.printf("  [CreditCard] Refunding $%.2f for txn %s%n",
            payment.getAmount(), payment.getTransactionId());
        payment.markRefunded();
        return true;
    }

    @Override
    public String getPaymentMethodName() {
        return "CREDIT_CARD";
    }
}
```

```java
/**
 * Processes wallet payments by deducting from an in-memory wallet balance.
 *
 * Simulates a digital wallet (like Amazon Pay balance).
 * Balance checks are done before deduction.
 *
 * Thread safety: wallet balance modifications should be synchronized
 * in a real system. For this LLD, PaymentService serializes calls.
 */
public class WalletPaymentStrategy implements PaymentStrategy {

    /** userId → wallet balance. Pre-loaded in AppConfig. */
    private final Map<String, Double> walletBalances;

    public WalletPaymentStrategy(Map<String, Double> walletBalances) {
        this.walletBalances = walletBalances;
    }

    @Override
    public Payment processPayment(String orderId, double amount) {
        System.out.printf("  [Wallet] Processing $%.2f for order %s%n", amount, orderId);

        // In a real system, we would look up userId from the order.
        // For simplicity, we use a default wallet.
        String userId = "default-user";

        Payment payment = new Payment(
            "pay-" + UUID.randomUUID().toString().substring(0, 8),
            orderId, amount, PaymentMethod.WALLET
        );

        double currentBalance = walletBalances.getOrDefault(userId, 0.0);
        if (currentBalance < amount) {
            payment.markFailed();
            throw new PaymentFailedException(
                "Wallet payment failed: insufficient balance. "
                + "Current: $" + currentBalance + ", Required: $" + amount
            );
        }

        // Deduct from wallet
        walletBalances.put(userId, currentBalance - amount);

        String transactionId = "TXN-WAL-" + UUID.randomUUID().toString().substring(0, 8);
        payment.markProcessing(transactionId);
        payment.markCompleted();

        System.out.printf("  [Wallet] Payment completed. New balance: $%.2f%n",
            walletBalances.get(userId));
        return payment;
    }

    @Override
    public boolean refundPayment(Payment payment) {
        System.out.printf("  [Wallet] Refunding $%.2f to wallet%n", payment.getAmount());
        String userId = "default-user";
        double currentBalance = walletBalances.getOrDefault(userId, 0.0);
        walletBalances.put(userId, currentBalance + payment.getAmount());
        payment.markRefunded();
        return true;
    }

    @Override
    public String getPaymentMethodName() {
        return "WALLET";
    }
}
```

```java
/**
 * Cash on Delivery: no upfront payment processing.
 *
 * Payment is marked as PENDING and only confirmed when the
 * delivery driver collects cash. In our saga, COD always
 * "succeeds" because there is nothing to charge upfront.
 *
 * INTERVIEW TIP: COD is interesting because it changes the
 * saga compensation logic. If shipping fails for a COD order,
 * there is no payment to refund. The compensation is simpler.
 */
public class CODPaymentStrategy implements PaymentStrategy {

    @Override
    public Payment processPayment(String orderId, double amount) {
        System.out.printf("  [COD] Marking $%.2f as Cash on Delivery for order %s%n",
            amount, orderId);

        Payment payment = new Payment(
            "pay-" + UUID.randomUUID().toString().substring(0, 8),
            orderId, amount, PaymentMethod.COD
        );

        // COD does not charge upfront. Mark as processing (to be collected).
        String transactionId = "TXN-COD-" + UUID.randomUUID().toString().substring(0, 8);
        payment.markProcessing(transactionId);
        // Note: NOT marking as COMPLETED. That happens on delivery.

        System.out.printf("  [COD] Payment pending collection: %s%n", transactionId);
        return payment;
    }

    @Override
    public boolean refundPayment(Payment payment) {
        // COD: nothing to refund since nothing was charged.
        System.out.printf("  [COD] No refund needed (cash not yet collected)%n");
        payment.markRefunded();
        return true;
    }

    @Override
    public String getPaymentMethodName() {
        return "COD";
    }
}
```

---

### 6.3 Shipping Strategies

```java
/**
 * Standard shipping: 5-7 business days, base cost + per-item cost.
 *
 * Cost formula: baseCost + (itemCount * perItemCost)
 * Example: $5.00 + (3 items * $1.00) = $8.00
 */
public class StandardShippingStrategy implements ShippingStrategy {

    private static final double BASE_COST = 5.00;
    private static final double PER_ITEM_COST = 1.00;
    private static final int ESTIMATED_DAYS = 6;  // average of 5-7
    private static final String CARRIER = "StandardPost";

    @Override
    public Shipment createShipment(Order order) {
        Instant estimatedDelivery = Instant.now()
            .plus(Duration.ofDays(ESTIMATED_DAYS));

        Shipment shipment = new Shipment(
            "ship-" + UUID.randomUUID().toString().substring(0, 8),
            order.getOrderId(),
            CARRIER,
            estimatedDelivery
        );

        System.out.printf("  [StandardShipping] Shipment created: %s, ETA: %d days%n",
            shipment.getTrackingId(), ESTIMATED_DAYS);
        return shipment;
    }

    @Override
    public double calculateCost(Order order) {
        int itemCount = order.getItems().stream()
            .mapToInt(OrderItem::quantity)
            .sum();
        return BASE_COST + (itemCount * PER_ITEM_COST);
    }

    @Override
    public int getEstimatedDays() {
        return ESTIMATED_DAYS;
    }

    @Override
    public String getStrategyName() {
        return "STANDARD_SHIPPING";
    }
}
```

```java
/**
 * Express shipping: 1-2 business days, higher base cost + priority fee.
 *
 * Cost formula: baseCost + (itemCount * perItemCost) + priorityFee
 * Example: $15.00 + (3 items * $3.00) + $5.00 = $29.00
 */
public class ExpressShippingStrategy implements ShippingStrategy {

    private static final double BASE_COST = 15.00;
    private static final double PER_ITEM_COST = 3.00;
    private static final double PRIORITY_FEE = 5.00;
    private static final int ESTIMATED_DAYS = 2;  // average of 1-2
    private static final String CARRIER = "ExpressCourier";

    @Override
    public Shipment createShipment(Order order) {
        Instant estimatedDelivery = Instant.now()
            .plus(Duration.ofDays(ESTIMATED_DAYS));

        Shipment shipment = new Shipment(
            "ship-" + UUID.randomUUID().toString().substring(0, 8),
            order.getOrderId(),
            CARRIER,
            estimatedDelivery
        );

        System.out.printf("  [ExpressShipping] Shipment created: %s, ETA: %d days%n",
            shipment.getTrackingId(), ESTIMATED_DAYS);
        return shipment;
    }

    @Override
    public double calculateCost(Order order) {
        int itemCount = order.getItems().stream()
            .mapToInt(OrderItem::quantity)
            .sum();
        return BASE_COST + (itemCount * PER_ITEM_COST) + PRIORITY_FEE;
    }

    @Override
    public int getEstimatedDays() {
        return ESTIMATED_DAYS;
    }

    @Override
    public String getStrategyName() {
        return "EXPRESS_SHIPPING";
    }
}
```

---

## 7. Service Layer Design

### 7.1 OrderSagaOrchestrator (THE Key Class)

> **This is the single most important class in the system.** It coordinates the checkout saga: reserve inventory, process payment, create shipment. If ANY step fails, all previously completed steps are compensated in reverse order. This is how real e-commerce systems work.

```
     ┌──────────────────────────────────────────────────────────────────┐
     │           SAGA ORCHESTRATION: The Core Checkout Flow              │
     │                                                                  │
     │   Step 1: RESERVE_INVENTORY                                     │
     │     → InventoryService.reserve(productId, qty) for each item    │
     │     → On failure: DONE (nothing to compensate)                  │
     │                                                                  │
     │   Step 2: PROCESS_PAYMENT                                       │
     │     → PaymentService.processPayment(orderId, totalAmount)       │
     │     → On failure: compensate Step 1 (release inventory)         │
     │                                                                  │
     │   Step 3: CREATE_SHIPMENT                                       │
     │     → ShippingService.createShipment(order)                     │
     │     → On failure: compensate Step 2 (refund)                    │
     │                    compensate Step 1 (release inventory)         │
     │                                                                  │
     │   All steps succeeded: order is PAYMENT_CONFIRMED + shipment    │
     │                                                                  │
     │   COMPENSATION ORDER: always reverse!                            │
     │     If Step 3 fails: undo Step 2, then undo Step 1              │
     │     If Step 2 fails: undo Step 1                                │
     │     If Step 1 fails: nothing to undo                            │
     └──────────────────────────────────────────────────────────────────┘
```

#### Anti-Pattern: No Compensation Logic

```
     ┌──────────────────────────────────────────────────────────────────┐
     │           ANTI-PATTERN: Fire-and-Forget Checkout                  │
     │                                                                  │
     │   // DANGEROUS: no rollback if a middle step fails               │
     │   public void checkout(Order order) {                           │
     │       inventoryService.reserve(order);    // Step 1: OK         │
     │       paymentService.charge(order);       // Step 2: FAILS!     │
     │       shippingService.ship(order);        // Step 3: never runs │
     │       // Step 2 threw an exception...                           │
     │       // But Step 1 already reserved the stock!                 │
     │       // Stock is now PERMANENTLY locked. Ghost reservation.    │
     │   }                                                             │
     │                                                                  │
     │   PROBLEMS:                                                      │
     │     1. Inventory reserved but never released (ghost reservation) │
     │     2. Customer cannot re-order (stock appears "out of stock")   │
     │     3. No audit trail of what happened                          │
     │     4. No way to retry or manually fix the order                │
     │     5. In production: customer support nightmare                 │
     └──────────────────────────────────────────────────────────────────┘
```

#### Clean Solution: Saga with Compensation

```java
/**
 * Orchestrates the order checkout saga.
 *
 * THE most important class in the e-commerce system.
 * Coordinates three steps with compensation on failure:
 *
 *   1. Reserve Inventory  (compensate: release inventory)
 *   2. Process Payment    (compensate: refund payment)
 *   3. Create Shipment    (compensate: cancel shipment)
 *
 * Each step:
 *   a. Creates a SagaStep record (PENDING)
 *   b. Executes the action
 *   c. Marks the SagaStep as COMPLETED
 *   d. Transitions the Order state machine
 *
 * On failure at step N:
 *   a. Marks step N as FAILED
 *   b. Iterates steps N-1 down to 1 in reverse order
 *   c. Calls the compensation action for each completed step
 *   d. Marks each compensated step as COMPENSATED
 *   e. Transitions the Order to CANCELLED
 *
 * CALL CHAIN (happy path):
 *   OrderService.placeOrder(userId, paymentMethod)
 *     → OrderSagaOrchestrator.execute(order)
 *       → reserveInventory(order)
 *         → order.transitionTo(INVENTORY_RESERVED)
 *       → processPayment(order)
 *         → order.transitionTo(PAYMENT_PENDING)
 *         → order.transitionTo(PAYMENT_CONFIRMED)
 *       → createShipment(order)
 *         → order.transitionTo(SHIPPED)
 *     → return SagaResult.success(completedSteps)
 *
 * CALL CHAIN (failure at payment):
 *   OrderService.placeOrder(userId, paymentMethod)
 *     → OrderSagaOrchestrator.execute(order)
 *       → reserveInventory(order)         ✓ COMPLETED
 *       → processPayment(order)           ✗ FAILED
 *       → compensateInventory(order)      ↩ COMPENSATED
 *       → order.transitionTo(CANCELLED)
 *     → return SagaResult.failure(failedStep, compensatedSteps)
 */
public class OrderSagaOrchestrator implements SagaOrchestrator {

    private final InventoryService inventoryService;
    private final PaymentService paymentService;
    private final ShippingService shippingService;
    private final NotificationService notificationService;

    /**
     * Constructor injection: all dependencies are provided by AppConfig.
     * No framework, no annotations -- pure constructor injection.
     */
    public OrderSagaOrchestrator(InventoryService inventoryService,
                                  PaymentService paymentService,
                                  ShippingService shippingService,
                                  NotificationService notificationService) {
        this.inventoryService = inventoryService;
        this.paymentService = paymentService;
        this.shippingService = shippingService;
        this.notificationService = notificationService;
    }

    /**
     * Execute the full checkout saga.
     *
     * This method is the heart of the system. Study this carefully.
     *
     * Note the try-catch structure:
     *   - Each step is wrapped in its own try block
     *   - On failure, we jump to compensation
     *   - Compensation runs in REVERSE ORDER of completed steps
     *   - We never partially compensate: it is all-or-nothing
     */
    @Override
    public SagaResult execute(Order order) {
        System.out.println("\n========== SAGA START: Order " + order.getOrderId() + " ==========");

        List<com.systemdesign.ecommerce.model.SagaStep> completedSteps = new ArrayList<>();
        Payment payment = null;
        Shipment shipment = null;

        // ─────────────── STEP 1: RESERVE INVENTORY ───────────────
        com.systemdesign.ecommerce.model.SagaStep step1 =
            new com.systemdesign.ecommerce.model.SagaStep("RESERVE_INVENTORY");
        try {
            System.out.println("[Saga] Step 1: Reserving inventory...");
            reserveInventory(order);
            step1.markCompleted();
            completedSteps.add(step1);
            order.addSagaStep(step1);
            order.transitionTo(OrderStatus.INVENTORY_RESERVED);
            System.out.println("[Saga] Step 1: COMPLETED");
        } catch (Exception e) {
            // Step 1 failed: nothing to compensate
            System.out.println("[Saga] Step 1: FAILED - " + e.getMessage());
            step1.markFailed(e.getMessage());
            order.addSagaStep(step1);
            order.transitionTo(OrderStatus.CANCELLED);
            notificationService.notifyOrderFailed(order, e.getMessage());
            return SagaResult.failure("RESERVE_INVENTORY", e.getMessage(),
                completedSteps, List.of());
        }

        // ─────────────── STEP 2: PROCESS PAYMENT ───────────────
        com.systemdesign.ecommerce.model.SagaStep step2 =
            new com.systemdesign.ecommerce.model.SagaStep("PROCESS_PAYMENT");
        try {
            System.out.println("[Saga] Step 2: Processing payment...");
            order.transitionTo(OrderStatus.PAYMENT_PENDING);
            payment = processPayment(order);
            order.setPayment(payment);
            step2.markCompleted();
            completedSteps.add(step2);
            order.addSagaStep(step2);
            order.transitionTo(OrderStatus.PAYMENT_CONFIRMED);
            System.out.println("[Saga] Step 2: COMPLETED");
        } catch (Exception e) {
            // Step 2 failed: compensate Step 1 (release inventory)
            System.out.println("[Saga] Step 2: FAILED - " + e.getMessage());
            step2.markFailed(e.getMessage());
            order.addSagaStep(step2);

            System.out.println("[Saga] Compensating Step 1: Releasing inventory...");
            compensateInventory(order);
            step1.markCompensated();

            order.transitionTo(OrderStatus.CANCELLED);
            notificationService.notifyOrderFailed(order, e.getMessage());
            return SagaResult.failure("PROCESS_PAYMENT", e.getMessage(),
                completedSteps, List.of("RESERVE_INVENTORY"));
        }

        // ─────────────── STEP 3: CREATE SHIPMENT ───────────────
        com.systemdesign.ecommerce.model.SagaStep step3 =
            new com.systemdesign.ecommerce.model.SagaStep("CREATE_SHIPMENT");
        try {
            System.out.println("[Saga] Step 3: Creating shipment...");
            shipment = createShipment(order);
            order.setShipment(shipment);
            step3.markCompleted();
            completedSteps.add(step3);
            order.addSagaStep(step3);
            order.transitionTo(OrderStatus.SHIPPED);
            System.out.println("[Saga] Step 3: COMPLETED");
        } catch (Exception e) {
            // Step 3 failed: compensate Step 2 (refund) then Step 1 (release)
            System.out.println("[Saga] Step 3: FAILED - " + e.getMessage());
            step3.markFailed(e.getMessage());
            order.addSagaStep(step3);

            System.out.println("[Saga] Compensating Step 2: Refunding payment...");
            compensatePayment(payment);
            step2.markCompensated();

            System.out.println("[Saga] Compensating Step 1: Releasing inventory...");
            compensateInventory(order);
            step1.markCompensated();

            order.transitionTo(OrderStatus.CANCELLED);
            notificationService.notifyOrderFailed(order, e.getMessage());
            return SagaResult.failure("CREATE_SHIPMENT", e.getMessage(),
                completedSteps, List.of("PROCESS_PAYMENT", "RESERVE_INVENTORY"));
        }

        // ─────────────── ALL STEPS COMPLETED ───────────────
        System.out.println("========== SAGA SUCCESS: Order " + order.getOrderId() + " ==========\n");

        // Confirm inventory (convert reservation to actual stock reduction)
        confirmInventory(order);
        notificationService.notifyOrderConfirmed(order);

        return SagaResult.success(completedSteps);
    }

    // ======================== STEP IMPLEMENTATIONS ========================

    /**
     * Step 1 action: reserve inventory for all items in the order.
     * Calls InventoryService.reserve() for each OrderItem.
     * If ANY item fails to reserve, throws InsufficientStockException.
     */
    private void reserveInventory(Order order) {
        for (OrderItem item : order.getItems()) {
            inventoryService.reserve(item.productId(), item.quantity());
        }
    }

    /**
     * Step 2 action: process payment for the order total.
     * Delegates to PaymentService which selects the appropriate PaymentStrategy.
     */
    private Payment processPayment(Order order) {
        return paymentService.processPayment(
            order.getOrderId(),
            order.getTotalAmount(),
            order.getPayment() != null ? order.getPayment().getMethod() : PaymentMethod.CREDIT_CARD
        );
    }

    /**
     * Step 3 action: create a shipment for the order.
     * Delegates to ShippingService which uses the configured ShippingStrategy.
     */
    private Shipment createShipment(Order order) {
        return shippingService.createShipment(order);
    }

    // ======================== COMPENSATION ACTIONS ========================

    /**
     * Compensation for Step 1: release all reserved inventory.
     *
     * CRITICAL: compensation must be IDEMPOTENT.
     * If this is called twice (e.g., retry logic), it should not fail.
     * InventoryService.release() handles this by checking reservedStock.
     */
    private void compensateInventory(Order order) {
        for (OrderItem item : order.getItems()) {
            try {
                inventoryService.release(item.productId(), item.quantity());
                System.out.printf("  [Compensate] Released %d units of %s%n",
                    item.quantity(), item.productId());
            } catch (Exception e) {
                // Log but do not re-throw: compensation must be best-effort
                System.err.printf("  [Compensate] WARNING: Failed to release %s: %s%n",
                    item.productId(), e.getMessage());
            }
        }
    }

    /**
     * Compensation for Step 2: refund the payment.
     */
    private void compensatePayment(Payment payment) {
        if (payment != null) {
            try {
                paymentService.refundPayment(payment);
                System.out.printf("  [Compensate] Refunded $%.2f for payment %s%n",
                    payment.getAmount(), payment.getPaymentId());
            } catch (Exception e) {
                System.err.printf("  [Compensate] WARNING: Failed to refund %s: %s%n",
                    payment.getPaymentId(), e.getMessage());
            }
        }
    }

    /**
     * Confirm inventory: convert reservations to actual stock reductions.
     * Called ONLY after all saga steps succeed.
     */
    private void confirmInventory(Order order) {
        for (OrderItem item : order.getItems()) {
            inventoryService.confirm(item.productId(), item.quantity());
        }
    }

    @Override
    public String getOrchestratorName() {
        return "OrderSagaOrchestrator";
    }
}
```

---

### 7.2 SagaResult

```java
/**
 * Captures the outcome of a saga execution.
 *
 * Contains:
 *   - success: did all steps complete?
 *   - completedSteps: which steps finished successfully?
 *   - failedStep: which step caused the failure? (null if success)
 *   - failureReason: why did it fail? (null if success)
 *   - compensatedSteps: which steps were rolled back?
 *
 * This is the "receipt" of a saga execution. Used by OrderService
 * to decide what to tell the customer.
 */
public class SagaResult {

    private final boolean success;
    private final String failedStep;
    private final String failureReason;
    private final List<com.systemdesign.ecommerce.model.SagaStep> completedSteps;
    private final List<String> compensatedSteps;

    private SagaResult(boolean success, String failedStep, String failureReason,
                       List<com.systemdesign.ecommerce.model.SagaStep> completedSteps,
                       List<String> compensatedSteps) {
        this.success = success;
        this.failedStep = failedStep;
        this.failureReason = failureReason;
        this.completedSteps = completedSteps;
        this.compensatedSteps = compensatedSteps;
    }

    /** Factory method for successful saga completion. */
    public static SagaResult success(
            List<com.systemdesign.ecommerce.model.SagaStep> completedSteps) {
        return new SagaResult(true, null, null, completedSteps, List.of());
    }

    /** Factory method for failed saga with compensation trace. */
    public static SagaResult failure(String failedStep, String reason,
            List<com.systemdesign.ecommerce.model.SagaStep> completedSteps,
            List<String> compensatedSteps) {
        return new SagaResult(false, failedStep, reason, completedSteps, compensatedSteps);
    }

    // --- Getters ---
    public boolean isSuccess() { return success; }
    public String getFailedStep() { return failedStep; }
    public String getFailureReason() { return failureReason; }
    public List<com.systemdesign.ecommerce.model.SagaStep> getCompletedSteps() {
        return completedSteps;
    }
    public List<String> getCompensatedSteps() { return compensatedSteps; }

    @Override
    public String toString() {
        if (success) {
            return "SagaResult[SUCCESS, steps=" + completedSteps.size() + "]";
        }
        return String.format("SagaResult[FAILED at %s: %s, compensated=%s]",
            failedStep, failureReason, compensatedSteps);
    }
}
```

---

### 7.3 InventoryService (Thread-Safe Reserve/Confirm/Release)

> **This is the most concurrency-sensitive class.** Multiple threads (concurrent checkout requests) may try to reserve the same product simultaneously. We use synchronized blocks keyed by productId to ensure atomicity.

```
     ┌──────────────────────────────────────────────────────────────────┐
     │           INVENTORY SERVICE: Thread-Safety Model                 │
     │                                                                  │
     │   Thread A: reserve("laptop", 1)    Thread B: reserve("laptop", 1)
     │         │                                   │                   │
     │         ▼                                   ▼                   │
     │   synchronized(lockFor("laptop"))     BLOCKED (waiting)         │
     │     → check available >= 1  ✓         ...                       │
     │     → reservedStock += 1              ...                       │
     │     → exit synchronized               ...                       │
     │                                       synchronized(lockFor("laptop"))
     │                                         → check available >= 1  │
     │                                         → reservedStock += 1    │
     │                                         → exit synchronized     │
     │                                                                  │
     │   WHY per-product locks (not a single global lock)?             │
     │     - Two users buying different products should NOT block       │
     │       each other. Only same-product reservations need to         │
     │       be serialized.                                            │
     │     - Global lock: reserve("laptop") blocks reserve("mouse")    │
     │     - Per-product lock: only reserve("laptop") blocks another   │
     │       reserve("laptop"). reserve("mouse") runs in parallel.     │
     └──────────────────────────────────────────────────────────────────┘
```

#### Anti-Pattern: Unsynchronized Inventory

```
     ┌──────────────────────────────────────────────────────────────────┐
     │           ANTI-PATTERN: No Synchronization                       │
     │                                                                  │
     │   public void reserve(String productId, int qty) {              │
     │       Inventory inv = repo.findByProductId(productId).get();    │
     │       // Thread A: available = 1                                │
     │       // Thread B: available = 1 (same stale read!)             │
     │       inv.reserve(qty);  // both succeed → oversold!            │
     │       repo.save(inv);                                           │
     │   }                                                             │
     │                                                                  │
     │   RACE CONDITION TIMELINE:                                      │
     │     T0: Thread A reads available=1                              │
     │     T1: Thread B reads available=1  (stale!)                    │
     │     T2: Thread A reserves → available=0, reserved=1             │
     │     T3: Thread B reserves → available=-1, reserved=2  BUG!     │
     │                                                                  │
     │   Result: reservedStock > totalStock. Inventory is negative.    │
     └──────────────────────────────────────────────────────────────────┘
```

#### Clean Solution: Per-Product Locking

```java
/**
 * Manages inventory with thread-safe reserve/confirm/release operations.
 *
 * Uses per-product locking via ConcurrentHashMap of lock objects.
 * This allows maximum concurrency: different products can be reserved
 * in parallel, only same-product operations are serialized.
 *
 * THREE operations (all synchronized per productId):
 *   reserve(productId, qty)  — hold stock for pending order
 *   confirm(productId, qty)  — order completed, consume reserved stock
 *   release(productId, qty)  — order cancelled, return reserved stock
 *
 * CALL CHAIN:
 *   OrderSagaOrchestrator.execute()
 *     → reserveInventory(order)
 *       → InventoryService.reserve("laptop", 2) [synchronized on "laptop" lock]
 *         → Inventory.reserve(2) [check + mutate under lock]
 *       → InventoryService.reserve("mouse", 1) [synchronized on "mouse" lock]
 *         → Inventory.reserve(1) [runs in parallel with "laptop" lock]
 */
public class InventoryService {

    private final InventoryRepository repository;

    /**
     * Per-product lock objects.
     *
     * WHY ConcurrentHashMap + computeIfAbsent?
     *   - ConcurrentHashMap is thread-safe for reads and writes
     *   - computeIfAbsent atomically creates the lock object on first access
     *   - Each productId gets its own lock object
     *   - Different products → different locks → no contention
     *   - Same product → same lock → serialized access
     *
     * WHY Object (not ReentrantLock)?
     *   For this interview scope, synchronized + Object is simpler.
     *   ReentrantLock adds tryLock() and fairness, which we do not need here.
     */
    private final ConcurrentHashMap<String, Object> productLocks = new ConcurrentHashMap<>();

    public InventoryService(InventoryRepository repository) {
        this.repository = repository;
    }

    /** Get or create the lock object for a product. */
    private Object getLock(String productId) {
        return productLocks.computeIfAbsent(productId, k -> new Object());
    }

    /**
     * Reserve qty units of a product.
     *
     * Thread-safe: synchronized on per-product lock.
     * The entire check-then-act sequence is atomic.
     *
     * @throws InsufficientStockException if not enough available stock
     */
    public void reserve(String productId, int qty) {
        // Synchronize on the lock for THIS product only.
        // Other products are not blocked.
        synchronized (getLock(productId)) {
            Inventory inventory = repository.findByProductId(productId)
                .orElseThrow(() -> new ECommerceException(
                    "No inventory record for product: " + productId));

            // Inventory.reserve() checks availableStock >= qty
            // and throws InsufficientStockException if not.
            inventory.reserve(qty);
            repository.save(inventory);

            System.out.printf("  [Inventory] Reserved %d of %s (available: %d)%n",
                qty, productId, inventory.getAvailableStock());
        }
    }

    /**
     * Confirm a reservation: stock is truly consumed.
     * Called after payment succeeds.
     */
    public void confirm(String productId, int qty) {
        synchronized (getLock(productId)) {
            Inventory inventory = repository.findByProductId(productId)
                .orElseThrow(() -> new ECommerceException(
                    "No inventory record for product: " + productId));

            inventory.confirmReservation(qty);
            repository.save(inventory);

            System.out.printf("  [Inventory] Confirmed %d of %s (total: %d)%n",
                qty, productId, inventory.getTotalStock());
        }
    }

    /**
     * Release a reservation: stock goes back to available.
     * Called when order is cancelled or payment fails.
     */
    public void release(String productId, int qty) {
        synchronized (getLock(productId)) {
            Inventory inventory = repository.findByProductId(productId)
                .orElseThrow(() -> new ECommerceException(
                    "No inventory record for product: " + productId));

            inventory.releaseReservation(qty);
            repository.save(inventory);

            System.out.printf("  [Inventory] Released %d of %s (available: %d)%n",
                qty, productId, inventory.getAvailableStock());
        }
    }

    /**
     * Check available stock without modifying it.
     * Read-only: does NOT require synchronization (eventual consistency is OK for display).
     */
    public int getAvailableStock(String productId) {
        return repository.findByProductId(productId)
            .map(Inventory::getAvailableStock)
            .orElse(0);
    }

    /** Get full inventory details for a product. */
    public Optional<Inventory> getInventory(String productId) {
        return repository.findByProductId(productId);
    }
}
```

---

### 7.4 OrderService (Facade)

> **The Facade.** This is the single entry point for all order-related operations. It orchestrates CartService, PricingStrategy, and OrderSagaOrchestrator. The controller never talks to individual services directly.

```java
/**
 * Facade for the e-commerce order workflow.
 *
 * Orchestrates:
 *   1. Cart retrieval and validation
 *   2. Price calculation via PricingStrategy
 *   3. Order creation via Builder
 *   4. Checkout via OrderSagaOrchestrator
 *   5. Notification via NotificationService
 *
 * The controller calls OrderService methods.
 * OrderService delegates to the appropriate services.
 * Individual services never know about each other.
 *
 * CALL CHAIN (placeOrder):
 *   ECommerceController.placeOrder(userId, paymentMethod)
 *     → OrderService.placeOrder(userId, paymentMethod)
 *       → CartService.getCart(userId)                    // get cart
 *       → PricingStrategy.calculatePrice(items)          // price items
 *       → ShippingStrategy.calculateCost(order)          // shipping cost
 *       → Order.Builder(userId).addItems(items).build()  // create order
 *       → OrderRepository.save(order)                    // persist
 *       → OrderSagaOrchestrator.execute(order)           // checkout saga
 *       → CartService.clearCart(userId)                   // clear cart
 *       → return order
 */
public class OrderService {

    private final CartService cartService;
    private final ProductService productService;
    private final PricingStrategy pricingStrategy;
    private final ShippingStrategy shippingStrategy;
    private final OrderRepository orderRepository;
    private final SagaOrchestrator sagaOrchestrator;
    private final NotificationService notificationService;

    public OrderService(CartService cartService,
                        ProductService productService,
                        PricingStrategy pricingStrategy,
                        ShippingStrategy shippingStrategy,
                        OrderRepository orderRepository,
                        SagaOrchestrator sagaOrchestrator,
                        NotificationService notificationService) {
        this.cartService = cartService;
        this.productService = productService;
        this.pricingStrategy = pricingStrategy;
        this.shippingStrategy = shippingStrategy;
        this.orderRepository = orderRepository;
        this.sagaOrchestrator = sagaOrchestrator;
        this.notificationService = notificationService;
    }

    /**
     * Place an order from the user's current cart.
     *
     * This is the main entry point for checkout. It:
     *   1. Retrieves the cart
     *   2. Converts CartItems to OrderItems
     *   3. Calculates pricing and shipping
     *   4. Builds the Order
     *   5. Executes the saga (inventory → payment → shipping)
     *   6. Clears the cart on success
     *
     * @param userId the user placing the order
     * @param paymentMethod the payment method to use
     * @return the completed Order (or cancelled if saga failed)
     */
    public Order placeOrder(String userId, PaymentMethod paymentMethod) {
        System.out.println("\n=== Placing order for user: " + userId + " ===");

        // Step 1: Get the user's cart
        Cart cart = cartService.getCart(userId)
            .orElseThrow(() -> new ECommerceException("No cart found for user: " + userId));

        if (cart.isEmpty()) {
            throw new ECommerceException("Cart is empty for user: " + userId);
        }

        // Step 2: Convert CartItems to OrderItems (lock in prices)
        List<OrderItem> orderItems = cart.getItems().stream()
            .map(cartItem -> new OrderItem(
                cartItem.productId(),
                cartItem.quantity(),
                cartItem.priceSnapshot()   // price locked at add-to-cart time
            ))
            .toList();

        // Step 3: Calculate total price via pricing strategy
        double subtotal = pricingStrategy.calculatePrice(orderItems);
        double tax = subtotal * 0.08;  // 8% tax (simplified)

        // Step 4: Build the order
        Order order = new Order.Builder(userId)
            .addItems(orderItems)
            .tax(tax)
            .build();

        // Step 5: Calculate and set shipping cost
        double shippingCost = shippingStrategy.calculateCost(order);
        order.setShippingCost(shippingCost);

        // Step 6: Persist the order
        orderRepository.save(order);
        System.out.println("Order created: " + order);

        // Step 7: Execute the checkout saga
        SagaResult result = sagaOrchestrator.execute(order);

        // Step 8: Handle saga result
        if (result.isSuccess()) {
            // Clear the cart after successful checkout
            cartService.clearCart(userId);
            System.out.println("Order placed successfully: " + order.getOrderId());
        } else {
            System.out.println("Order failed: " + result);
        }

        // Persist final state
        orderRepository.save(order);
        return order;
    }

    /**
     * Cancel an existing order.
     * Only possible if the order is not yet shipped.
     */
    public Order cancelOrder(String orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));

        if (!order.canTransitionTo(OrderStatus.CANCELLED)) {
            throw new InvalidOrderStateException(
                "Cannot cancel order in state: " + order.getStatus());
        }

        order.transitionTo(OrderStatus.CANCELLED);
        orderRepository.save(order);
        notificationService.notifyOrderCancelled(order);

        return order;
    }

    /**
     * Get an order by ID.
     */
    public Optional<Order> getOrder(String orderId) {
        return orderRepository.findById(orderId);
    }

    /**
     * Get all orders for a user.
     */
    public List<Order> getOrdersByUser(String userId) {
        return orderRepository.findByUserId(userId);
    }
}
```

---

### 7.5 CartService

```java
/**
 * Manages shopping carts.
 *
 * Operations:
 *   - addItem: add a product to cart (snapshots current catalog price)
 *   - removeItem: remove a product from cart
 *   - updateQuantity: change quantity of an existing cart item
 *   - getCart: retrieve the cart for a user
 *   - clearCart: empty the cart (after checkout or user action)
 *
 * IMPORTANT: CartItem snapshots the price at add-time.
 * This prevents price-change surprises at checkout.
 *
 * Cart is rebuilt (new instance via Builder) on every modification.
 * This keeps Cart effectively immutable after construction.
 */
public class CartService {

    private final CartRepository cartRepository;
    private final ProductService productService;

    public CartService(CartRepository cartRepository, ProductService productService) {
        this.cartRepository = cartRepository;
        this.productService = productService;
    }

    /**
     * Add a product to the user's cart.
     *
     * CALL CHAIN:
     *   ECommerceController.addToCart(userId, productId, qty)
     *     → CartService.addItem(userId, productId, qty)
     *       → ProductService.getProduct(productId)  // get current price
     *       → new CartItem(productId, name, qty, price)  // snapshot price
     *       → Cart.Builder(userId).addItems(existingItems).addItem(newItem).build()
     *       → CartRepository.save(cart)
     */
    public Cart addItem(String userId, String productId, int quantity) {
        Product product = productService.getProduct(productId)
            .orElseThrow(() -> new ECommerceException("Product not found: " + productId));

        CartItem newItem = new CartItem(
            product.productId(),
            product.name(),
            quantity,
            product.price()   // snapshot current catalog price
        );

        // Get existing cart or start fresh
        Cart.Builder builder = new Cart.Builder(userId);
        cartRepository.findByUserId(userId).ifPresent(existingCart -> {
            // Re-add existing items (except the product being added, to avoid duplicates)
            existingCart.getItems().stream()
                .filter(item -> !item.productId().equals(productId))
                .forEach(builder::addItem);
        });

        // Add the new item
        builder.addItem(newItem);
        Cart cart = builder.build();
        cartRepository.save(cart);

        System.out.printf("[Cart] Added %d x %s ($%.2f each) to cart for user %s%n",
            quantity, product.name(), product.price(), userId);
        return cart;
    }

    /**
     * Remove a product from the user's cart.
     */
    public Optional<Cart> removeItem(String userId, String productId) {
        return cartRepository.findByUserId(userId).map(existingCart -> {
            Cart.Builder builder = new Cart.Builder(userId);
            existingCart.getItems().stream()
                .filter(item -> !item.productId().equals(productId))
                .forEach(builder::addItem);

            // If cart would be empty, delete it
            List<CartItem> remaining = existingCart.getItems().stream()
                .filter(item -> !item.productId().equals(productId))
                .toList();

            if (remaining.isEmpty()) {
                cartRepository.deleteByUserId(userId);
                return null;
            }

            Cart cart = builder.build();
            cartRepository.save(cart);
            return cart;
        });
    }

    /** Get the cart for a user. */
    public Optional<Cart> getCart(String userId) {
        return cartRepository.findByUserId(userId);
    }

    /** Clear the cart (e.g., after successful checkout). */
    public void clearCart(String userId) {
        cartRepository.deleteByUserId(userId);
        System.out.println("[Cart] Cleared cart for user: " + userId);
    }
}
```

---

### 7.6 PaymentService and ShippingService

```java
/**
 * Processes payments using the configured PaymentStrategy.
 *
 * Delegates to the appropriate strategy based on the PaymentMethod.
 * Maintains a registry of strategies (Map<PaymentMethod, PaymentStrategy>).
 *
 * CALL CHAIN:
 *   OrderSagaOrchestrator.processPayment(order)
 *     → PaymentService.processPayment(orderId, amount, CREDIT_CARD)
 *       → strategies.get(CREDIT_CARD).processPayment(orderId, amount)
 *       → PaymentRepository.save(payment)
 *       → return payment
 */
public class PaymentService {

    private final Map<PaymentMethod, PaymentStrategy> strategies;
    private final PaymentRepository paymentRepository;

    /**
     * Constructor takes a map of all available payment strategies.
     * AppConfig wires this: Map.of(CREDIT_CARD, ccStrategy, WALLET, walletStrategy, ...)
     */
    public PaymentService(Map<PaymentMethod, PaymentStrategy> strategies,
                          PaymentRepository paymentRepository) {
        this.strategies = strategies;
        this.paymentRepository = paymentRepository;
    }

    /**
     * Process a payment for an order.
     *
     * @param orderId the order being paid for
     * @param amount the amount to charge
     * @param method the payment method
     * @return Payment object with status and transactionId
     * @throws PaymentFailedException if the strategy rejects the payment
     */
    public Payment processPayment(String orderId, double amount, PaymentMethod method) {
        PaymentStrategy strategy = strategies.get(method);
        if (strategy == null) {
            throw new PaymentFailedException("Unsupported payment method: " + method);
        }

        System.out.printf("[Payment] Processing $%.2f via %s for order %s%n",
            amount, method, orderId);

        Payment payment = strategy.processPayment(orderId, amount);
        paymentRepository.save(payment);
        return payment;
    }

    /**
     * Refund a previously completed payment.
     * Used by saga compensation.
     */
    public boolean refundPayment(Payment payment) {
        PaymentStrategy strategy = strategies.get(payment.getMethod());
        if (strategy == null) {
            throw new PaymentFailedException(
                "Cannot refund: no strategy for " + payment.getMethod());
        }

        boolean refunded = strategy.refundPayment(payment);
        paymentRepository.save(payment);  // persist updated status
        return refunded;
    }
}
```

```java
/**
 * Manages shipment creation and tracking using the configured ShippingStrategy.
 *
 * CALL CHAIN:
 *   OrderSagaOrchestrator.createShipment(order)
 *     → ShippingService.createShipment(order)
 *       → shippingStrategy.createShipment(order)
 *       → return shipment
 */
public class ShippingService {

    private final ShippingStrategy shippingStrategy;

    public ShippingService(ShippingStrategy shippingStrategy) {
        this.shippingStrategy = shippingStrategy;
    }

    /**
     * Create a shipment for the given order.
     */
    public Shipment createShipment(Order order) {
        System.out.println("[Shipping] Creating shipment for order: " + order.getOrderId());
        return shippingStrategy.createShipment(order);
    }

    /**
     * Calculate shipping cost for the given order.
     */
    public double calculateShippingCost(Order order) {
        return shippingStrategy.calculateCost(order);
    }

    /**
     * Get estimated delivery days.
     */
    public int getEstimatedDays() {
        return shippingStrategy.getEstimatedDays();
    }
}
```

---

### 7.7 NotificationService

```java
/**
 * Sends notifications for order lifecycle events (simulated via console).
 *
 * In a real system, this would integrate with:
 *   - Email service (SES, SendGrid)
 *   - SMS service (Twilio, SNS)
 *   - Push notification service (Firebase, APNs)
 *
 * For our LLD, we print to console.
 */
public class NotificationService {

    public void notifyOrderConfirmed(Order order) {
        System.out.printf("[Notification] Order %s confirmed! Total: $%.2f. "
            + "Tracking: %s%n",
            order.getOrderId(), order.getTotalAmount(),
            order.getShipment() != null ? order.getShipment().getTrackingId() : "pending");
    }

    public void notifyOrderFailed(Order order, String reason) {
        System.out.printf("[Notification] Order %s FAILED: %s%n",
            order.getOrderId(), reason);
    }

    public void notifyOrderCancelled(Order order) {
        System.out.printf("[Notification] Order %s has been cancelled.%n",
            order.getOrderId());
    }

    public void notifyShipmentUpdate(Shipment shipment) {
        System.out.printf("[Notification] Shipment %s status: %s%n",
            shipment.getTrackingId(), shipment.getStatus());
    }
}
```

---

## 8. Concurrency Considerations

### 8.1 Inventory Reservation Concurrency

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║                CONCURRENCY MODEL: Per-Product Locking                            ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    SCENARIO: Two users buy the last laptop simultaneously.

    ┌──────────────────────────────────────────────────────────────┐
    │ Inventory: laptop → total=100, reserved=99, available=1     │
    └──────────────────────────────────────────────────────────────┘

    Thread A: reserve("laptop", 1)       Thread B: reserve("laptop", 1)
         │                                    │
         ▼                                    ▼
    synchronized(getLock("laptop"))       BLOCKED (same lock object!)
         │                                    │
         │ check: available=1 >= 1 ✓          │ (waiting...)
         │ reserve: reserved=100              │
         │ save to repo                       │
         │                                    │
         exit synchronized ──────────────────→ enters synchronized
                                              │
                                              │ check: available=0 >= 1 ✗
                                              │ throw InsufficientStockException!
                                              │
                                              exit synchronized

    RESULT:
      Thread A: successfully reserved the last laptop.
      Thread B: InsufficientStockException → saga compensates.
      NO overselling. NO race condition.


    ┌──────────────────────────────────────────────────────────────┐
    │ CONCURRENT DIFFERENT PRODUCTS: No contention!               │
    │                                                              │
    │ Thread A: reserve("laptop", 1)    Thread B: reserve("mouse", 5)
    │      │                                  │                    │
    │      ▼                                  ▼                    │
    │ synchronized(lock["laptop"])    synchronized(lock["mouse"])  │
    │      │ (different locks!)              │                     │
    │      │ runs in parallel ◄──────────── │ runs in parallel    │
    │      │                                │                      │
    │ Both complete without blocking each other.                   │
    └──────────────────────────────────────────────────────────────┘
```

### 8.2 Repository Thread Safety

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║                REPOSITORY: ConcurrentHashMap-Backed Stores                       ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    All InMemory*Repository implementations use ConcurrentHashMap:

    public class InMemoryProductRepository implements ProductRepository {
        private final Map<String, Product> store = new ConcurrentHashMap<>();

        @Override
        public void save(Product product) {
            store.put(product.productId(), product);
        }

        @Override
        public Optional<Product> findById(String productId) {
            return Optional.ofNullable(store.get(productId));
        }
    }

    WHY ConcurrentHashMap?
      - Thread-safe for individual put/get operations
      - No global lock: uses segment-level locking internally
      - Good enough for our scope (compound operations like
        check-then-act are handled by InventoryService's synchronized)

    Thread safety hierarchy:
      ┌────────────────────────────────────────────┐
      │ Layer             Thread Safety             │
      │ ─────────────     ───────────────────────── │
      │ Controller        Stateless (new per call)  │
      │ OrderService      Stateless (delegates)     │
      │ InventoryService  synchronized per product  │
      │ Repository        ConcurrentHashMap          │
      │ Inventory model   NOT thread-safe (mutated   │
      │                   under InventoryService     │
      │                   lock only)                 │
      └────────────────────────────────────────────┘
```

### 8.3 Deadlock Prevention

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║                DEADLOCK PREVENTION                                                ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    PROBLEM: An order with multiple items acquires multiple product locks.
      Order A: {laptop, mouse}
      Order B: {mouse, laptop}

      Thread A: lock(laptop) → try lock(mouse) → BLOCKED (B holds mouse)
      Thread B: lock(mouse) → try lock(laptop) → BLOCKED (A holds laptop)
      → DEADLOCK!

    SOLUTION: Lock ordering. Always acquire locks in sorted productId order.

    /**
     * Reserve inventory for multiple items with consistent lock ordering.
     * Sort productIds alphabetically to prevent deadlock.
     */
    private void reserveInventory(Order order) {
        // Sort items by productId to ensure consistent lock acquisition order.
        // This prevents deadlock when two orders contain the same products
        // in different order.
        List<OrderItem> sortedItems = order.getItems().stream()
            .sorted(Comparator.comparing(OrderItem::productId))
            .toList();

        for (OrderItem item : sortedItems) {
            inventoryService.reserve(item.productId(), item.quantity());
        }
    }

    With lock ordering:
      Thread A: lock(laptop) → lock(mouse)     // alphabetical order
      Thread B: lock(laptop) → lock(mouse)     // same order!
      → No deadlock. Thread B waits for Thread A to finish both locks.
```

---

## 9. SOLID Principles Applied

### 9.1 Single Responsibility Principle (SRP)

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║                SRP: Each class has ONE reason to change                           ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    Class                     Responsibility                  Changes when...
    ─────────────────────     ──────────────────────────      ───────────────────
    Order                     Order data + state machine      Order fields/states change
    OrderSagaOrchestrator     Saga coordination logic         Saga steps change
    InventoryService          Stock management                Inventory rules change
    PaymentService            Payment routing                 Payment flow changes
    CreditCardPaymentStrategy Credit card processing logic    CC gateway API changes
    PricingStrategy           Price calculation               Pricing rules change
    ShippingStrategy          Shipping cost/creation          Shipping rules change
    CartService               Cart operations                 Cart features change
    NotificationService       Sending notifications           Notification channels change

    ANTI-PATTERN (violates SRP): a "GodOrderService" that handles
    cart management, inventory checks, payment processing, shipping,
    AND notifications all in one 2000-line class.
```

### 9.2 Open-Closed Principle (OCP)

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║                OCP: Open for extension, closed for modification                  ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    Adding a new payment method (e.g., cryptocurrency):

    WRONG (violates OCP):
      // Modify PaymentService with a new if-else branch
      if (method == CREDIT_CARD) { ... }
      else if (method == WALLET) { ... }
      else if (method == CRYPTO) { ... }  // <-- modifying existing code!

    RIGHT (follows OCP):
      1. Create CryptoPaymentStrategy implements PaymentStrategy
      2. Register it in AppConfig: strategies.put(CRYPTO, new CryptoPaymentStrategy())
      3. PaymentService unchanged. It just calls strategies.get(method).

    Same pattern for:
      - New pricing rule: create a new PricingStrategy implementation
      - New shipping method: create a new ShippingStrategy implementation
      - New saga step: extend OrderSagaOrchestrator (or create a new orchestrator)
```

### 9.3 Liskov Substitution Principle (LSP)

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║                LSP: Subtypes must be substitutable for their base type            ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    PaymentService works with ANY PaymentStrategy implementation.
    It calls processPayment() and refundPayment() without knowing
    the concrete type.

    // PaymentService does not care which strategy it is using.
    // All strategies honor the same contract:
    //   - processPayment() returns a Payment or throws PaymentFailedException
    //   - refundPayment() returns true/false

    PaymentStrategy strategy = strategies.get(method);
    Payment payment = strategy.processPayment(orderId, amount);
    // Works for CreditCard, Wallet, COD -- all interchangeable.

    LSP violation example (what NOT to do):
      class BrokenCODStrategy implements PaymentStrategy {
          Payment processPayment(String orderId, double amount) {
              return null;  // <-- VIOLATES contract! Caller expects non-null.
          }
      }
```

### 9.4 Interface Segregation Principle (ISP)

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║                ISP: Clients should not depend on methods they do not use          ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    GOOD: Separate strategy interfaces for separate concerns.

      PricingStrategy:  calculatePrice(items)
      PaymentStrategy:  processPayment(), refundPayment()
      ShippingStrategy: createShipment(), calculateCost(), getEstimatedDays()

    BAD (violates ISP): One giant "CheckoutStrategy" interface:

      interface CheckoutStrategy {
          double calculatePrice(items);
          Payment processPayment(orderId, amount);
          boolean refundPayment(payment);
          Shipment createShipment(order);
          double calculateShippingCost(order);
      }
      // StandardPricingStrategy would have to implement processPayment()
      // even though it has nothing to do with payments!

    GOOD: Separate repository interfaces per entity.

      ProductRepository:   save, findById, findByCategory
      OrderRepository:     save, findById, findByUserId, findByStatus
      InventoryRepository: save, findByProductId

    BAD: One giant "DataRepository" interface with all methods.
```

### 9.5 Dependency Inversion Principle (DIP)

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║                DIP: Depend on abstractions, not concretions                       ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    OrderService depends on:
      ├── PricingStrategy         (interface, not StandardPricingStrategy)
      ├── ShippingStrategy        (interface, not StandardShippingStrategy)
      ├── SagaOrchestrator        (interface, not OrderSagaOrchestrator)
      ├── OrderRepository         (interface, not InMemoryOrderRepository)
      └── CartService, ProductService, NotificationService (concrete, OK for now)

    InventoryService depends on:
      └── InventoryRepository     (interface, not InMemoryInventoryRepository)

    PaymentService depends on:
      ├── PaymentStrategy         (interface, via Map<PaymentMethod, PaymentStrategy>)
      └── PaymentRepository       (interface, not InMemoryPaymentRepository)

    Wiring happens in AppConfig:

    /**
     * AppConfig: the composition root.
     * Creates all concrete objects and injects them as interfaces.
     *
     * This is where "new" lives. Nowhere else in the codebase
     * creates service/repository/strategy instances directly.
     */
    public class AppConfig {
        public OrderService createOrderService() {
            // Repositories (concrete behind interface)
            ProductRepository productRepo = new InMemoryProductRepository();
            OrderRepository orderRepo = new InMemoryOrderRepository();
            InventoryRepository inventoryRepo = new InMemoryInventoryRepository();
            CartRepository cartRepo = new InMemoryCartRepository();
            PaymentRepository paymentRepo = new InMemoryPaymentRepository();

            // Strategies (concrete behind interface)
            PricingStrategy pricing = new StandardPricingStrategy();
            ShippingStrategy shipping = new StandardShippingStrategy();
            Map<PaymentMethod, PaymentStrategy> paymentStrategies = Map.of(
                PaymentMethod.CREDIT_CARD, new CreditCardPaymentStrategy(),
                PaymentMethod.WALLET, new WalletPaymentStrategy(new HashMap<>()),
                PaymentMethod.COD, new CODPaymentStrategy()
            );

            // Services
            ProductService productService = new ProductService(productRepo);
            CartService cartService = new CartService(cartRepo, productService);
            InventoryService inventoryService = new InventoryService(inventoryRepo);
            PaymentService paymentService = new PaymentService(paymentStrategies, paymentRepo);
            ShippingService shippingService = new ShippingService(shipping);
            NotificationService notificationService = new NotificationService();

            // Saga orchestrator
            SagaOrchestrator saga = new OrderSagaOrchestrator(
                inventoryService, paymentService, shippingService, notificationService
            );

            // Facade
            return new OrderService(
                cartService, productService, pricing, shipping,
                orderRepo, saga, notificationService
            );
        }
    }
```

---

## 10. Sample Workflows

### 10.1 Happy Path: Successful Order

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║                WORKFLOW 1: Happy Path — Successful Order Checkout                 ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    User adds items to cart, then checks out.
    All saga steps succeed. Order is placed.

    STEP-BY-STEP:

    1. User adds items to cart:
       ECommerceController.addToCart("user-123", "laptop-001", 1)
         → CartService.addItem("user-123", "laptop-001", 1)
           → ProductService.getProduct("laptop-001") → Product{price=999.99}
           → CartItem("laptop-001", "Laptop Pro", 1, 999.99)  // snapshot price
           → Cart.Builder("user-123").addItem(cartItem).build()
           → CartRepository.save(cart)

    2. User adds another item:
       ECommerceController.addToCart("user-123", "mouse-001", 2)
         → CartService.addItem("user-123", "mouse-001", 2)
           → ProductService.getProduct("mouse-001") → Product{price=29.99}
           → CartItem("mouse-001", "Wireless Mouse", 2, 29.99)
           → Rebuild cart with both items
           → CartRepository.save(cart)

    3. User places order:
       ECommerceController.placeOrder("user-123", CREDIT_CARD)
         → OrderService.placeOrder("user-123", CREDIT_CARD)
           → CartService.getCart("user-123") → Cart{items=2, subtotal=1059.97}
           → PricingStrategy.calculatePrice(items) → 1059.97
           → tax = 1059.97 * 0.08 = 84.80
           → Order.Builder("user-123").addItems(...).tax(84.80).build()
           → ShippingStrategy.calculateCost(order) → 7.00
           → order.setShippingCost(7.00)
           → OrderRepository.save(order) → Order{total=1151.77}

    4. Saga execution:
       OrderSagaOrchestrator.execute(order)

         Step 1: RESERVE_INVENTORY
           → InventoryService.reserve("laptop-001", 1)
             → synchronized(lock["laptop-001"])
             → Inventory.reserve(1) → available: 99→98
           → InventoryService.reserve("mouse-001", 2)
             → synchronized(lock["mouse-001"])
             → Inventory.reserve(2) → available: 200→198
           → order.transitionTo(INVENTORY_RESERVED)  ✓

         Step 2: PROCESS_PAYMENT
           → order.transitionTo(PAYMENT_PENDING)
           → PaymentService.processPayment("order-abc", 1151.77, CREDIT_CARD)
             → CreditCardPaymentStrategy.processPayment("order-abc", 1151.77)
             → Payment{txn=TXN-CC-xyz, status=COMPLETED}
           → order.setPayment(payment)
           → order.transitionTo(PAYMENT_CONFIRMED)   ✓

         Step 3: CREATE_SHIPMENT
           → ShippingService.createShipment(order)
             → StandardShippingStrategy.createShipment(order)
             → Shipment{tracking=TRACK-abc, carrier=StandardPost, ETA=6 days}
           → order.setShipment(shipment)
           → order.transitionTo(SHIPPED)             ✓

         All steps completed!
           → confirmInventory(order)
             → InventoryService.confirm("laptop-001", 1) → total: 100→99
             → InventoryService.confirm("mouse-001", 2) → total: 200→198
           → NotificationService.notifyOrderConfirmed(order)
           → CartService.clearCart("user-123")

    5. Final state:
       Order{status=SHIPPED, total=1151.77, tracking=TRACK-abc}
       Inventory{laptop: total=99, reserved=0, available=99}
       Inventory{mouse: total=198, reserved=0, available=198}
       Cart: empty
```

---

### 10.2 Failure Path: Payment Fails, Saga Compensates

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║            WORKFLOW 2: Payment Fails — Saga Compensation                         ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    Same setup as Workflow 1, but the credit card charge fails
    (e.g., amount exceeds limit). The saga must compensate.

    1-3. (same as Workflow 1: cart → order → saga starts)

    4. Saga execution:
       OrderSagaOrchestrator.execute(order)

         Step 1: RESERVE_INVENTORY
           → InventoryService.reserve("laptop-001", 1) → available: 99→98  ✓
           → InventoryService.reserve("mouse-001", 2) → available: 200→198 ✓
           → order.transitionTo(INVENTORY_RESERVED)

         Step 2: PROCESS_PAYMENT
           → order.transitionTo(PAYMENT_PENDING)
           → PaymentService.processPayment("order-abc", 11000.00, CREDIT_CARD)
             → CreditCardPaymentStrategy: amount $11,000 > $10,000 limit!
             → throws PaymentFailedException                              ✗ FAILED

         *** COMPENSATION BEGINS (reverse order) ***

         Compensate Step 1: RELEASE INVENTORY
           → InventoryService.release("laptop-001", 1) → available: 98→99
           → InventoryService.release("mouse-001", 2) → available: 198→200
           → SagaStep[RESERVE_INVENTORY: COMPENSATED]

         → order.transitionTo(CANCELLED)
         → NotificationService.notifyOrderFailed(order, "Payment failed")

    5. Final state:
       Order{status=CANCELLED}
       Inventory{laptop: total=100, reserved=0, available=100}  // fully restored!
       Inventory{mouse: total=200, reserved=0, available=200}   // fully restored!

       SagaResult{
         success: false,
         failedStep: "PROCESS_PAYMENT",
         failureReason: "amount exceeds limit",
         completedSteps: [RESERVE_INVENTORY],
         compensatedSteps: [RESERVE_INVENTORY]
       }

    KEY INSIGHT: After compensation, the system is exactly as it was before
    the order was placed. No ghost reservations. No locked stock.
```

---

### 10.3 Concurrent Checkout: Two Users, Last Item

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║            WORKFLOW 3: Race Condition — Two Users, Last Item                      ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    Inventory: laptop → total=100, reserved=99, available=1
    User A and User B both try to buy the last laptop.

    Timeline:
    ──────────────────────────────────────────────────────────────────
    T0:  Thread A enters saga → reserve("laptop", 1)
         Thread B enters saga → reserve("laptop", 1)

    T1:  Thread A acquires lock["laptop"]
         Thread B BLOCKED on lock["laptop"]

    T2:  Thread A: available=1 >= 1 ✓ → reserved=100 → exit lock
         Thread B: acquires lock["laptop"]

    T3:  Thread B: available=0 >= 1 ✗ → InsufficientStockException!

    T4:  Thread A: continues saga (payment → shipping → done)  ✓
         Thread B: saga fails at step 1 (nothing to compensate)  ✗

    T5:  Thread A: order placed successfully
         Thread B: order cancelled, user notified "out of stock"
    ──────────────────────────────────────────────────────────────────

    RESULT: Exactly one user gets the laptop. No overselling.
    The synchronized block on InventoryService ensures atomicity.
```

---

## 11. Design Patterns Used

| Pattern | Where Applied | Why |
|---------|---------------|-----|
| **Saga** | `OrderSagaOrchestrator` | Coordinates multi-step checkout with compensation on failure. Cannot use a single DB transaction across inventory, payment, and shipping. |
| **Strategy** | `PricingStrategy`, `PaymentStrategy`, `ShippingStrategy` | Swap pricing/payment/shipping algorithms at runtime without changing service code. Each strategy is independently testable. |
| **Builder** | `Order.Builder`, `Cart.Builder` | Clean construction of objects with many fields. Computed fields (subtotal, total) are derived from items, never passed in. |
| **Facade** | `OrderService` | Single entry point for cart-to-order-to-saga workflow. Controller never talks to InventoryService or PaymentService directly. |
| **State Machine** | `Order.transitionTo()`, `VALID_TRANSITIONS` map | Enforces legal order state transitions. Prevents impossible states like shipping an unpaid order. |
| **Repository** | All `*Repository` interfaces | Abstracts data access. InMemory implementations for LLD/testing. Could swap to JDBC/NoSQL without changing services. |
| **Factory** | `AppConfig` | Composition root that creates and wires all objects. No `new` in service classes. Pure constructor injection. |
| **Snapshot** | `CartItem.priceSnapshot` | Locks in the price at add-to-cart time. Protects customer from catalog price changes during shopping. |

### Pattern Interaction Diagram

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║                HOW PATTERNS INTERACT IN A SINGLE CHECKOUT                        ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    ┌─────────┐
    │ Builder │──── Cart.Builder creates Cart with snapshotted prices
    │ Pattern │──── Order.Builder creates Order with computed totals
    └────┬────┘
         │ Order is created
         ▼
    ┌─────────┐
    │ Facade  │──── OrderService.placeOrder() orchestrates everything
    │ Pattern │     The controller calls ONE method. The Facade delegates.
    └────┬────┘
         │ delegates to
         ▼
    ┌─────────┐
    │  Saga   │──── OrderSagaOrchestrator.execute(order)
    │ Pattern │     Step 1 → Step 2 → Step 3, with compensation on failure
    └────┬────┘
         │ each step uses
         ▼
    ┌──────────┐
    │ Strategy │──── PricingStrategy.calculatePrice()
    │ Pattern  │     PaymentStrategy.processPayment()
    │          │     ShippingStrategy.createShipment()
    └────┬─────┘
         │ state changes via
         ▼
    ┌──────────────┐
    │ State Machine│──── order.transitionTo(INVENTORY_RESERVED)
    │   Pattern    │     order.transitionTo(PAYMENT_CONFIRMED)
    │              │     order.transitionTo(SHIPPED)
    └──────┬───────┘
           │ persisted via
           ▼
    ┌──────────────┐
    │  Repository  │──── OrderRepository.save(order)
    │   Pattern    │     InventoryRepository.save(inventory)
    └──────────────┘
```

---

## 12. Extensibility Points

### 12.1 New Payment Method

```
To add cryptocurrency payment:

  1. Add to enum:     PaymentMethod.CRYPTO
  2. Create strategy: CryptoPaymentStrategy implements PaymentStrategy
  3. Register:        AppConfig → strategies.put(CRYPTO, new CryptoPaymentStrategy())
  4. DONE. No changes to PaymentService, OrderSagaOrchestrator, or OrderService.

  Files changed: 3 (enum, new class, AppConfig)
  Files NOT changed: PaymentService, OrderService, OrderSagaOrchestrator
```

### 12.2 New Pricing Rule

```
To add membership-based pricing (Prime discount):

  1. Create strategy: PrimePricingStrategy implements PricingStrategy
     - Takes membership level as constructor parameter
     - Applies percentage discount based on level
  2. Wire in AppConfig: new PrimePricingStrategy(MembershipLevel.PRIME)
  3. DONE. OrderService calls pricingStrategy.calculatePrice() as before.

  Files changed: 2 (new class, AppConfig)
  Files NOT changed: OrderService, CartService, any existing strategy
```

### 12.3 New Shipping Method

```
To add same-day delivery:

  1. Create strategy: SameDayShippingStrategy implements ShippingStrategy
     - getEstimatedDays() returns 0
     - calculateCost() applies premium rate
  2. Register in AppConfig or expose as user choice
  3. DONE. ShippingService delegates to the strategy without knowing the details.
```

### 12.4 New Saga Step

```
To add a fraud check step (between inventory and payment):

  1. Add to SagaStep enum: CHECK_FRAUD
  2. Create FraudService with check(order) method
  3. Modify OrderSagaOrchestrator:
     - Add Step 1.5: CHECK_FRAUD between RESERVE_INVENTORY and PROCESS_PAYMENT
     - Add compensation: compensateFraudCheck() (may be a no-op)
  4. This DOES modify the orchestrator, which is expected:
     saga step ordering is the orchestrator's core responsibility.
```

### 12.5 Switch from InMemory to Database

```
To switch to a real database:

  1. Create JdbcProductRepository implements ProductRepository
  2. Create JdbcOrderRepository implements OrderRepository
  3. (etc. for all repositories)
  4. In AppConfig: replace new InMemoryProductRepository() with
     new JdbcProductRepository(dataSource)
  5. DONE. All services depend on the interface, not the implementation.
     Not a single service class needs to change.
```

### 12.6 Event-Driven Notifications

```
To add event-driven architecture:

  1. Create OrderEvent record (orderId, eventType, timestamp)
  2. Create EventBus with publish(event) and subscribe(handler)
  3. NotificationService subscribes to order events
  4. OrderSagaOrchestrator publishes events instead of calling
     NotificationService directly
  5. This decouples the saga from notification delivery.
     Add email, SMS, push handlers without touching the saga.
```

### 12.7 Extensibility Summary

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║                EXTENSIBILITY MAP                                                  ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    What you want to add          Pattern that enables it    Files to change
    ──────────────────────        ────────────────────────   ──────────────────
    New payment method            Strategy                   Enum + new class + AppConfig
    New pricing rule              Strategy                   New class + AppConfig
    New shipping method           Strategy                   New class + AppConfig
    New saga step                 Saga                       Orchestrator + new service
    New storage backend           Repository (DIP)           New class + AppConfig
    New notification channel      Observer/Event             New handler + EventBus
    Order state change            State Machine              Order.VALID_TRANSITIONS
    New product type/category     Model                      Product record (or subclass)

    The key insight: most extensions require ADDING new classes,
    not MODIFYING existing ones. This is the Open-Closed Principle
    made real through Strategy, Repository, and Saga patterns.
```

---

> **Final Interview Tip**: When asked "Design an E-Commerce system," start with the Saga pattern for checkout orchestration. Draw the state machine for Order. Explain the inventory reserve/confirm/release model. Then show how Strategy pattern makes pricing, payment, and shipping pluggable. This demonstrates distributed systems thinking, concurrency awareness, and clean design in one answer.
