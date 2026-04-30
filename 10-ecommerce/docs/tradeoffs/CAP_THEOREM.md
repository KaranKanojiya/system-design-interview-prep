# CAP Theorem & Distributed Tradeoffs in the E-Commerce System (Amazon)

> Interview-ready reference for a Senior Java developer.
> An e-commerce system is a SPLIT CAP system -- different components make different CAP choices.
> Inventory and Payment are CP (no overselling, no double-charge). Catalog and Cart are AP (stale prices are OK, empty cart is not).

---

## Table of Contents

| Section | Key Point |
|---------|-----------|
| Split CAP Classification | CP for inventory/payment, AP for catalog/cart |
| Inventory Consistency | Strong consistency to prevent overselling |
| Payment Consistency | Exactly-once semantics via idempotency keys |
| Cart Consistency | Eventual consistency OK (user can retry) |
| Product Catalog | AP, CDN-cached, stale prices are invisible |
| Saga and Eventual Consistency | How saga achieves consistency without distributed locks |
| PACELC Analysis | When no partition: latency vs consistency per component |
| Per-Component Deep Dive | Detailed analysis of each subsystem |
| Interview Q&A | Ready-to-use answers |

---

## Split CAP Classification: This Is NOT One Choice

```
  THE KEY INSIGHT: An e-commerce system doesn't make ONE CAP choice.
  Each subsystem makes its OWN choice based on its tolerance for inconsistency.

         Consistency (C)
            /\
           /  \
          / CP \
         / INV  \     <--- Inventory (no overselling)
        / PAYMENT \   <--- Payment (no double-charge)
       /____________\
  Availability (A) --- Partition Tolerance (P)
        \            /
         \   AP    /
          \ CART  /   <--- Cart (empty cart = broken UX)
           \CATLG/    <--- Catalog (blank product page = broken UX)
            \  /
             \/

  +------------------------------------------------------------------+
  |  Component         | CAP Choice | Why                             |
  +--------------------+------------+---------------------------------+
  | Inventory Service  | CP         | Overselling = ship what you     |
  |                    |            | don't have = legal liability    |
  +--------------------+------------+---------------------------------+
  | Payment Service    | CP         | Double-charge = customer rage   |
  |                    |            | + chargeback fees               |
  +--------------------+------------+---------------------------------+
  | Product Catalog    | AP         | Stale price for 5 min is OK.    |
  |                    |            | Blank product page = lost sale  |
  +--------------------+------------+---------------------------------+
  | Shopping Cart      | AP         | Cart losing an item is annoying |
  |                    |            | but user can re-add. Cart being |
  |                    |            | UNAVAILABLE = lost sale         |
  +--------------------+------------+---------------------------------+
  | Order Service      | CP         | Order must be consistent -- you |
  |                    |            | can't ship wrong items          |
  +--------------------+------------+---------------------------------+
  | Search/Browse      | AP         | Stale search results are fine.  |
  |                    |            | Search being down = lost sales  |
  +--------------------+------------+---------------------------------+
```

---

## Inventory Consistency: CP -- Strong Consistency to Prevent Overselling

### The Problem: Overselling

```
  SCENARIO: 1 iPhone left in stock. Two users click "Buy" simultaneously.

  WITHOUT STRONG CONSISTENCY:
  ============================

  User A                  Inventory DB              User B
    |                         |                        |
    | (1) check stock         |                        |
    |  (iPhone)               |                        |
    |------------------------>|                        |
    |  stock = 1              |                        |
    |<------------------------|                        |
    |                         | (2) check stock        |
    |                         |  (iPhone)              |
    |                         |<-----------------------|
    |                         |  stock = 1             |
    |                         |----------------------->|
    |                         |                        |
    | (3) reserve 1           |                        |
    |  (stock 1 >= 1? YES)    |                        |
    |------------------------>|                        |
    |  stock = 0              |                        |
    |                         |                        |
    |                         | (4) reserve 1          |
    |                         |  (stock 1 >= 1? YES)   |  <-- STALE READ!
    |                         |<-----------------------|
    |                         |  stock = -1            |  <-- OVERSOLD!
    |                         |----------------------->|

  RESULT: Both users think they got the iPhone.
  You must now: cancel one order, apologize, possibly face legal action.

  WITH STRONG CONSISTENCY (synchronized / conditional write):
  ============================================================

  User A                  Inventory DB              User B
    |                         |                        |
    | (1) atomic reserve      |                        |
    |  (iPhone, qty=1)        |                        |
    |  [LOCK acquired]        |                        |
    |------------------------>|                        |
    |                         |                        |
    |                         | (2) atomic reserve     |
    |                         |  (iPhone, qty=1)       |
    |                         |  [BLOCKED -- waiting]  |
    |                         |<-----------------------|
    |                         |                        |
    |  stock 1 >= 1? YES      |                        |
    |  stock = 0              |                        |
    |  [LOCK released]        |                        |
    |<------------------------|                        |
    |                         |                        |
    |                         |  stock 0 >= 1? NO!     |
    |                         |  [LOCK released]       |
    |                         |  REJECTED              |
    |                         |----------------------->|

  RESULT: User A gets the iPhone. User B sees "Out of Stock."
  Correct behavior. No overselling.
```

### Implementation: Synchronized Reserve in Our Java Simulation

```java
public class InMemoryInventoryRepository implements InventoryRepository {
    private final Map<String, Integer> stock = new ConcurrentHashMap<>();

    @Override
    public synchronized boolean reserve(String productId, int quantity) {
        // synchronized = only one thread can execute this at a time
        // This is our simulation of strong consistency
        int current = stock.getOrDefault(productId, 0);
        if (current >= quantity) {
            stock.put(productId, current - quantity);
            return true;   // reserved successfully
        }
        return false;       // insufficient stock -- reject
    }

    @Override
    public synchronized void release(String productId, int quantity) {
        // Compensation: add stock back (saga rollback)
        stock.merge(productId, quantity, Integer::sum);
    }
}
```

### Production Equivalent: DynamoDB Conditional Write

```
  DynamoDB conditional update (production-grade atomic decrement):

  UpdateExpression: SET stock = stock - :qty
  ConditionExpression: stock >= :qty

  If condition fails -> ConditionalCheckFailedException -> reject order
  If condition passes -> atomic decrement -> no race condition

  This is the equivalent of our synchronized block, but:
  - Works across multiple servers (distributed)
  - No JVM-level locking needed
  - DynamoDB handles the atomicity
```

### Why CP for Inventory?

| Question | Answer |
|----------|--------|
| What happens if inventory is stale by 5 seconds? | Two users can reserve the same last item = overselling |
| What happens if inventory service is briefly unavailable? | User sees "temporarily unavailable" -- annoying but correct |
| What's worse: unavailable or inconsistent? | Inconsistent: overselling means shipping what you don't have |
| How does Amazon handle this? | DynamoDB conditional writes + strong consistency reads for inventory |

---

## Payment Consistency: CP -- Exactly-Once Semantics via Idempotency Keys

### The Problem: Double-Charging

```
  SCENARIO: User clicks "Pay" and the network times out.
  Did the payment go through? User retries.

  WITHOUT IDEMPOTENCY:
  =====================

  Client                  Payment Gateway           User's Bank
    |                         |                         |
    | (1) charge $135         |                         |
    |  (no idempotency key)   |                         |
    |------------------------>|                         |
    |                         | (2) debit $135          |
    |                         |------------------------>|
    |                         |  OK                     |
    |                         |<------------------------|
    |                         |                         |
    |  TIMEOUT (network)      |                         |
    |  X<----- response lost  |                         |
    |                         |                         |
    | (3) retry: charge $135  |                         |
    |  (no idempotency key)   |                         |
    |------------------------>|                         |
    |                         | (4) debit $135 AGAIN!   |
    |                         |------------------------>|
    |                         |  OK                     |
    |                         |<------------------------|
    |  OK                     |                         |
    |<------------------------|                         |
    |                         |                         |

  RESULT: User charged $270 instead of $135.
  Chargeback + angry customer + potential legal issue.

  WITH IDEMPOTENCY KEY:
  ======================

  Client                  Payment Gateway           User's Bank
    |                         |                         |
    | (1) charge $135         |                         |
    |  key: "PAY-ORD-123"    |                         |
    |------------------------>|                         |
    |                         | (2) store key           |
    |                         |  "PAY-ORD-123" -> $135  |
    |                         |                         |
    |                         | (3) debit $135          |
    |                         |------------------------>|
    |                         |  OK                     |
    |                         |<------------------------|
    |                         |                         |
    |  TIMEOUT (network)      |                         |
    |  X<----- response lost  |                         |
    |                         |                         |
    | (4) retry: charge $135  |                         |
    |  key: "PAY-ORD-123"    |                         |
    |------------------------>|                         |
    |                         | (5) key exists!         |
    |                         |  Already processed.     |
    |                         |  Return cached result.  |
    |  OK (from cache)        |                         |
    |<------------------------|                         |
    |                         |                         |

  RESULT: User charged exactly $135. Retry was safely deduplicated.
```

### Implementation: Idempotency in Our Java Simulation

```java
public class PaymentService {
    private final PaymentRepository paymentRepository;
    private final Map<String, PaymentResult> idempotencyCache = new ConcurrentHashMap<>();

    public PaymentResult charge(BigDecimal amount, String orderId, String userId) {
        // (1) Generate idempotency key from order ID
        String idempotencyKey = "PAY-" + orderId;

        // (2) Check if already processed
        PaymentResult cached = idempotencyCache.get(idempotencyKey);
        if (cached != null) {
            System.out.printf("[PAYMENT] Duplicate request for %s -- returning cached result%n",
                idempotencyKey);
            return cached;  // safe retry
        }

        // (3) Process payment (simulate gateway call)
        PaymentResult result = processPayment(amount, orderId, userId);

        // (4) Cache the result with idempotency key
        idempotencyCache.put(idempotencyKey, result);

        // (5) Persist payment record
        paymentRepository.save(new Payment(orderId, userId, amount, result));

        return result;
    }
}
```

### Production Equivalent: Stripe Idempotency

```
  Stripe API call with idempotency key:

  POST /v1/charges
  Idempotency-Key: PAY-ORD-123
  {
    "amount": 13500,  // cents
    "currency": "usd",
    "source": "tok_visa"
  }

  If Stripe receives the same Idempotency-Key within 24 hours:
  - Does NOT create a new charge
  - Returns the original response
  - Guarantees exactly-once semantics
```

### Why CP for Payment?

| Question | Answer |
|----------|--------|
| What happens if payment state is stale? | Double-charge or missed charge -- both are unacceptable |
| What happens if payment is briefly unavailable? | User sees "payment processing, please wait" -- annoying but correct |
| What's worse: unavailable or inconsistent? | Inconsistent: double-charge = chargeback + customer trust destroyed |
| Exactly-once guarantee? | Idempotency key per order ensures retry safety |

---

## Cart Consistency: AP -- Eventual Consistency OK

### Why AP for Cart?

```
  SCENARIO: User adds item to cart. Network partition occurs.
  Different servers have different cart states.

  +--------------------------------------------------------------------+
  |                         CART: AP IS OK                              |
  +--------------------------------------------------------------------+
  |                                                                    |
  |  What happens if cart is stale?                                    |
  |  -> User sees 2 items instead of 3 they just added.               |
  |  -> User adds the item again. No harm done.                       |
  |  -> Cart merges eventually (CRDT / last-writer-wins).             |
  |                                                                    |
  |  What happens if cart is unavailable?                              |
  |  -> User CANNOT add items to cart.                                 |
  |  -> User leaves the site. Lost sale.                               |
  |  -> Amazon loses $30M/day in revenue.                              |
  |                                                                    |
  |  VERDICT: Stale cart (user re-adds item) < Unavailable cart        |
  |           (user leaves site). AP wins.                             |
  |                                                                    |
  +--------------------------------------------------------------------+
```

### Cart Conflict Resolution

```
  SCENARIO: User has two browser tabs open. Adds different items.

  Tab 1 (Server A)                   Tab 2 (Server B)
  Cart: [iPhone]                     Cart: [AirPods]
       |                                  |
       | (partition heals)                |
       v                                  v
  +---------------------------------------------+
  |           MERGE STRATEGY                     |
  +---------------------------------------------+
  |                                              |
  |  Option 1: Last-Writer-Wins (LWW)           |
  |  -> Cart = [AirPods] (Tab 2 was later)      |
  |  -> iPhone LOST! Bad UX.                     |
  |                                              |
  |  Option 2: Union Merge (CRDT-style)          |
  |  -> Cart = [iPhone, AirPods]                 |
  |  -> Nothing lost. User can remove unwanted.  |
  |  -> Amazon uses this approach.               |
  |                                              |
  |  Option 3: Prompt User                       |
  |  -> "Your cart was updated on another device" |
  |  -> User chooses which version to keep.      |
  |                                              |
  +---------------------------------------------+

  AMAZON'S CHOICE: Union merge.
  Add-wins > remove-wins for a shopping cart (Dynamo paper, Section 5).
```

### Implementation: Redis Cart with Session

```
  Production architecture for cart:

  +--------+       +---------+       +---------+
  | Client |------>| Redis   |------>| Cart    |
  | (user) |       | (cache) |       | Service |
  +--------+       +---------+       +---------+
                        |
                   write-through
                        |
                        v
                   +---------+
                   | DynamoDB|
                   | (durable|
                   |  store) |
                   +---------+

  - Redis: fast reads/writes for active sessions
  - DynamoDB: durable storage, survives Redis failure
  - Write-through: every cart update goes to both Redis and DynamoDB
  - On Redis miss: read from DynamoDB, populate Redis

  In our Java simulation:
  - ConcurrentHashMap (in-memory) = simulates Redis
  - No durability layer (it's a demo)
```

---

## Product Catalog Consistency: AP -- CDN-Cached

### Why AP for Catalog?

```
  SCENARIO: Product price changes from $999 to $899 (flash sale).

  +--------------------------------------------------------------------+
  |                     CATALOG: AP IS FINE                             |
  +--------------------------------------------------------------------+
  |                                                                    |
  |  Timeline:                                                         |
  |  t=0      Admin sets iPhone price to $899                          |
  |  t=0-30s  CDN still serves $999 (stale cache)                     |
  |  t=30s    CDN cache expires, new price $899 served                |
  |                                                                    |
  |  WHAT HAPPENS DURING THE 30-SECOND WINDOW?                        |
  |                                                                    |
  |  User sees $999 on product page                                    |
  |  User clicks "Buy"                                                 |
  |  Checkout reads REAL price from DB = $899                          |
  |  User is pleasantly surprised! (or at worst, sees $899 at cart)   |
  |                                                                    |
  |  THE KEY INSIGHT:                                                  |
  |  Catalog prices are DISPLAY ONLY.                                  |
  |  The authoritative price is always read from the database          |
  |  at checkout time. Stale catalog = visual inconsistency only.     |
  |                                                                    |
  +--------------------------------------------------------------------+
```

### CDN Caching Architecture

```
  +--------+     +--------+     +---------+     +----------+     +--------+
  | Client |---->| CDN    |---->| API     |---->| Product  |---->| DB     |
  | (user) |     | (edge) |     | Gateway |     | Service  |     | (src   |
  +--------+     +--------+     +---------+     +----------+     | of     |
                      |                                           | truth) |
                 TTL: 30-60s                                     +--------+
                 HIT rate: 95%+
                 (80% of traffic
                  never reaches
                  backend)

  INVALIDATION:
  - Price change event -> invalidate CDN cache for that product
  - TTL-based: worst case 60 seconds of stale data
  - Acceptable because checkout reads from DB (not CDN)
```

---

## Saga and Eventual Consistency

### How Saga Achieves Consistency Without Distributed Locks

```
  THE SAGA MODEL: Eventual Consistency via Compensation

  +--------------------------------------------------------------------+
  |                                                                    |
  |  Traditional (2PC):                                                |
  |    All databases commit AT THE SAME TIME (strong consistency)      |
  |    Requires distributed locks held during entire transaction       |
  |    Blocks other transactions -- doesn't scale                      |
  |                                                                    |
  |  Saga:                                                             |
  |    Each step commits INDEPENDENTLY (local transaction)             |
  |    If a later step fails, COMPENSATE (undo) earlier steps          |
  |    Result: EVENTUAL consistency (not immediate)                    |
  |    No distributed locks -- scales to millions of transactions      |
  |                                                                    |
  +--------------------------------------------------------------------+

  TIMELINE OF A SAGA:

  t=0   OrderService.placeOrder() called
  t=1   Step 1: Reserve Inventory (LOCAL commit to Inventory DB)
        -> Inventory DB: stock = stock - 1  (committed)
  t=2   Step 2: Charge Payment (LOCAL commit to Payment DB)
        -> Payment DB: charge recorded  (committed)
  t=3   Saga complete -> Order is consistent

  INCONSISTENCY WINDOW (t=1 to t=3):
  - Inventory is reserved but payment hasn't been charged yet
  - The system is temporarily "inconsistent"
  - But this is OK because the saga WILL reach consistency:
    - Either Step 2 succeeds -> both committed -> consistent
    - Or Step 2 fails -> compensate Step 1 (release inventory) -> consistent

  THE GUARANTEE:
  +-----------------------------------------------------------------+
  | The saga ALWAYS reaches one of two final states:                |
  |   1. All steps committed (success)                              |
  |   2. All steps compensated (failure, everything undone)         |
  | There is NO state where some steps are committed and some are   |
  | not, UNLESS compensation itself fails (edge case, see below).   |
  +-----------------------------------------------------------------+
```

### What If Compensation Fails?

```
  COMPENSATION FAILURE HANDLING:

  Step 1: Reserve Inventory  -> SUCCESS
  Step 2: Charge Payment     -> FAILED (card declined)

  Compensation:
  Step 1 compensate: Release Inventory -> FAILS! (network error)

  NOW WHAT?
  +--------------------------------------------------------------------+
  |  Strategy               | How It Works                             |
  +-------------------------+------------------------------------------+
  | Retry with backoff      | Retry release 3x with exponential        |
  |                         | backoff (1s, 2s, 4s). Works for          |
  |                         | transient network errors.                |
  +-------------------------+------------------------------------------+
  | Dead Letter Queue (DLQ) | After retries exhausted, push to DLQ.    |
  |                         | Background worker retries periodically.  |
  |                         | Alert ops team if stuck > 1 hour.        |
  +-------------------------+------------------------------------------+
  | Saga Log / Journal      | Persist saga state to DB. Recovery       |
  |                         | process reads incomplete sagas and       |
  |                         | retries compensation.                    |
  +-------------------------+------------------------------------------+
  | Manual intervention     | Last resort. Ops team manually releases  |
  |                         | inventory via admin tool.                |
  +-------------------------+------------------------------------------+

  THE KEY PRINCIPLE:
  Compensation actions must be IDEMPOTENT.
  release(productId, qty) called twice = same result as called once.
  This makes retries safe.
```

### Saga Consistency vs 2PC Consistency

| Aspect | 2PC | Saga |
|--------|-----|------|
| Consistency model | Strong (immediate) | Eventual |
| Lock duration | Entire transaction (seconds) | None (each step commits independently) |
| Failure handling | ROLLBACK (atomic) | COMPENSATE (reverse order) |
| Availability during tx | Low (locks block others) | High (no locks) |
| Scalability | Poor (lock contention) | Excellent (no coordination) |
| Complexity | Simple concept, hard to operate | Complex concept, easier to operate |
| Database support | Requires XA-compatible DBs | Works with any database |

---

## PACELC Analysis

### What Is PACELC?

```
  CAP only describes behavior DURING a partition.
  PACELC extends this: what happens when there's NO partition?

  P = Partition
  A = Availability
  C = Consistency
  E = Else (no partition)
  L = Latency
  C = Consistency

  Format: "If Partition: A or C. Else: L or C"
```

### PACELC for Each E-Commerce Component

```
  +--------------------------------------------------------------------+
  | Component          | During Partition | Else (Normal)   | PACELC   |
  +--------------------+------------------+-----------------+----------+
  | Inventory Service  | PC (reject if    | EC (strong      | PC/EC    |
  |                    | unsure about     | consistency     |          |
  |                    | stock levels)    | even at cost    |          |
  |                    |                  | of latency)     |          |
  +--------------------+------------------+-----------------+----------+
  | Payment Service    | PC (reject if    | EC (never       | PC/EC    |
  |                    | can't confirm    | sacrifice       |          |
  |                    | idempotency)     | correctness)    |          |
  +--------------------+------------------+-----------------+----------+
  | Product Catalog    | PA (serve stale  | EL (serve from  | PA/EL    |
  |                    | from CDN cache)  | cache, update   |          |
  |                    |                  | eventually)     |          |
  +--------------------+------------------+-----------------+----------+
  | Shopping Cart      | PA (serve stale  | EL (read from   | PA/EL    |
  |                    | cart, merge      | Redis cache,    |          |
  |                    | later)           | fast response)  |          |
  +--------------------+------------------+-----------------+----------+
  | Search/Browse      | PA (serve stale  | EL (serve from  | PA/EL    |
  |                    | results from     | Elasticsearch   |          |
  |                    | local index)     | cache)          |          |
  +--------------------+------------------+-----------------+----------+
  | Order Service      | PC (reject new   | EC (strong      | PC/EC    |
  |                    | orders if saga   | consistency     |          |
  |                    | can't complete)  | for order data) |          |
  +--------------------+------------------+-----------------+----------+
```

### PACELC Decision Flow

```
  IS THERE A NETWORK PARTITION?
         |
    +----+----+
    |         |
   YES        NO
    |         |
    v         v
  P: Choose   E: Choose
  A or C?     L or C?
    |             |
    |         +---+---+
    |         |       |
    |        EL      EC
    |    (low latency (strong consistency
    |     via cache)   via synchronous
    |                   replication)
    |
  +-+---+
  |     |
  PA    PC
(serve  (reject
stale   if unsure)
data)

  INVENTORY & PAYMENT: PC/EC
  - During partition: REJECT orders (can't risk overselling/double-charge)
  - Normal operation: STRONG consistency (synchronous writes)
  - Trade latency for correctness

  CATALOG & CART: PA/EL
  - During partition: SERVE stale data (better than nothing)
  - Normal operation: LOW latency via caching (eventual consistency)
  - Trade consistency for speed
```

---

## Per-Component Deep Dive

### Inventory: The Hardest Consistency Problem

```
  WHY INVENTORY IS THE HARDEST:

  +--------------------------------------------------------------------+
  | Challenge              | Why It's Hard                              |
  +------------------------+--------------------------------------------+
  | Hot items              | Flash sale: 10,000 users competing for     |
  |                        | 100 iPhones. Race conditions galore.      |
  +------------------------+--------------------------------------------+
  | Distributed stock      | Warehouse A has 50, Warehouse B has 30.    |
  |                        | Total = 80 but they're in different DBs.   |
  +------------------------+--------------------------------------------+
  | Reservation timeout    | User reserves but never completes checkout.|
  |                        | Stock stuck as "reserved" forever.         |
  +------------------------+--------------------------------------------+
  | Return processing      | Customer returns item. Must add back to    |
  |                        | available stock. Concurrent with new sales.|
  +------------------------+--------------------------------------------+

  SOLUTIONS:
  1. Atomic decrement (DynamoDB conditional write, our synchronized block)
  2. Reservation timeout: background job releases after 15 minutes
  3. Distributed stock: saga coordinator queries each warehouse
  4. Return processing: add to separate "return stock" queue, process async
```

### Payment: Exactly-Once Is Really "At-Least-Once + Idempotency"

```
  THE TRUTH ABOUT "EXACTLY ONCE":

  In distributed systems, true exactly-once delivery is impossible.
  What we actually implement:

  AT-LEAST-ONCE delivery (retry on failure)
  + IDEMPOTENCY (duplicate detection)
  = EFFECTIVELY exactly-once processing

  +--------------------------------------------------------------------+
  | Layer              | Mechanism                                     |
  +--------------------+-----------------------------------------------+
  | Client -> Gateway  | Idempotency key in HTTP header                |
  | Gateway -> Bank    | Transaction reference number                  |
  | Saga -> Payment    | Order ID as natural idempotency key           |
  | Retry logic        | Exponential backoff: 1s, 2s, 4s, 8s, give up |
  +--------------------+-----------------------------------------------+

  IDEMPOTENCY KEY LIFECYCLE:

  (1) Generate key: "PAY-ORD-123"
  (2) First request: process payment, store key -> result
  (3) Retry (same key): return cached result (no re-processing)
  (4) Key expires after 24 hours (Stripe default)
  (5) After expiry: same key = new payment (but order is already fulfilled)
```

### Search/Browse: AP with Elasticsearch

```
  SEARCH CONSISTENCY MODEL:

  User searches "iPhone"
    |
    v
  Elasticsearch (near-real-time index)
    |
    | Index refresh interval: 1 second (default)
    | This means: a product added 0.5 seconds ago might not appear
    |
    | IS THIS OK?
    |
    | YES! Search results are inherently approximate.
    | Nobody expects "show me every product added in the last 100ms."
    |
    v
  Results sorted by relevance (BM25 + custom scoring)
    |
    | Prices shown are from Elasticsearch index (might be stale)
    | But checkout reads REAL price from PostgreSQL
    |
    v
  Product detail page: CDN cache (30-60s TTL)
    |
    v
  Add to cart: reads real-time price from DB (strong consistency)
```

---

## Consistency Boundaries: Where AP Meets CP

```
  THE CRITICAL BOUNDARY: Cart -> Checkout

  +-------------------+                   +---------------------+
  |   AP ZONE         |    BOUNDARY       |    CP ZONE          |
  |                   |        |          |                     |
  | Product Catalog   |        |          | Inventory Service   |
  | (CDN, 30s TTL)    |        |          | (synchronized)      |
  |                   |        |          |                     |
  | Shopping Cart     |        |          | Payment Service     |
  | (Redis, eventual) |        |          | (idempotent)        |
  |                   |        |          |                     |
  | Search Results    |        |          | Order Service       |
  | (Elasticsearch)   |        |          | (saga-coordinated)  |
  +-------------------+        |          +---------------------+
                               |
                          CHECKOUT
                          BOUNDARY

  AT CHECKOUT:
  (1) Read cart from Redis (AP zone)
  (2) Re-validate prices from DB (not CDN!) -- crossing into CP zone
  (3) Check real-time inventory (CP zone)
  (4) Execute saga (CP zone)

  PRICE MISMATCH HANDLING:
  If catalog showed $999 but DB price is $899:
    -> Charge $899 (DB is source of truth)
    -> User sees lower price at checkout (good surprise)

  If catalog showed $899 but DB price is $999 (flash sale ended):
    -> Show message: "Price changed to $999"
    -> User decides whether to proceed
    -> NEVER silently charge more than displayed
```

---

## Interview Q&A

| Question | Answer |
|----------|--------|
| "Is your e-commerce system CP or AP?" | Neither -- it's a split CAP system. Inventory and payment are CP (no overselling, no double-charge). Catalog and cart are AP (stale prices are fine, availability is critical for revenue). |
| "How do you prevent overselling?" | Synchronized reserve() in our simulation; DynamoDB conditional writes in production. Strong consistency for inventory -- we reject the order rather than oversell. |
| "How do you prevent double-charging?" | Idempotency keys. Each order has a unique payment key. Retries return cached results instead of creating new charges. At-least-once delivery + idempotency = effectively exactly-once. |
| "What if the user sees a stale price?" | Catalog is AP (CDN-cached). At checkout, we re-read the authoritative price from the database. If price went up, we notify the user. If price went down, we charge less. |
| "How does the saga maintain consistency?" | Eventual consistency. Each step commits independently. On failure, compensate in reverse order. The system is temporarily inconsistent during the saga, but always reaches a final consistent state. |
| "What if compensation fails?" | Retry with exponential backoff, then dead-letter queue. Compensation must be idempotent so retries are safe. Background worker processes stuck compensations. Alert ops if unresolved > 1 hour. |
| "What's the PACELC for inventory?" | PC/EC. During partition: reject (can't risk overselling). Normal operation: strong consistency (synchronous writes). We trade latency for correctness on inventory. |
| "Why not use 2PC instead of saga?" | 2PC requires all participants to hold locks during the transaction. At Amazon scale (100K+ orders/sec), that's millions of database locks held simultaneously. Plus, NoSQL databases like DynamoDB don't support 2PC. Saga scales because there are no distributed locks. |
| "How do you handle cart during a partition?" | Serve stale cart from Redis. Merge conflicts with union strategy (add-wins). User can re-add missing items. Better than "cart unavailable." |
| "What's the inconsistency window for the saga?" | Milliseconds to seconds. From when Step 1 commits to when the last step commits. During this window, inventory is reserved but payment hasn't been charged. If the system crashes here, a background job detects incomplete sagas and compensates. |
