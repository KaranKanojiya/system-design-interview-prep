# Technologies & Infrastructure for the E-Commerce System (Amazon)

> Interview-ready reference for a Senior Java developer.
> An e-commerce system sits at the intersection of microservices, polyglot persistence, distributed transactions, and event-driven architecture.
> Know the production stack, why each technology was chosen, and how our plain Java simulation maps to it.

---

## Table of Contents

| Technology | Why It's Here | Interview Relevance |
|------------|--------------|---------------------|
| Microservices (Spring Boot) | Production architecture for Amazon-scale e-commerce | HIGH -- architecture pattern |
| PostgreSQL | Order and payment persistence (ACID transactions) | HIGH -- relational for financial data |
| Redis | Cart caching, session storage, inventory cache | HIGH -- caching layer |
| Elasticsearch | Product search and filtering | HIGH -- full-text search |
| DynamoDB | Inventory with conditional writes (strong consistency) | HIGH -- NoSQL for high-throughput writes |
| Kafka | Event streaming between microservices | HIGH -- async communication |
| SQS | Notification delivery queue | MEDIUM -- decoupling notifications |
| Saga Frameworks | Axon, Eventuate Tram, Temporal | HIGH -- distributed transactions |
| Payment Gateways | Stripe, PayPal SDK | MEDIUM -- integration details |
| Our Java Simulation | In-memory, synchronized, plain Java | HIGH -- interview code walkthrough |

---

## 1. Microservices Architecture: Production vs Our Simulation

### Production Architecture (Spring Boot)

```
  +--------------------------------------------------------------------+
  |                    PRODUCTION E-COMMERCE STACK                      |
  +--------------------------------------------------------------------+
  |                                                                    |
  |  +--------+    +---------+    +----------+    +---------+          |
  |  | API    |    | Product |    | Inventory|    | Payment |          |
  |  | Gateway|--->| Service |--->| Service  |--->| Service |          |
  |  | (Kong) |    | (Spring |    | (Spring  |    | (Spring |          |
  |  +--------+    |  Boot)  |    |  Boot)   |    |  Boot)  |          |
  |       |        +---------+    +----------+    +---------+          |
  |       |             |              |               |               |
  |       |        +----v----+    +----v-----+    +----v-----+        |
  |       |        | Elastic |    | DynamoDB |    | PostgreSQL|        |
  |       |        | search  |    | (strong  |    | (ACID for |        |
  |       |        | (search)|    |  consist)|    |  payments)|        |
  |       |        +---------+    +----------+    +----------+        |
  |       |                                                            |
  |       |    +---------+    +---------+    +-----------+            |
  |       +--->| Order   |--->| Cart    |    | Notif.    |            |
  |            | Service |    | Service |    | Service   |            |
  |            | (Spring |    | (Spring |    | (Spring   |            |
  |            |  Boot)  |    |  Boot)  |    |  Boot)    |            |
  |            +---------+    +---------+    +-----------+            |
  |                 |              |               |                   |
  |            +----v----+    +---v-----+    +----v-----+            |
  |            |PostgreSQL|    | Redis  |    | SQS      |            |
  |            |(orders)  |    | (cart  |    | (email,  |            |
  |            |          |    |  cache)|    |  SMS)    |            |
  |            +----------+    +--------+    +----------+            |
  |                                                                    |
  |  +---------------------------+                                     |
  |  | Kafka (event bus)         |                                     |
  |  | - order.placed            |                                     |
  |  | - inventory.reserved      |                                     |
  |  | - payment.charged         |                                     |
  |  | - order.shipped           |                                     |
  |  +---------------------------+                                     |
  |                                                                    |
  +--------------------------------------------------------------------+
```

### Our Java Simulation

```
  +--------------------------------------------------------------------+
  |                    OUR JAVA SIMULATION                              |
  +--------------------------------------------------------------------+
  |                                                                    |
  |  AppConfig (Factory)                                               |
  |     |                                                              |
  |     +-- ProductRepository   = ConcurrentHashMap<String, Product>   |
  |     +-- OrderRepository     = ConcurrentHashMap<String, Order>     |
  |     +-- InventoryRepository = ConcurrentHashMap<String, Integer>   |
  |     |                         + synchronized for reserve/release   |
  |     +-- CartRepository      = ConcurrentHashMap<String, Cart>      |
  |     +-- PaymentRepository   = ConcurrentHashMap<String, Payment>   |
  |     |                                                              |
  |     +-- PricingStrategy     = StandardPricing / DiscountPricing    |
  |     +-- PaymentStrategy     = CreditCard / Wallet / COD           |
  |     +-- ShippingStrategy    = Standard / Express                   |
  |     |                                                              |
  |     +-- OrderSagaOrchestrator = List<SagaStep> executed in order   |
  |     +-- NotificationService   = System.out.printf (simulated)      |
  |     +-- OrderService (Facade) = orchestrates everything            |
  |                                                                    |
  |  KEY DIFFERENCE: everything runs in one JVM, single process.       |
  |  In production, each service is a separate deployable.             |
  |                                                                    |
  +--------------------------------------------------------------------+
```

### Production vs Simulation Mapping

| Production Component | Our Simulation | Why It Maps |
|---------------------|----------------|-------------|
| Spring Boot microservice | Plain Java class | Same patterns (DI, interface-driven), no framework overhead |
| Spring @Configuration | `AppConfig` factory | Centralized DI wiring |
| PostgreSQL (orders) | `ConcurrentHashMap` in `InMemoryOrderRepository` | Repository interface is identical |
| DynamoDB (inventory) | `ConcurrentHashMap` + `synchronized` | Conditional write = synchronized check-then-act |
| Redis (cart) | `ConcurrentHashMap` in `InMemoryCartRepository` | Same get/set semantics |
| Elasticsearch (search) | Linear scan + `String.contains()` | Same interface, different performance |
| Kafka (events) | Direct method calls | Observer pattern = synchronous Kafka |
| SQS (notifications) | `System.out.printf()` | Fire-and-forget notification |
| Stripe (payments) | Simulated `PaymentResult` with UUID | Same idempotency pattern |
| Saga framework (Temporal) | `OrderSagaOrchestrator` | Same execute/compensate pattern |

---

## 2. PostgreSQL -- Orders and Payments

### Why PostgreSQL for Orders?

```
  ORDERS NEED ACID:

  +--------------------------------------------------------------------+
  | ACID Property | Why Orders Need It                                  |
  +---------------+-----------------------------------------------------+
  | Atomicity     | Order creation + item insertion must be all-or-     |
  |               | nothing. Partial order = data corruption.           |
  +---------------+-----------------------------------------------------+
  | Consistency   | Order total must match sum of item prices.          |
  |               | Foreign keys ensure valid product references.       |
  +---------------+-----------------------------------------------------+
  | Isolation     | Two concurrent orders can't interfere with each     |
  |               | other's totals or status updates.                   |
  +---------------+-----------------------------------------------------+
  | Durability    | Once order is confirmed, it must survive crashes.   |
  |               | Customer has a receipt -- order can't vanish.       |
  +---------------+-----------------------------------------------------+
```

### Schema Design

```sql
  -- Orders table (PostgreSQL)
  CREATE TABLE orders (
      id              VARCHAR(36) PRIMARY KEY,  -- UUID
      user_id         VARCHAR(36) NOT NULL,
      status          VARCHAR(30) NOT NULL,      -- CREATED, SHIPPED, etc.
      total_amount    DECIMAL(12,2) NOT NULL,
      shipping_cost   DECIMAL(8,2),
      shipping_addr   JSONB,                     -- flexible address format
      discount_code   VARCHAR(20),
      created_at      TIMESTAMP DEFAULT NOW(),
      updated_at      TIMESTAMP DEFAULT NOW()
  );

  -- Order items (one-to-many)
  CREATE TABLE order_items (
      id              SERIAL PRIMARY KEY,
      order_id        VARCHAR(36) REFERENCES orders(id),
      product_id      VARCHAR(36) NOT NULL,
      product_name    VARCHAR(255) NOT NULL,
      quantity        INTEGER NOT NULL,
      unit_price      DECIMAL(10,2) NOT NULL,
      CONSTRAINT fk_order FOREIGN KEY (order_id) REFERENCES orders(id)
  );

  -- Payments table
  CREATE TABLE payments (
      id              VARCHAR(36) PRIMARY KEY,
      order_id        VARCHAR(36) NOT NULL REFERENCES orders(id),
      user_id         VARCHAR(36) NOT NULL,
      amount          DECIMAL(12,2) NOT NULL,
      status          VARCHAR(20) NOT NULL,       -- SUCCESS, FAILED, REFUNDED
      transaction_id  VARCHAR(100),                -- from payment gateway
      idempotency_key VARCHAR(100) UNIQUE,         -- prevents double-charge
      created_at      TIMESTAMP DEFAULT NOW()
  );

  -- Index for idempotency check
  CREATE UNIQUE INDEX idx_payment_idempotency ON payments(idempotency_key);

  -- Index for order lookup by user
  CREATE INDEX idx_orders_user_id ON orders(user_id);
```

### Why NOT PostgreSQL for Inventory?

```
  PostgreSQL for inventory at scale:

  Flash sale: 10,000 concurrent writes to the same row (iPhone stock).

  PostgreSQL row-level locking:
  UPDATE inventory SET stock = stock - 1 WHERE product_id = 'iphone' AND stock >= 1;

  Problem: 10,000 transactions competing for the same row lock.
  Result: massive lock contention, p99 latency spikes to seconds.

  DynamoDB conditional write:
  - Optimistic concurrency (no locks held)
  - Each write either succeeds atomically or fails (retry)
  - Scales horizontally (partition key = product_id)
  - Built for this exact access pattern

  Rule of thumb:
  - PostgreSQL: complex queries, joins, ACID for financial data
  - DynamoDB: high-throughput single-key operations, inventory counters
```

---

## 3. Redis -- Cart, Session, Caching

### Why Redis for Cart?

```
  CART ACCESS PATTERN:
  +--------------------------------------------------------------------+
  | Operation        | Frequency      | Latency Need | Data Size       |
  +------------------+----------------+--------------+-----------------+
  | Get cart         | Every page load| <5ms         | ~1-10 KB        |
  | Add to cart      | User action    | <10ms        | Single item     |
  | Update quantity  | User action    | <10ms        | Single field    |
  | Clear cart       | Post-checkout  | <50ms        | Full cart       |
  +------------------+----------------+--------------+-----------------+

  REDIS IS PERFECT:
  - Sub-millisecond reads (in-memory)
  - Hash data structure maps naturally to cart
  - TTL for abandoned cart cleanup (7 days)
  - Pub/Sub for real-time cart updates across tabs
```

### Redis Cart Data Model

```
  KEY: cart:{userId}
  TYPE: Hash

  HSET cart:USR-456 item:PROD-1 '{"productId":"PROD-1","name":"iPhone","qty":1,"price":999.00}'
  HSET cart:USR-456 item:PROD-2 '{"productId":"PROD-2","name":"AirPods","qty":2,"price":249.00}'

  HGETALL cart:USR-456
  -> Returns all items in the cart

  HDEL cart:USR-456 item:PROD-1
  -> Remove iPhone from cart

  EXPIRE cart:USR-456 604800
  -> Cart expires in 7 days (abandoned cart cleanup)

  PROS:
  - O(1) per item operation
  - No serialization/deserialization of entire cart for single item update
  - Natural fit for cart structure
  - Built-in TTL for cleanup

  CONS:
  - Data loss if Redis crashes (mitigate: Redis Cluster with AOF persistence)
  - No transactions across keys (mitigate: Lua scripts for atomic ops)
```

### Redis for Price Caching

```
  KEY: price:{productId}
  TYPE: String (with TTL)

  SET price:PROD-1 "999.00" EX 300   -- 5-minute TTL
  GET price:PROD-1                     -- returns "999.00" or nil

  CACHE-ASIDE PATTERN:
  (1) Read price from Redis
  (2) If cache miss -> read from PostgreSQL -> write to Redis
  (3) On price change event -> DEL price:PROD-1 (invalidate)

  IMPORTANT:
  - Price cache is for DISPLAY only (product pages, search results)
  - At CHECKOUT, always read from PostgreSQL (source of truth)
  - This is the AP/CP boundary discussed in CAP_THEOREM.md
```

---

## 4. Elasticsearch -- Product Search

### Why Elasticsearch?

```
  THE PROBLEM: User searches "wireless noise cancelling headphones under $300"

  PostgreSQL approach:
  SELECT * FROM products
  WHERE (name ILIKE '%wireless%' OR description ILIKE '%wireless%')
    AND (name ILIKE '%noise%' OR description ILIKE '%noise%')
    AND (name ILIKE '%cancelling%' OR description ILIKE '%cancelling%')
    AND (name ILIKE '%headphones%' OR description ILIKE '%headphones%')
    AND price < 300
  ORDER BY ???  -- how do you rank relevance?

  Problems:
  - ILIKE '%keyword%' = full table scan (no index usage)
  - No relevance scoring (which result is most relevant?)
  - No typo tolerance ("canceling" vs "cancelling")
  - No synonym matching ("headphones" vs "earphones")
  - At 10M products: 5-10 seconds per query

  Elasticsearch approach:
  GET /products/_search
  {
    "query": {
      "bool": {
        "must": [
          { "multi_match": {
              "query": "wireless noise cancelling headphones",
              "fields": ["name^3", "description", "category"],
              "fuzziness": "AUTO"
          }}
        ],
        "filter": [
          { "range": { "price": { "lt": 300 } } }
        ]
      }
    }
  }

  Result: <50ms, relevance-scored, typo-tolerant, synonym-aware
```

### Elasticsearch Index Design

```
  INDEX: products

  MAPPING:
  {
    "properties": {
      "product_id":    { "type": "keyword" },
      "name":          { "type": "text", "analyzer": "standard",
                         "fields": { "keyword": { "type": "keyword" } } },
      "description":   { "type": "text", "analyzer": "standard" },
      "category":      { "type": "keyword" },
      "price":         { "type": "float" },
      "rating":        { "type": "float" },
      "review_count":  { "type": "integer" },
      "in_stock":      { "type": "boolean" },
      "brand":         { "type": "keyword" },
      "tags":          { "type": "keyword" },
      "created_at":    { "type": "date" }
    }
  }

  DATA SYNC: PostgreSQL -> Kafka -> Elasticsearch
  - Product created/updated in PostgreSQL
  - Change event published to Kafka (CDC via Debezium)
  - Elasticsearch consumer indexes the event
  - Near-real-time (seconds) lag between DB and search index
```

### Our Simulation vs Production Search

| Feature | Our Simulation | Elasticsearch (Production) |
|---------|---------------|---------------------------|
| Search algorithm | `String.contains()` linear scan | Inverted index with BM25 scoring |
| Fuzzy matching | No | Yes (Levenshtein distance) |
| Performance at 10M products | O(n) per query = slow | O(1) index lookup = fast |
| Relevance scoring | No ranking | BM25 + custom boosting |
| Faceted filtering | Manual Java filter | Built-in aggregations |
| Synonym support | No | Yes (analyzer configuration) |

---

## 5. DynamoDB -- Inventory

### Why DynamoDB for Inventory?

```
  INVENTORY ACCESS PATTERN:
  +--------------------------------------------------------------------+
  | Operation              | Pattern           | Scale                 |
  +------------------------+-------------------+-----------------------+
  | Get stock level        | Single key read   | 100K+ reads/sec       |
  | Reserve stock          | Conditional write | 10K+ writes/sec       |
  | Release stock          | Atomic increment  | 1K+ writes/sec        |
  | Deduct (after payment) | Atomic decrement  | 1K+ writes/sec        |
  +------------------------+-------------------+-----------------------+

  DynamoDB is built for exactly this:
  - Single-key operations (partition key = productId)
  - Conditional writes (no overselling)
  - Auto-scaling (handles flash sale spikes)
  - Single-digit millisecond latency at any scale
```

### DynamoDB Table Design

```
  TABLE: Inventory

  Primary Key:
    Partition Key: productId (String)

  Attributes:
    - productId:      "PROD-1"
    - availableStock: 500
    - reservedStock:  15
    - warehouseId:    "WH-EAST-1"
    - lastUpdated:    "2024-01-15T10:30:00Z"
    - version:        42  (optimistic locking)

  CONDITIONAL WRITE (reserve):
  UpdateItem:
    Key: { productId: "PROD-1" }
    UpdateExpression: "SET availableStock = availableStock - :qty,
                          reservedStock = reservedStock + :qty,
                          version = version + 1"
    ConditionExpression: "availableStock >= :qty AND version = :expectedVersion"

  If condition fails:
  -> ConditionalCheckFailedException
  -> Client retries with latest version
  -> Eventually succeeds or gives up (out of stock)
```

### DynamoDB vs PostgreSQL for Inventory

| Aspect | PostgreSQL | DynamoDB |
|--------|-----------|----------|
| Concurrency model | Row-level locks (pessimistic) | Conditional writes (optimistic) |
| Hot key handling | Lock contention on popular items | Adaptive capacity, burst capacity |
| Scale | Vertical (bigger server) | Horizontal (auto-partition) |
| Consistency | SERIALIZABLE isolation | Strong consistency reads available |
| Cost at scale | Expensive (large instance) | Pay per request (cost-effective) |
| Flash sale (10K writes/sec) | Struggles (lock contention) | Handles natively |
| Complex queries | Full SQL support | Limited (single-key or scan) |

---

## 6. Kafka -- Event Streaming

### Why Kafka?

```
  E-COMMERCE EVENT FLOW:

  +----------+    order.created     +-----------+
  | Order    |--------------------->| Inventory |
  | Service  |                      | Service   |
  +----------+    order.placed      +-----------+
       |      |--------------------->|
       |                             | inventory.reserved
       |                             |--------------------->+----------+
       |                                                    | Payment  |
       |                                                    | Service  |
       |          payment.charged                           +----------+
       |<----------------------------------------------------|
       |
       |          order.shipped
       |--------------------->+-----------+
       |                      | Notif.    |
       |                      | Service   |
       |                      +-----------+

  WHY KAFKA (not direct HTTP calls):
  +--------------------------------------------------------------------+
  | Problem with HTTP       | How Kafka Solves It                      |
  +-------------------------+------------------------------------------+
  | Order Service must know | Kafka: publish event, subscribers decide |
  | all downstream services | to listen. Loose coupling.               |
  +-------------------------+------------------------------------------+
  | Downstream service down | Kafka: message persisted on broker.      |
  | = lost message          | Consumer reads when back up.             |
  +-------------------------+------------------------------------------+
  | Spike in orders         | Kafka: consumers process at their own    |
  | overwhelms payment      | pace. Natural backpressure.              |
  +-------------------------+------------------------------------------+
  | Replay for debugging    | Kafka: replay topic from any offset.     |
  |                         | Reconstruct state for debugging.         |
  +-------------------------+------------------------------------------+
```

### Kafka Topic Design

```
  TOPICS:

  +--------------------------------------------------------------------+
  | Topic                  | Partitions | Retention | Key             |
  +------------------------+------------+-----------+-----------------+
  | order.events           | 16         | 7 days    | orderId         |
  | inventory.events       | 8          | 3 days    | productId       |
  | payment.events         | 8          | 30 days   | orderId         |
  | notification.events    | 4          | 1 day     | userId          |
  | product.changes        | 8          | 7 days    | productId       |
  +------------------------+------------+-----------+-----------------+

  PARTITION KEY DESIGN:
  - orderId for order/payment topics: all events for one order go to same partition
    -> Guarantees ordering within an order (created before shipped)
  - productId for inventory: all stock changes for one product are ordered
  - userId for notifications: all notifications for one user are ordered

  CONSUMER GROUPS:
  +--------------------------------------------------------------------+
  | Consumer Group         | Reads From           | Purpose            |
  +------------------------+----------------------+--------------------+
  | inventory-consumers    | order.events         | Reserve stock      |
  | payment-consumers      | inventory.events     | Charge after rsv   |
  | notification-consumers | order.events,        | Send email/SMS     |
  |                        | payment.events       |                    |
  | analytics-consumers    | all topics           | Business metrics   |
  | search-indexer         | product.changes      | Update ES index    |
  +------------------------+----------------------+--------------------+
```

### Our Simulation vs Kafka

| Aspect | Our Simulation | Kafka (Production) |
|--------|---------------|-------------------|
| Communication | Direct method calls | Async message passing |
| Coupling | NotificationService injected into OrderService | NotificationService subscribes to Kafka topic |
| Failure handling | Exception propagates up | Message retry + DLQ |
| Ordering | Single-threaded (implicit order) | Per-partition ordering |
| Replay | Not possible | Replay from any offset |
| Backpressure | Not applicable (synchronous) | Consumer lag = natural buffer |

---

## 7. SQS -- Notification Queue

### Why SQS for Notifications?

```
  +--------------------------------------------------------------------+
  |                    NOTIFICATION PIPELINE                            |
  +--------------------------------------------------------------------+
  |                                                                    |
  |  Kafka                  SQS                   Notification Workers |
  |  (order events)   (per-channel queues)        (send actual msgs)   |
  |                                                                    |
  |  order.placed  -->  +-- email-queue ------>  Email Worker (SES)    |
  |                |    |                                               |
  |                |    +-- sms-queue -------->  SMS Worker (SNS)       |
  |                |    |                                               |
  |                |    +-- push-queue ------->  Push Worker (FCM)      |
  |                                                                    |
  |  WHY SQS (not just Kafka)?                                        |
  |  - Kafka: event streaming (order events, analytics)                |
  |  - SQS: task queue (one consumer processes each message exactly)   |
  |  - SQS has built-in retry + dead-letter queue                      |
  |  - SQS visibility timeout prevents double-sending                  |
  |  - Different channels have different throughput needs               |
  |                                                                    |
  +--------------------------------------------------------------------+
```

### SQS Configuration

```
  EMAIL QUEUE:
  - Visibility timeout: 30 seconds (time to send email via SES)
  - Max retries: 3
  - Dead-letter queue: email-dlq (for failed sends)
  - Delay: 0 (send immediately)

  SMS QUEUE:
  - Visibility timeout: 10 seconds (SMS is fast)
  - Max retries: 2
  - Dead-letter queue: sms-dlq
  - Delay: 0

  PUSH NOTIFICATION QUEUE:
  - Visibility timeout: 15 seconds
  - Max retries: 3
  - Dead-letter queue: push-dlq
  - Delay: 0
```

---

## 8. Saga Frameworks

### Framework Comparison

```
  +--------------------------------------------------------------------+
  | Framework       | Approach       | Language | Key Feature           |
  +-----------------+----------------+----------+-----------------------+
  | Temporal        | Orchestration  | Java,    | Durable execution,    |
  |                 |                | Go, etc. | automatic retry,      |
  |                 |                |          | versioning support    |
  +-----------------+----------------+----------+-----------------------+
  | Axon Framework  | Event Sourcing | Java     | Built-in saga mgmt,   |
  |                 | + Saga         |          | CQRS support,         |
  |                 |                |          | Spring Boot native    |
  +-----------------+----------------+----------+-----------------------+
  | Eventuate Tram  | Choreography   | Java     | Transactional outbox, |
  |                 | or Orchestra.  |          | CDC-based messaging,  |
  |                 |                |          | lightweight           |
  +-----------------+----------------+----------+-----------------------+
  | MicroProfile    | Orchestration  | Java     | Jakarta EE standard,  |
  | LRA             |                |          | annotation-driven     |
  +-----------------+----------------+----------+-----------------------+
  | Our Simulation  | Orchestration  | Java     | Plain Java, in-memory,|
  |                 |                |          | no framework          |
  +-----------------+----------------+----------+-----------------------+
```

### Temporal (Most Modern, Recommended for New Projects)

```java
// Temporal workflow (production saga)
@WorkflowInterface
public interface OrderWorkflow {
    @WorkflowMethod
    OrderResult processOrder(Order order);
}

@WorkflowImpl
public class OrderWorkflowImpl implements OrderWorkflow {

    private final InventoryActivity inventory = Workflow.newActivityStub(
        InventoryActivity.class, ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setRetryOptions(RetryOptions.newBuilder()
                .setMaximumAttempts(3)
                .build())
            .build());

    private final PaymentActivity payment = Workflow.newActivityStub(
        PaymentActivity.class, ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(60))
            .build());

    @Override
    public OrderResult processOrder(Order order) {
        // Temporal handles retries, timeouts, compensation automatically
        Saga saga = new Saga(new Saga.Options.Builder().build());

        try {
            // Step 1: Reserve inventory
            saga.addCompensation(inventory::release, order);
            inventory.reserve(order);

            // Step 2: Charge payment
            saga.addCompensation(payment::refund, order);
            payment.charge(order);

            return OrderResult.success(order);

        } catch (Exception e) {
            // Temporal runs compensation automatically
            saga.compensate();
            return OrderResult.failed(e.getMessage());
        }
    }
}
```

### Our Simulation vs Temporal

| Aspect | Our Simulation | Temporal |
|--------|---------------|----------|
| Durability | In-memory (lost on crash) | Durable (persisted workflow state) |
| Retry | Manual loop | Automatic with configurable policy |
| Timeout | No timeout handling | Per-activity timeouts |
| Versioning | Not supported | Workflow versioning for updates |
| Visibility | Print statements | Temporal Web UI (full history) |
| Compensation | Manual reverse-order loop | `saga.compensate()` |
| Complexity | ~50 lines of Java | Framework setup + configuration |

### Axon Framework (Java-Native, Event Sourcing)

```java
// Axon saga (event-sourced)
@Saga
public class OrderSaga {

    @SagaEventHandler(associationProperty = "orderId")
    public void on(OrderCreatedEvent event) {
        // Step 1: Send command to reserve inventory
        commandGateway.send(new ReserveInventoryCommand(
            event.getOrderId(), event.getItems()));
    }

    @SagaEventHandler(associationProperty = "orderId")
    public void on(InventoryReservedEvent event) {
        // Step 2: Send command to charge payment
        commandGateway.send(new ChargePaymentCommand(
            event.getOrderId(), event.getAmount()));
    }

    @SagaEventHandler(associationProperty = "orderId")
    public void on(PaymentFailedEvent event) {
        // Compensation: release inventory
        commandGateway.send(new ReleaseInventoryCommand(
            event.getOrderId()));
        SagaLifecycle.end();
    }

    @SagaEventHandler(associationProperty = "orderId")
    public void on(PaymentChargedEvent event) {
        // Success: end saga
        SagaLifecycle.end();
    }
}
```

---

## 9. Payment Gateways

### Stripe Integration (Production)

```java
// Stripe SDK usage
public class StripePaymentStrategy implements PaymentStrategy {

    @Override
    public PaymentResult pay(BigDecimal amount, Order order) {
        try {
            // Convert to cents (Stripe uses smallest currency unit)
            long amountInCents = amount.multiply(BigDecimal.valueOf(100)).longValue();

            Map<String, Object> params = new HashMap<>();
            params.put("amount", amountInCents);
            params.put("currency", "usd");
            params.put("source", order.getPaymentToken());
            params.put("description", "Order " + order.getOrderId());

            // Idempotency key prevents double-charge on retry
            RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey("PAY-" + order.getOrderId())
                .build();

            Charge charge = Charge.create(params, options);

            return new PaymentResult(
                charge.getPaid(),
                charge.getId(),        // "ch_1234567890"
                charge.getStatus()     // "succeeded"
            );

        } catch (CardException e) {
            // Card declined
            return new PaymentResult(false, null, e.getMessage());
        } catch (StripeException e) {
            // API error -- retry
            throw new PaymentGatewayException("Stripe API error", e);
        }
    }
}
```

### Our Simulation vs Stripe

| Aspect | Our Simulation | Stripe (Production) |
|--------|---------------|---------------------|
| Charge | Returns `new PaymentResult(true, UUID, "OK")` | HTTP POST to Stripe API |
| Idempotency | `ConcurrentHashMap` cache | Stripe-side idempotency (24h window) |
| Card validation | None | Luhn check + bank authorization |
| Refund | Remove from map | POST /v1/refunds |
| Webhooks | Direct method call | Stripe sends HTTP webhook |
| PCI compliance | N/A | Stripe handles card data (PCI Level 1) |
| Test mode | Always succeeds | Stripe test mode with test card numbers |

### PayPal Integration

```
  PayPal order flow (different from Stripe):

  (1) Client creates order via PayPal SDK
  (2) PayPal returns approval URL
  (3) User redirected to PayPal for approval
  (4) User approves, redirected back
  (5) Server captures payment (POST /v2/checkout/orders/{id}/capture)

  KEY DIFFERENCE:
  - Stripe: server-to-server charge (card number never hits your server)
  - PayPal: redirect flow (user approves on PayPal's site)
  - Both: support idempotency keys for retry safety
```

---

## 10. Our Java Implementation: In-Memory Simulation

### Design Decisions

```
  +--------------------------------------------------------------------+
  | Decision                  | Why                                     |
  +---------------------------+-----------------------------------------+
  | Plain Java (no Spring)    | Focus on PATTERNS, not framework magic  |
  |                           | Interviewer sees real code, not @Inject  |
  +---------------------------+-----------------------------------------+
  | ConcurrentHashMap         | Thread-safe in-memory storage            |
  |                           | Maps directly to key-value stores        |
  +---------------------------+-----------------------------------------+
  | synchronized for reserve  | Simulates strong consistency             |
  |                           | (DynamoDB conditional write)             |
  +---------------------------+-----------------------------------------+
  | System.out for notifs     | Focus on Observer pattern, not SMTP      |
  |                           | config                                   |
  +---------------------------+-----------------------------------------+
  | SagaStep interface        | Same abstraction as Temporal/Axon        |
  |                           | execute + compensate = universal         |
  +---------------------------+-----------------------------------------+
  | Builder for Order/Cart    | Show the pattern, not Lombok @Builder    |
  |                           | Interviewer sees field validation logic  |
  +---------------------------+-----------------------------------------+
```

### Thread Safety Analysis

```
  +--------------------------------------------------------------------+
  | Component               | Thread Safety Mechanism                   |
  +-------------------------+-------------------------------------------+
  | ProductRepository       | ConcurrentHashMap (read-heavy, safe)      |
  | OrderRepository         | ConcurrentHashMap (write-per-user, safe)  |
  | InventoryRepository     | synchronized on reserve/release           |
  |                         | (critical section -- prevents overselling)|
  | CartRepository          | ConcurrentHashMap (one cart per user)     |
  | PaymentRepository       | ConcurrentHashMap + idempotency cache     |
  | SagaOrchestrator        | Stateless (new SagaResult per execution) |
  | NotificationService     | Stateless (just prints)                   |
  +-------------------------+-------------------------------------------+

  KEY INSIGHT FOR INTERVIEWS:
  "Most components are stateless or use ConcurrentHashMap, which is sufficient
  for per-key operations. The only critical section is InventoryRepository.reserve()
  which uses synchronized to prevent overselling -- the equivalent of a DynamoDB
  conditional write or a PostgreSQL SELECT FOR UPDATE."
```

---

## Comprehensive Technology Comparison Table

```
  +----------+----------+---------+----------+---------+--------+-------+
  | Feature  | Postgres | Redis   | Elastic  | DynamoDB| Kafka  | SQS   |
  +==========+==========+=========+==========+=========+========+=======+
  | Type     | RDBMS    | KV/Cache| Search   | NoSQL   | Stream | Queue |
  +----------+----------+---------+----------+---------+--------+-------+
  | Data     | Orders,  | Cart,   | Product  | Inven-  | Events | Notif |
  |          | Payments | Prices, | search   | tory    |        | tasks |
  |          |          | Session | index    | stock   |        |       |
  +----------+----------+---------+----------+---------+--------+-------+
  | CAP      | CP       | AP      | AP       | CP      | AP     | AP    |
  +----------+----------+---------+----------+---------+--------+-------+
  | Consist  | Strong   | Eventu- | Near-    | Strong  | Eventu | At    |
  |          | (ACID)   | al      | realtime | (cond.  | al     | least |
  |          |          |         | (~1s)    | writes) |        | once  |
  +----------+----------+---------+----------+---------+--------+-------+
  | Latency  | 5-20ms   | <1ms    | 5-50ms   | <10ms   | <10ms  | 5ms   |
  +----------+----------+---------+----------+---------+--------+-------+
  | Scale    | Vertical | Cluster | Cluster  | Auto    | Parti- | Auto  |
  |          | (+read   | (shard) | (shard)  | scale   | tions  | scale |
  |          |  replca) |         |          |         |        |       |
  +----------+----------+---------+----------+---------+--------+-------+
  | Persist  | Disk     | Memory  | Disk     | Disk    | Disk   | Disk  |
  |          | (WAL)    | (+AOF)  | (Lucene) | (SSD)   | (log)  | (S3)  |
  +----------+----------+---------+----------+---------+--------+-------+
  | Cost     | $$       | $$      | $$$      | $       | $$     | $     |
  | (at      | (RDS)    | (Elasti | (managed)| (pay-   | (MSK)  | (per  |
  |  scale)  |          |  Cache) |          |  per-   |        |  msg) |
  |          |          |         |          |  req)   |        |       |
  +----------+----------+---------+----------+---------+--------+-------+
  | Our Sim  | Concurr- | Concurr | String   | Concurr | Direct | Print |
  |          | entHash  | entHash | .contains| entHash | method | ln    |
  |          | Map      | Map     |          | Map +   | calls  |       |
  |          |          |         |          | synchro |        |       |
  +----------+----------+---------+----------+---------+--------+-------+
```

---

## Interview Q&A

| Question | Answer |
|----------|--------|
| "Why polyglot persistence?" | Each service has different data access patterns. Orders need ACID (PostgreSQL), inventory needs atomic counters (DynamoDB), search needs inverted index (Elasticsearch), cart needs fast KV (Redis). One-size-fits-all means compromising on everything. |
| "Why not just PostgreSQL for everything?" | At Amazon scale, inventory gets 10K+ writes/sec to hot keys. PostgreSQL row locks cause contention. DynamoDB conditional writes are optimistic and scale horizontally. Also, full-text search in PostgreSQL is 10x slower than Elasticsearch. |
| "Why Kafka over direct HTTP calls?" | Decoupling, durability, and backpressure. If Notification Service is down, Kafka retains messages. If orders spike, consumers process at their own pace. Plus, analytics can replay the topic for debugging. |
| "Why not use Temporal/Axon in your simulation?" | We want to show the pattern, not the framework. Our SagaStep interface (execute + compensate) is the same abstraction Temporal uses. In an interview, showing you understand the PATTERN is more valuable than knowing framework APIs. |
| "Why plain Java instead of Spring Boot?" | Same reason. Spring Boot adds @Autowired, @Configuration, @Service -- all of which hide the DI and Factory patterns. Our AppConfig makes the wiring VISIBLE. Interviewers see you understand DI, not just Spring annotations. |
| "How would you migrate from in-memory to production?" | Implement the Repository interfaces with real database clients. InMemoryOrderRepository becomes PostgresOrderRepository. InMemoryInventoryRepository becomes DynamoDBInventoryRepository. The service layer doesn't change -- that's the point of the Repository pattern. |
| "What about observability?" | Production: Datadog/New Relic for metrics, ELK for logs, Jaeger for distributed tracing, PagerDuty for alerts. Kafka consumer lag monitoring for backpressure. Saga state dashboard for incomplete transactions. |
| "How do you handle schema evolution?" | PostgreSQL: Flyway/Liquibase migrations. Elasticsearch: reindex with new mapping. Kafka: Avro + Schema Registry for backward-compatible event schemas. DynamoDB: schemaless (just add new attributes). |
