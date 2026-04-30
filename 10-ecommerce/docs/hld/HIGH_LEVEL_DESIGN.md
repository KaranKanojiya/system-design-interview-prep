# High-Level Design: E-Commerce System (Amazon)

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
10. [Saga Pattern Deep Dive](#10-saga-pattern-deep-dive)
11. [CQRS Pattern](#11-cqrs-pattern)
12. [Inventory Management](#12-inventory-management)
13. [Concurrency](#13-concurrency)
14. [Scaling](#14-scaling)
15. [Database Choice](#15-database-choice)
16. [CAP Theorem](#16-cap-theorem)
17. [Cloud Services](#17-cloud-services)
18. [Tradeoffs Summary](#18-tradeoffs-summary)
19. [Interview Talking Points](#19-interview-talking-points)

---

## 1. Problem Statement

Design an **E-Commerce System** (like Amazon) that allows users to browse a massive product catalog, add items to a cart, place orders, process payments, manage inventory, and track shipments. The system must handle hundreds of millions of daily orders, survive Black Friday traffic spikes, prevent overselling, and maintain consistency across distributed services using patterns like Saga and CQRS.

**Why is it needed?**

- E-commerce platforms serve as the backbone of modern retail -- Amazon alone processes 1.6 million packages per day.
- Users expect sub-second product search, real-time inventory accuracy, and guaranteed order fulfillment.
- The checkout flow touches inventory, payments, and shipping -- each managed by independent services that must coordinate without a single point of failure.
- Flash sales and Black Friday events create 10x traffic spikes that can overwhelm unprepared systems.
- At scale (500M products, 100M daily orders), the system must handle distributed transactions, concurrent stock updates, and eventual consistency while appearing perfectly consistent to the user.

**Core Workflow:**

```
User browses products, adds to cart, checks out

(1) Client (Browser/App) --GET /products?q=laptop&page=1--> API Gateway
(2) API Gateway --> Product Catalog Service: search products
(3) Product Catalog Service --> Elasticsearch: full-text search + filters
(4) Elasticsearch --> Product Catalog Service: return ranked results
(5) Product Catalog Service --> API Gateway --> Client: display product list

(6) Client --POST /cart/items {productId, qty}--> API Gateway
(7) API Gateway --> Cart Service: add item to cart
(8) Cart Service --> Redis: store cart (session-based, fast reads/writes)
(9) Cart Service --> Inventory Service: check stock availability (soft check)
(10) Cart Service --> Client: "Item added to cart"

(11) Client --POST /orders/checkout--> API Gateway
(12) API Gateway --> Order Service: initiate checkout
(13) Order Service --> Cart Service: retrieve cart items
(14) Order Service --> Inventory Service: RESERVE stock for each item
(15) Inventory Service --> PostgreSQL: SELECT FOR UPDATE, decrement available stock
(16) Order Service --> Payment Service: charge customer
(17) Payment Service --> Payment Gateway (Stripe): process payment
(18) Payment Service --> Order Service: payment confirmed
(19) Order Service --> Inventory Service: CONFIRM reservation (stock committed)
(20) Order Service --> Shipping Service: create shipment
(21) Order Service --> Notification Service: send order confirmation email/push
(22) Order Service --> Client: "Order placed successfully, order #ORD-12345"

On failure at any step (Saga compensation):
(23) Payment fails --> Inventory Service: RELEASE reserved stock
(24) Inventory reservation fails --> Order Service: cancel order, notify user
```

### Why This Is Asked in Interviews

This is a **tier-1 system design** interview question, rated **Hard**. It appears at Amazon, Google, Meta, Microsoft, and every major tech company because it tests nearly every distributed systems concept in a single design:

| Skill Tested                    | What Interviewers Look For                                        |
|---------------------------------|-------------------------------------------------------------------|
| **Distributed Transactions**    | Why 2PC fails at scale; how Saga pattern coordinates services     |
| **Inventory Management**        | Pessimistic vs optimistic locking, overselling prevention         |
| **CQRS / Event Sourcing**       | Separating read and write models for catalog vs orders            |
| **Saga Pattern**                | Choreography vs orchestration, compensation/rollback logic        |
| **Event-Driven Architecture**   | Kafka for decoupling services, eventual consistency               |
| **Microservices Decomposition** | How to split monolith into Product, Cart, Order, Payment, etc.   |
| **Concurrency Control**         | Two users buying the last item simultaneously                     |
| **Caching Strategy**            | CDN for product images, Redis for cart, cache invalidation        |
| **Database Choices**            | PostgreSQL for orders, Redis for cart, ES for search              |
| **Scalability Under Spikes**    | Black Friday 10x surge -- auto-scaling, circuit breakers          |

> **Interview tip**: Start by clarifying the scale (daily orders, product count, peak QPS). Draw the microservice boundaries first. Interviewers will deep-dive into ONE of: Saga pattern, inventory locking, CQRS, or checkout concurrency. The Saga compensation flow is the "aha moment" -- be ready to whiteboard the rollback sequence for payment failure after inventory reservation.

---

## 2. Scope

### In Scope

| Feature                          | Description                                                          |
|----------------------------------|----------------------------------------------------------------------|
| Product Catalog & Search         | Browse, search, filter products with full-text search                |
| Shopping Cart                    | Add/remove/update items, persist cart across sessions                |
| Checkout & Order Placement       | Convert cart to order, coordinate inventory + payment                |
| Inventory Management             | Real-time stock tracking, reservation pattern, overselling prevention|
| Payment Processing               | Charge customer, handle refunds, idempotent payment operations       |
| Order Lifecycle                  | State machine: CREATED -> CONFIRMED -> SHIPPED -> DELIVERED          |
| Shipping & Tracking              | Create shipments, track packages, carrier integration                |
| Notifications                    | Order confirmation, shipping updates, delivery alerts                |
| Saga Pattern                     | Distributed transaction coordination with compensation               |
| CQRS                             | Separate read/write models for high-throughput catalog reads         |

### Out of Scope

| Feature                          | Reason                                                               |
|----------------------------------|----------------------------------------------------------------------|
| User Authentication / IAM        | Covered by a separate identity service                               |
| Recommendation Engine            | ML-based system, separate deep-dive topic                            |
| Reviews & Ratings                | CRUD feature, not architecturally interesting at this level          |
| Seller Portal / Marketplace      | Multi-vendor marketplace is an extension, not core checkout          |
| Return / Refund Workflow         | Extension of order lifecycle, adds complexity without new patterns   |
| Advertising / Sponsored Products | Business/monetization layer, not core system design                  |
| Warehouse Management             | Physical logistics, separate from software system design             |
| A/B Testing / Feature Flags      | Operational concern, not core architecture                           |

---

## 3. Assumptions

### Platform Scale

| Parameter                        | Value                              |
|----------------------------------|------------------------------------|
| Total products in catalog        | 500 million                        |
| Daily active users               | 200 million                        |
| Daily page views                 | 1 billion                          |
| Daily orders                     | 100 million                        |
| Average items per order          | 3                                  |
| Average order value              | $50                                |
| Peak orders per second           | 100M / 86400 * 3 (peak) = ~3,500 orders/sec |
| Black Friday peak                | 10x normal = ~35,000 orders/sec    |
| Concurrent users (peak)          | 20 million                         |
| Cart operations per day          | 500 million (add/remove/update)    |

### Data Volume

| Parameter                        | Value                              |
|----------------------------------|------------------------------------|
| Product record size              | ~5 KB (title, description, images, attributes) |
| Product catalog total            | 500M * 5 KB = ~2.5 TB              |
| Product images (CDN)             | 500M * 5 images * 500 KB = ~1.25 PB |
| Order record size                | ~2 KB (order + items + status)      |
| Daily order data                 | 100M * 2 KB = ~200 GB/day           |
| Cart data (Redis)                | 200M users * avg 3 items * 100 bytes = ~60 GB (peak active carts) |
| Inventory records                | 500M products * 50 bytes = ~25 GB   |
| Payment records per day          | 100M * 500 bytes = ~50 GB/day       |
| Kafka event throughput           | ~500K events/sec (peak)             |

### Back-of-the-Envelope: Latency Budget

```
Product Search (end-to-end):     Target p99 < 200 ms
  (1) Network RTT (client -> CDN):      10-30 ms
  (2) API Gateway + auth:                5 ms
  (3) Elasticsearch query:              20-50 ms
  (4) Result serialization:              5 ms
  (5) Network RTT (server -> client):   10-30 ms
  -------------------------------------------
  Total:                                50-120 ms

Checkout Flow (end-to-end):      Target p99 < 2 seconds
  (1) Cart retrieval (Redis):            2 ms
  (2) Inventory reservation (PostgreSQL): 10-50 ms
  (3) Payment processing (external):    500-1500 ms  <-- bottleneck
  (4) Order record write (PostgreSQL):   5-10 ms
  (5) Shipment creation:                 5-10 ms
  (6) Notification dispatch (async):     0 ms (fire-and-forget to Kafka)
  -------------------------------------------
  Total:                               522-1572 ms

Cart Operations:                 Target p99 < 50 ms
  (1) Redis read/write:                  1-2 ms
  (2) Inventory soft check:              5-10 ms
  (3) Response serialization:            1 ms
  -------------------------------------------
  Total:                                 7-13 ms
```

---

## 4. Functional Requirements

### FR-1: Product Catalog & Search
Users can search products by keyword, filter by category/price/brand/rating, sort by relevance/price/rating, and view product detail pages. Search must support full-text queries, faceted filtering, and return results in under 200ms.

### FR-2: Shopping Cart
Users can add items to a cart, update quantities, remove items, and view cart contents. The cart must persist across sessions (logged-in users). Guest users get a session-based cart that merges upon login.

### FR-3: Checkout
Users can convert their cart into an order. The checkout flow must validate inventory, calculate totals (with tax and shipping), process payment, and create the order atomically. If any step fails, all previous steps must be compensated (Saga pattern).

### FR-4: Inventory Management
The system must track available stock for every product in real time. When a user starts checkout, inventory must be reserved (soft lock). Upon payment confirmation, the reservation is confirmed (stock decremented). If checkout fails, the reservation is released.

### FR-5: Order Lifecycle
Orders follow a state machine: CREATED -> PAYMENT_PENDING -> CONFIRMED -> PROCESSING -> SHIPPED -> IN_TRANSIT -> DELIVERED. Each state transition is recorded. Users can view order history and current status.

### FR-6: Payment Processing
The system must charge the user's payment method (credit card, wallet, etc.) during checkout. Payments must be idempotent -- retrying a failed payment request must not double-charge. Support refunds for canceled orders.

### FR-7: Shipping
Once an order is confirmed and packed, the shipping service creates a shipment with a carrier (UPS, FedEx, etc.), generates a tracking number, and provides real-time tracking updates.

### FR-8: Notifications
Users receive notifications at key order milestones: order confirmation, payment receipt, shipment tracking, delivery confirmation. Channels include email, push notification, and SMS.

### FR-9: Price Calculation
Compute order total including product prices, quantity discounts, promo codes/coupons, applicable taxes, and shipping costs. Display price breakdown to the user before payment.

### FR-10: Graceful Degradation
If a downstream service is unavailable, the system must degrade gracefully. Product search can serve cached results. Cart operations can queue in Redis. Payment retries use exponential backoff. The user should never see a blank page.

---

## 5. Non-Functional Requirements

| Requirement              | Target                           | Rationale                                                      |
|--------------------------|----------------------------------|----------------------------------------------------------------|
| **Search Latency**       | p99 < 200 ms                     | Users abandon pages with > 3 second load times                 |
| **Checkout Latency**     | p99 < 2 seconds                  | Payment gateway is the bottleneck; user expects confirmation   |
| **Cart Latency**         | p99 < 50 ms                      | Cart must feel instant; Redis ensures sub-10ms operations      |
| **Availability**         | 99.99% (52 min/year downtime)    | E-commerce downtime = direct revenue loss ($M/minute for Amazon)|
| **Order Throughput**     | 35K orders/sec (Black Friday)    | 10x normal peak; auto-scale to handle spikes                   |
| **Search Throughput**    | 500K searches/sec (peak)         | 1B page views/day with search on most pages                    |
| **Data Durability**      | Zero order/payment loss           | Financial data must never be lost; replicated writes            |
| **Consistency**          | Strong for inventory/payments     | Cannot oversell; payment must be exactly-once                  |
| **Eventual Consistency** | < 5 sec for catalog/cart          | Product updates, cart syncs can be slightly stale               |
| **Scalability**          | Linear horizontal scaling          | Each microservice scales independently                         |
| **Fault Tolerance**      | Single service failure = degraded, not down | Circuit breakers, fallbacks, bulkheads              |
| **Idempotency**          | All write operations idempotent    | Network retries must not cause duplicate charges/orders        |

---

## 6. API Design

### 6.1 Product Catalog APIs

**Search Products:**

```
GET /api/v1/products?q=laptop&category=electronics&minPrice=500&maxPrice=2000
    &brand=apple&sort=relevance&page=1&size=20
Authorization: Bearer <token>
Accept: application/json
```

**Query Parameters:**

| Parameter    | Type     | Required | Default     | Description                                  |
|--------------|----------|----------|-------------|----------------------------------------------|
| `q`          | String   | No       | --          | Full-text search keyword                     |
| `category`   | String   | No       | null        | Filter by category (hierarchical)            |
| `minPrice`   | Decimal  | No       | 0           | Minimum price filter                         |
| `maxPrice`   | Decimal  | No       | MAX         | Maximum price filter                         |
| `brand`      | String   | No       | null        | Filter by brand name                         |
| `sort`       | String   | No       | "relevance" | Sort: relevance, price_asc, price_desc, rating |
| `page`       | Integer  | No       | 1           | Page number (1-indexed)                      |
| `size`       | Integer  | No       | 20          | Results per page (max 100)                   |

**Response (200 OK):**

```json
{
  "total": 4523,
  "page": 1,
  "size": 20,
  "products": [
    {
      "product_id": "prod_abc123",
      "title": "MacBook Pro 16-inch M3 Max",
      "description": "Apple MacBook Pro with M3 Max chip...",
      "price": 3499.00,
      "currency": "USD",
      "category": "Electronics > Computers > Laptops",
      "brand": "Apple",
      "rating": 4.7,
      "review_count": 12450,
      "in_stock": true,
      "image_url": "https://cdn.example.com/products/prod_abc123/main.jpg",
      "seller_id": "seller_xyz"
    }
  ],
  "facets": {
    "brands": [{"name": "Apple", "count": 342}, {"name": "Dell", "count": 287}],
    "price_ranges": [{"range": "500-1000", "count": 1200}, {"range": "1000-2000", "count": 890}]
  }
}
```

**Get Product Detail:**

```
GET /api/v1/products/{productId}
Authorization: Bearer <token>
```

**Response (200 OK):**

```json
{
  "product_id": "prod_abc123",
  "title": "MacBook Pro 16-inch M3 Max",
  "description": "Full description with specifications...",
  "price": 3499.00,
  "original_price": 3999.00,
  "discount_percent": 12,
  "currency": "USD",
  "category_path": ["Electronics", "Computers", "Laptops"],
  "brand": "Apple",
  "rating": 4.7,
  "review_count": 12450,
  "in_stock": true,
  "stock_quantity": 243,
  "attributes": {
    "processor": "M3 Max",
    "ram": "64GB",
    "storage": "1TB SSD",
    "screen_size": "16 inches"
  },
  "images": [
    "https://cdn.example.com/products/prod_abc123/1.jpg",
    "https://cdn.example.com/products/prod_abc123/2.jpg"
  ],
  "seller": {
    "seller_id": "seller_xyz",
    "name": "Apple Official Store",
    "rating": 4.9
  }
}
```

**Response (404 Not Found):**

```json
{
  "error": "PRODUCT_NOT_FOUND",
  "message": "Product with ID 'prod_abc123' does not exist.",
  "code": 404
}
```

### 6.2 Cart APIs

**Add Item to Cart:**

```
POST /api/v1/cart/items
Authorization: Bearer <token>
Content-Type: application/json
Idempotency-Key: idem_abc123
```

**Request:**

```json
{
  "product_id": "prod_abc123",
  "quantity": 2
}
```

**Response (200 OK):**

```json
{
  "cart_id": "cart_user_789",
  "items": [
    {
      "product_id": "prod_abc123",
      "title": "MacBook Pro 16-inch M3 Max",
      "quantity": 2,
      "unit_price": 3499.00,
      "subtotal": 6998.00,
      "in_stock": true
    }
  ],
  "total_items": 2,
  "subtotal": 6998.00
}
```

**Update Cart Item Quantity:**

```
PUT /api/v1/cart/items/{productId}
Authorization: Bearer <token>
Content-Type: application/json
```

**Request:**

```json
{
  "quantity": 3
}
```

**Remove Item from Cart:**

```
DELETE /api/v1/cart/items/{productId}
Authorization: Bearer <token>
```

**Get Cart:**

```
GET /api/v1/cart
Authorization: Bearer <token>
```

**Response (200 OK):**

```json
{
  "cart_id": "cart_user_789",
  "items": [
    {
      "product_id": "prod_abc123",
      "title": "MacBook Pro 16-inch M3 Max",
      "quantity": 2,
      "unit_price": 3499.00,
      "subtotal": 6998.00,
      "in_stock": true
    },
    {
      "product_id": "prod_def456",
      "title": "USB-C Hub 10-in-1",
      "quantity": 1,
      "unit_price": 49.99,
      "subtotal": 49.99,
      "in_stock": true
    }
  ],
  "total_items": 3,
  "subtotal": 7047.99,
  "estimated_tax": 563.84,
  "estimated_shipping": 0.00,
  "estimated_total": 7611.83
}
```

### 6.3 Order APIs

**Place Order (Checkout):**

```
POST /api/v1/orders/checkout
Authorization: Bearer <token>
Content-Type: application/json
Idempotency-Key: idem_checkout_xyz
```

**Request:**

```json
{
  "shipping_address_id": "addr_home_123",
  "payment_method_id": "pm_visa_4242",
  "promo_code": "SAVE10",
  "shipping_method": "STANDARD"
}
```

**Response (201 Created):**

```json
{
  "order_id": "ord_20260426_abc123",
  "status": "CONFIRMED",
  "items": [
    {
      "product_id": "prod_abc123",
      "title": "MacBook Pro 16-inch M3 Max",
      "quantity": 2,
      "unit_price": 3499.00,
      "subtotal": 6998.00
    }
  ],
  "subtotal": 6998.00,
  "discount": -699.80,
  "tax": 503.86,
  "shipping": 0.00,
  "total": 6802.06,
  "payment": {
    "payment_id": "pay_xyz789",
    "method": "VISA **** 4242",
    "status": "CAPTURED"
  },
  "shipping_address": {
    "name": "Karan Kanoji",
    "line1": "123 Main St",
    "city": "San Francisco",
    "state": "CA",
    "zip": "94105",
    "country": "US"
  },
  "estimated_delivery": "2026-04-30",
  "created_at": "2026-04-26T14:30:00Z"
}
```

**Response (409 Conflict -- insufficient stock):**

```json
{
  "error": "INSUFFICIENT_STOCK",
  "message": "Product 'prod_abc123' only has 1 unit available, but 2 were requested.",
  "code": 409,
  "available_quantity": 1
}
```

**Response (402 Payment Required -- payment failed):**

```json
{
  "error": "PAYMENT_FAILED",
  "message": "Payment declined by card issuer. Please try a different payment method.",
  "code": 402,
  "payment_error": "CARD_DECLINED"
}
```

**Get Order:**

```
GET /api/v1/orders/{orderId}
Authorization: Bearer <token>
```

**Get Order History:**

```
GET /api/v1/orders?page=1&size=10&status=DELIVERED
Authorization: Bearer <token>
```

### 6.4 Payment APIs

**Process Payment (Internal -- called by Order Service):**

```
POST /api/v1/internal/payments
X-Internal-Auth: <service-token>
Content-Type: application/json
Idempotency-Key: pay_ord_20260426_abc123
```

**Request:**

```json
{
  "order_id": "ord_20260426_abc123",
  "user_id": "user_789",
  "amount": 6802.06,
  "currency": "USD",
  "payment_method_id": "pm_visa_4242"
}
```

**Response (200 OK):**

```json
{
  "payment_id": "pay_xyz789",
  "order_id": "ord_20260426_abc123",
  "amount": 6802.06,
  "currency": "USD",
  "status": "CAPTURED",
  "gateway_reference": "ch_stripe_abc123",
  "created_at": "2026-04-26T14:30:01Z"
}
```

**Refund Payment:**

```
POST /api/v1/internal/payments/{paymentId}/refund
X-Internal-Auth: <service-token>
Content-Type: application/json
Idempotency-Key: refund_pay_xyz789
```

**Request:**

```json
{
  "amount": 6802.06,
  "reason": "ORDER_CANCELLED"
}
```

---

## 7. Data Model

### 7.1 Product

```
Table: products
+-------------------+-------------------+---------------------------------------------+
| Column            | Type              | Description                                 |
+-------------------+-------------------+---------------------------------------------+
| product_id        | VARCHAR(36) PK    | Unique product identifier (UUID)            |
| title             | VARCHAR(500)      | Product title                               |
| description       | TEXT              | Full product description                    |
| price             | DECIMAL(12,2)     | Current selling price                       |
| original_price    | DECIMAL(12,2)     | Original price (before discount)            |
| currency          | VARCHAR(3)        | ISO 4217 currency code (USD, EUR)           |
| category_id       | VARCHAR(36) FK    | Reference to category tree                  |
| brand             | VARCHAR(200)      | Brand name                                  |
| seller_id         | VARCHAR(36) FK    | Reference to seller                         |
| rating            | DECIMAL(2,1)      | Average rating (1.0 - 5.0)                  |
| review_count      | INTEGER           | Number of reviews                           |
| status            | ENUM              | ACTIVE, INACTIVE, DELETED                   |
| attributes        | JSONB             | Flexible key-value attributes               |
| image_urls        | TEXT[]            | Array of CDN image URLs                     |
| created_at        | TIMESTAMP         | Creation timestamp                          |
| updated_at        | TIMESTAMP         | Last update timestamp                       |
+-------------------+-------------------+---------------------------------------------+
Indexes: (category_id), (brand), (seller_id), (status, created_at)
Stored in: PostgreSQL (write master) + Elasticsearch (search replica)
```

### 7.2 CartItem

```
Stored in: Redis (Hash per user)
Key: cart:{userId}
TTL: 30 days (logged-in users), 24 hours (guest sessions)

Hash Fields:
+-------------------+-------------------+---------------------------------------------+
| Field             | Type              | Description                                 |
+-------------------+-------------------+---------------------------------------------+
| {productId}       | JSON String       | Serialized cart item entry                  |
+-------------------+-------------------+---------------------------------------------+

Cart Item JSON Structure:
{
  "product_id": "prod_abc123",
  "title": "MacBook Pro 16-inch M3 Max",
  "quantity": 2,
  "unit_price": 3499.00,
  "image_url": "https://cdn.example.com/products/prod_abc123/thumb.jpg",
  "added_at": "2026-04-26T10:00:00Z"
}

Redis Commands:
  HSET cart:user_789 prod_abc123 '{"product_id":"prod_abc123","quantity":2,...}'
  HGET cart:user_789 prod_abc123
  HDEL cart:user_789 prod_abc123
  HGETALL cart:user_789
  EXPIRE cart:user_789 2592000
```

### 7.3 Order

```
Table: orders
+-------------------+-------------------+---------------------------------------------+
| Column            | Type              | Description                                 |
+-------------------+-------------------+---------------------------------------------+
| order_id          | VARCHAR(36) PK    | Unique order identifier                     |
| user_id           | VARCHAR(36) FK    | User who placed the order                   |
| status            | ENUM              | CREATED, PAYMENT_PENDING, CONFIRMED,        |
|                   |                   | PROCESSING, SHIPPED, IN_TRANSIT, DELIVERED, |
|                   |                   | CANCELLED, REFUNDED                         |
| subtotal          | DECIMAL(12,2)     | Sum of all item subtotals                   |
| discount          | DECIMAL(12,2)     | Total discount applied                      |
| tax               | DECIMAL(12,2)     | Tax amount                                  |
| shipping_cost     | DECIMAL(12,2)     | Shipping fee                                |
| total             | DECIMAL(12,2)     | Final amount charged                        |
| currency          | VARCHAR(3)        | ISO 4217 currency code                      |
| shipping_address  | JSONB             | Denormalized shipping address snapshot      |
| promo_code        | VARCHAR(50)       | Applied promo code (nullable)               |
| payment_id        | VARCHAR(36) FK    | Reference to payment record                 |
| shipping_method   | ENUM              | STANDARD, EXPRESS, SAME_DAY                 |
| created_at        | TIMESTAMP         | Order creation timestamp                    |
| updated_at        | TIMESTAMP         | Last status update timestamp                |
| version           | INTEGER           | Optimistic locking version                  |
+-------------------+-------------------+---------------------------------------------+
Indexes: (user_id, created_at DESC), (status), (created_at)
Partitioned by: created_at (monthly partitions)
Sharded by: user_id (consistent hashing)
```

### 7.4 OrderItem

```
Table: order_items
+-------------------+-------------------+---------------------------------------------+
| Column            | Type              | Description                                 |
+-------------------+-------------------+---------------------------------------------+
| order_item_id     | VARCHAR(36) PK    | Unique line item identifier                 |
| order_id          | VARCHAR(36) FK    | Reference to parent order                   |
| product_id        | VARCHAR(36)       | Product at time of order (snapshot)         |
| title             | VARCHAR(500)      | Product title at time of order              |
| quantity          | INTEGER           | Quantity ordered                            |
| unit_price        | DECIMAL(12,2)     | Price per unit at time of order             |
| subtotal          | DECIMAL(12,2)     | quantity * unit_price                       |
| image_url         | VARCHAR(2048)     | Product thumbnail URL                       |
+-------------------+-------------------+---------------------------------------------+
Indexes: (order_id), (product_id)
Co-located with: orders table (same shard key via order_id -> user_id)
```

### 7.5 Inventory

```
Table: inventory
+-------------------+-------------------+---------------------------------------------+
| Column            | Type              | Description                                 |
+-------------------+-------------------+---------------------------------------------+
| product_id        | VARCHAR(36) PK    | Product identifier (1:1 with product)       |
| warehouse_id      | VARCHAR(36) PK    | Warehouse location (composite PK)           |
| total_stock       | INTEGER           | Total physical units in warehouse           |
| reserved_stock    | INTEGER           | Units currently reserved (in checkout)      |
| available_stock   | INTEGER           | total_stock - reserved_stock (computed)     |
| version           | INTEGER           | Optimistic locking version                  |
| updated_at        | TIMESTAMP         | Last modification timestamp                 |
+-------------------+-------------------+---------------------------------------------+
Indexes: (product_id), (warehouse_id)
Constraint: available_stock >= 0 (CHECK constraint prevents overselling)
```

### 7.6 Inventory Reservation

```
Table: inventory_reservations
+-------------------+-------------------+---------------------------------------------+
| Column            | Type              | Description                                 |
+-------------------+-------------------+---------------------------------------------+
| reservation_id    | VARCHAR(36) PK    | Unique reservation identifier               |
| order_id          | VARCHAR(36) FK    | Associated order                            |
| product_id        | VARCHAR(36) FK    | Reserved product                            |
| warehouse_id      | VARCHAR(36) FK    | Warehouse the stock is reserved in          |
| quantity          | INTEGER           | Number of units reserved                    |
| status            | ENUM              | RESERVED, CONFIRMED, RELEASED, EXPIRED      |
| expires_at        | TIMESTAMP         | Auto-release if not confirmed by this time  |
| created_at        | TIMESTAMP         | When reservation was made                   |
+-------------------+-------------------+---------------------------------------------+
Indexes: (order_id), (product_id, status), (expires_at, status)
TTL Job: Background job releases RESERVED items past expires_at (15 min default)
```

### 7.7 Payment

```
Table: payments
+-------------------+-------------------+---------------------------------------------+
| Column            | Type              | Description                                 |
+-------------------+-------------------+---------------------------------------------+
| payment_id        | VARCHAR(36) PK    | Unique payment identifier                   |
| order_id          | VARCHAR(36) FK    | Associated order (UNIQUE -- 1 payment:1 order)|
| user_id           | VARCHAR(36) FK    | User who made the payment                   |
| amount            | DECIMAL(12,2)     | Payment amount                              |
| currency          | VARCHAR(3)        | ISO 4217 currency code                      |
| status            | ENUM              | PENDING, CAPTURED, FAILED, REFUNDED         |
| payment_method    | VARCHAR(50)       | Card type or wallet identifier              |
| gateway_ref       | VARCHAR(200)      | External payment gateway reference ID       |
| idempotency_key   | VARCHAR(100) UQ   | Prevents duplicate charges on retry         |
| failure_reason    | VARCHAR(500)      | Reason for failure (nullable)               |
| created_at        | TIMESTAMP         | Payment initiation timestamp                |
| updated_at        | TIMESTAMP         | Last status change timestamp                |
+-------------------+-------------------+---------------------------------------------+
Indexes: (order_id UNIQUE), (user_id, created_at DESC), (idempotency_key UNIQUE)
```

### 7.8 Shipment

```
Table: shipments
+-------------------+-------------------+---------------------------------------------+
| Column            | Type              | Description                                 |
+-------------------+-------------------+---------------------------------------------+
| shipment_id       | VARCHAR(36) PK    | Unique shipment identifier                  |
| order_id          | VARCHAR(36) FK    | Associated order                            |
| carrier           | VARCHAR(50)       | Carrier name (UPS, FedEx, USPS, DHL)       |
| tracking_number   | VARCHAR(100)      | Carrier tracking number                     |
| status            | ENUM              | CREATED, PICKED, PACKED, SHIPPED,           |
|                   |                   | IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED     |
| shipping_address  | JSONB             | Destination address (snapshot from order)   |
| estimated_delivery| DATE              | Estimated delivery date                     |
| actual_delivery   | TIMESTAMP         | Actual delivery timestamp (nullable)        |
| shipping_method   | ENUM              | STANDARD, EXPRESS, SAME_DAY                 |
| weight_kg         | DECIMAL(8,2)      | Package weight                              |
| created_at        | TIMESTAMP         | Shipment creation timestamp                 |
| updated_at        | TIMESTAMP         | Last status update                          |
+-------------------+-------------------+---------------------------------------------+
Indexes: (order_id), (tracking_number UNIQUE), (status, estimated_delivery)
```

### Entity Relationship Summary

```
+------------+     +------------+     +-----------+
|  Product   |     |   Cart     |     | Inventory |
|  Catalog   |     | (Redis)    |     |           |
+-----+------+     +-----+------+     +-----+-----+
      |                   |                  |
      | product_id        | product_id       | product_id
      |                   |                  |
      +-------------------+------------------+
                          |
                    +-----v------+
                    |   Order    |
                    +-----+------+
                          |
              +-----------+-----------+
              |           |           |
        +-----v----+ +---v-----+ +--v--------+
        |OrderItems| | Payment | | Shipment  |
        +----------+ +---------+ +-----------+
```

---

## 8. High-Level Architecture

```
                              +------------------+
                              |   CDN (Images,   |
                              |   Static Assets) |
                              +--------+---------+
                                       |
+----------+                  +--------v---------+
|  Client  | ----(HTTPS)----> |   API Gateway    |
| (Web/App)|                  | (Rate Limit,     |
+----------+                  |  Auth, Routing)  |
                              +--------+---------+
                                       |
          +----------+---------+-------+-------+---------+----------+
          |          |         |       |       |         |          |
    +-----v---+ +---v----+ +-v------+ +-----v--+ +----v----+ +---v---------+
    | Product  | | Cart   | | Order  | |Inventory| |Payment | | Shipping    |
    | Catalog  | |Service | |Service | |Service  | |Service | | Service     |
    | Service  | |        | |        | |         | |        | |             |
    +-----+----+ +---+----+ +---+----+ +----+----+ +---+----+ +------+------+
          |          |         |  |        |          |           |
          |          |         |  |        |          |           |
    +-----v----+ +--v---+ +--v--v---+ +--v------+ +-v--------+ +v----------+
    |Elastic-  | |Redis | |Postgres | |Postgres | |Payment   | |Carrier    |
    |search    | |      | |(Orders) | |(Inv.)   | |Gateway   | |API (UPS,  |
    |(Products)| |(Cart)| |         | |         | |(Stripe)  | | FedEx)    |
    +----------+ +------+ +---------+ +---------+ +----------+ +-----------+
          |          |         |          |           |            |
          +----------+---------+----------+-----------+------------+
                               |
                        +------v------+
                        |    Kafka    |
                        | (Event Bus)|
                        +------+------+
                               |
                   +-----------+-----------+
                   |                       |
            +------v-------+      +-------v--------+
            | Notification |      | Analytics /    |
            | Service      |      | Reporting      |
            | (Email,Push, |      | Service        |
            |  SMS)        |      |                |
            +--------------+      +----------------+
```

### Numbered Flow: Place Order (Happy Path)

```
(1)  Client --POST /orders/checkout--> API Gateway
(2)  API Gateway --> authenticates user, rate-limits, routes to Order Service
(3)  Order Service --> Cart Service: GET cart items for user_789
(4)  Cart Service --> Redis: HGETALL cart:user_789
(5)  Cart Service --> Order Service: return cart (2 items, total $7047.99)
(6)  Order Service: create order record (status: CREATED) in PostgreSQL
(7)  Order Service --> Inventory Service: reserve stock for each item
(8)  Inventory Service --> PostgreSQL: BEGIN; SELECT FOR UPDATE inventory
         WHERE product_id='prod_abc123' AND available_stock >= 2;
         UPDATE inventory SET reserved_stock = reserved_stock + 2,
         available_stock = available_stock - 2; COMMIT;
(9)  Inventory Service --> Order Service: reservation confirmed (reservation_id)
(10) Order Service: update order status to PAYMENT_PENDING
(11) Order Service --> Payment Service: charge $6802.06 (with idempotency key)
(12) Payment Service --> Stripe API: create payment intent, capture
(13) Stripe API --> Payment Service: payment captured (gateway_ref: ch_stripe_abc)
(14) Payment Service --> PostgreSQL: save payment record (status: CAPTURED)
(15) Payment Service --> Order Service: payment confirmed (payment_id)
(16) Order Service: update order status to CONFIRMED
(17) Order Service --> Inventory Service: confirm reservation (RESERVED -> CONFIRMED)
(18) Order Service --> Shipping Service: create shipment for order
(19) Shipping Service --> PostgreSQL: save shipment record (status: CREATED)
(20) Order Service --> Kafka: publish OrderConfirmedEvent
(21) Kafka --> Notification Service: consume event, send order confirmation email
(22) Kafka --> Analytics Service: consume event, update dashboards
(23) Order Service --> Cart Service: clear cart for user_789
(24) Cart Service --> Redis: DEL cart:user_789
(25) Order Service --> API Gateway --> Client: 201 Created (order details)
```

### Numbered Flow: Place Order (Payment Failure -- Saga Compensation)

```
(1)  Client --POST /orders/checkout--> API Gateway
(2)  API Gateway --> Order Service: initiate checkout
(3)  Order Service --> Cart Service: get cart items
(4)  Order Service: create order (status: CREATED)
(5)  Order Service --> Inventory Service: reserve stock --> SUCCESS
(6)  Order Service: update status to PAYMENT_PENDING
(7)  Order Service --> Payment Service: charge $6802.06
(8)  Payment Service --> Stripe API: payment intent
(9)  Stripe API --> Payment Service: DECLINED (insufficient funds)
(10) Payment Service --> Order Service: PAYMENT_FAILED

--- Saga Compensation Begins ---
(11) Order Service --> Inventory Service: RELEASE reservation (RESERVED -> RELEASED)
(12) Inventory Service --> PostgreSQL: UPDATE inventory
         SET reserved_stock = reserved_stock - 2,
         available_stock = available_stock + 2
         WHERE product_id = 'prod_abc123';
(13) Order Service: update order status to CANCELLED
(14) Order Service --> Kafka: publish OrderCancelledEvent
(15) Kafka --> Notification Service: send "payment failed" notification
(16) Order Service --> Client: 402 Payment Required (payment failed)
```

---

## 9. Component Deep Dive

### 9.1 Product Catalog Service

**Responsibilities:**
- Serve product search and filtering (delegated to Elasticsearch)
- Serve product detail pages (from PostgreSQL or cache)
- Manage product CRUD operations (admin/seller writes)
- Maintain data sync between PostgreSQL (source of truth) and Elasticsearch (search index)

**Architecture:**

```
                    +------------------------+
                    | Product Catalog Service |
                    +--------+---------------+
                             |
          +------------------+------------------+
          |                  |                  |
    +-----v------+   +------v-------+   +-----v------+
    | Search     |   | Product      |   | Sync       |
    | Handler    |   | Detail       |   | Worker     |
    | (ES query) |   | Handler      |   | (CDC)      |
    +-----+------+   +------+-------+   +-----+------+
          |                  |                  |
    +-----v------+   +------v-------+   +-----v------+
    |Elasticsearch|  |  PostgreSQL  |   |   Kafka    |
    | (search)   |   |  (master)   |   | (CDC events)|
    +------------+   +--------------+   +------------+
```

**Search Flow:**

```
(1) Client --> Product Catalog Service: GET /products?q=laptop&category=electronics
(2) Service builds Elasticsearch query:
    {
      "query": {
        "bool": {
          "must": [{"multi_match": {"query": "laptop", "fields": ["title^3", "description"]}}],
          "filter": [{"term": {"category": "electronics"}}, {"term": {"status": "ACTIVE"}}]
        }
      },
      "sort": [{"_score": "desc"}, {"rating": "desc"}],
      "aggs": {"brands": {"terms": {"field": "brand"}}, "price_ranges": {"range": {...}}}
    }
(3) Elasticsearch returns ranked results + facets
(4) Service enriches with real-time inventory availability (cache lookup)
(5) Service returns paginated results to client
```

**Product Detail Flow:**

```
(1) Client --> Product Catalog Service: GET /products/{productId}
(2) Service checks local cache (Caffeine, 5 min TTL)
(3) Cache MISS --> PostgreSQL: SELECT * FROM products WHERE product_id = ?
(4) Service checks inventory: available_stock from Redis cache
(5) Service assembles full product response
(6) Service caches result, returns to client
```

**Data Sync (PostgreSQL -> Elasticsearch):**

```
(1) Product updated in PostgreSQL (admin/seller action)
(2) Change Data Capture (Debezium) detects row change
(3) Debezium publishes ProductUpdatedEvent to Kafka
(4) Sync Worker consumes event from Kafka
(5) Sync Worker transforms data to Elasticsearch document format
(6) Sync Worker upserts document in Elasticsearch
(7) Search results reflect update within ~2-5 seconds
```

**Edge Cases:**
- **Stale search results**: Elasticsearch may lag behind PostgreSQL by a few seconds. Acceptable for search; product detail page always hits PostgreSQL for price/stock.
- **Category hierarchy**: Categories form a tree (Electronics > Computers > Laptops). Store the full path in Elasticsearch for hierarchical filtering.
- **Price changes**: When a product's price changes, cached cart items may show stale prices. Cart must re-validate prices at checkout time.

### 9.2 Cart Service

**Responsibilities:**
- Store and manage user cart state (add, remove, update items)
- Persist carts across sessions for logged-in users
- Support guest carts with session-based TTL
- Merge guest cart into user cart on login
- Validate item availability before adding (soft check, non-blocking)

**Architecture:**

```
+-------------------+
|   Cart Service    |
+--------+----------+
         |
+--------v----------+
| Redis Cluster     |
| (3 masters,       |
|  3 replicas)      |
|                   |
| Key: cart:{userId}|
| Type: Hash        |
| TTL: 30 days      |
+-------------------+
```

**Add Item Flow:**

```
(1) Client --POST /cart/items {productId: "prod_abc", qty: 2}--> Cart Service
(2) Cart Service --> Inventory Service: soft stock check
    (non-blocking -- if Inventory Service is down, skip check and allow add)
(3) Inventory Service: available_stock for prod_abc = 243 (enough)
(4) Cart Service --> Redis: HGET cart:user_789 prod_abc
(5) Item exists? Merge quantities. Item is new? Create entry.
(6) Cart Service --> Redis: HSET cart:user_789 prod_abc '{...updated JSON...}'
(7) Cart Service --> Redis: EXPIRE cart:user_789 2592000  (30-day TTL refresh)
(8) Cart Service: compute cart totals (iterate all HGETALL fields)
(9) Cart Service --> Client: return updated cart
```

**Guest Cart Merge on Login:**

```
(1) Guest browses, adds items to cart (key: cart:session_abc123)
(2) Guest logs in as user_789
(3) Cart Service: HGETALL cart:session_abc123  (get guest cart items)
(4) Cart Service: HGETALL cart:user_789  (get existing user cart items)
(5) For each guest item:
    - If product already in user cart: keep higher quantity
    - If product not in user cart: add it
(6) Cart Service: HSET cart:user_789 ... (merged items)
(7) Cart Service: DEL cart:session_abc123  (delete guest cart)
(8) Return merged cart to client
```

**Edge Cases:**
- **Item goes out of stock after adding to cart**: Cart shows "Only 1 left" or "Out of stock" badge. Validated again at checkout.
- **Price changes after adding to cart**: Cart displays current price (fetched from Product Service). User sees updated price at checkout.
- **Cart size limits**: Maximum 100 unique items per cart, maximum quantity 10 per item (prevent abuse).
- **Redis failover**: If Redis primary fails, replica is promoted. During failover (~5-15 sec), cart reads may serve slightly stale data. Writes queue and retry.

### 9.3 Order Service

**Responsibilities:**
- Orchestrate the checkout flow (the Saga orchestrator)
- Create and manage order records in PostgreSQL
- Manage order lifecycle state machine
- Coordinate with Inventory, Payment, and Shipping services
- Publish order events to Kafka for downstream consumers

**Order State Machine:**

```
                    +----------+
                    | CREATED  |
                    +----+-----+
                         |
                    (inventory reserved)
                         |
                +--------v---------+
                | PAYMENT_PENDING  |
                +--------+---------+
                         |
             +-----------+-----------+
             |                       |
        (payment OK)           (payment FAIL)
             |                       |
    +--------v-------+      +--------v-------+
    |   CONFIRMED    |      |   CANCELLED    |
    +--------+-------+      +----------------+
             |
        (picked & packed)
             |
    +--------v-------+
    |  PROCESSING    |
    +--------+-------+
             |
        (handed to carrier)
             |
    +--------v-------+
    |    SHIPPED     |
    +--------+-------+
             |
        (in transit)
             |
    +--------v-------+
    |   IN_TRANSIT   |
    +--------+-------+
             |
        (delivered)
             |
    +--------v-------+
    |   DELIVERED    |
    +----------------+
```

**Checkout Orchestration (Saga Orchestrator):**

```java
// Pseudocode for Order Service checkout orchestration
public OrderResponse checkout(CheckoutRequest request) {
    String orderId = generateOrderId();
    String idempotencyKey = request.getIdempotencyKey();

    // Step 1: Idempotency check
    Order existingOrder = orderRepository.findByIdempotencyKey(idempotencyKey);
    if (existingOrder != null) {
        return toResponse(existingOrder);  // Return cached result
    }

    // Step 2: Retrieve cart
    Cart cart = cartService.getCart(request.getUserId());
    if (cart.isEmpty()) {
        throw new EmptyCartException();
    }

    // Step 3: Create order (CREATED status)
    Order order = createOrder(orderId, request, cart);
    orderRepository.save(order);

    try {
        // Step 4: Reserve inventory
        List<Reservation> reservations = inventoryService.reserveStock(
            orderId, cart.getItems()
        );

        // Step 5: Update order (PAYMENT_PENDING)
        order.setStatus(OrderStatus.PAYMENT_PENDING);
        orderRepository.save(order);

        // Step 6: Process payment
        PaymentResult payment = paymentService.charge(
            orderId, order.getTotal(), request.getPaymentMethodId()
        );

        // Step 7: Confirm inventory reservation
        inventoryService.confirmReservations(reservations);

        // Step 8: Update order (CONFIRMED)
        order.setStatus(OrderStatus.CONFIRMED);
        order.setPaymentId(payment.getPaymentId());
        orderRepository.save(order);

        // Step 9: Create shipment
        shippingService.createShipment(orderId, request.getShippingAddress());

        // Step 10: Clear cart
        cartService.clearCart(request.getUserId());

        // Step 11: Publish event (async)
        eventPublisher.publish(new OrderConfirmedEvent(order));

        return toResponse(order);

    } catch (InsufficientStockException e) {
        // Compensation: cancel order
        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);
        eventPublisher.publish(new OrderCancelledEvent(order, "INSUFFICIENT_STOCK"));
        throw e;

    } catch (PaymentFailedException e) {
        // Compensation: release inventory, cancel order
        inventoryService.releaseReservations(orderId);
        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);
        eventPublisher.publish(new OrderCancelledEvent(order, "PAYMENT_FAILED"));
        throw e;
    }
}
```

**Edge Cases:**
- **Idempotent checkout**: If the client retries (network timeout), the same idempotency key returns the existing order without re-processing.
- **Partial inventory failure**: If item A is reserved but item B is out of stock, release item A's reservation and fail the entire order.
- **Order timeout**: If checkout takes longer than 30 seconds (e.g., payment gateway slow), the client receives a timeout. The server still completes and the client can poll `GET /orders/{orderId}`.
- **Concurrent checkouts for the same cart**: The first to reserve inventory wins. The second gets INSUFFICIENT_STOCK if the last units were taken.

### 9.4 Inventory Service

**Responsibilities:**
- Track available, reserved, and total stock per product per warehouse
- Reserve stock during checkout (soft lock with expiration)
- Confirm reservations on successful payment
- Release reservations on failed/expired checkouts
- Background job to expire stale reservations (TTL: 15 minutes)
- Prevent overselling with database-level constraints

**Reservation Flow:**

```
(1) Order Service --> Inventory Service: reserve(orderId, [{prodA, qty:2}, {prodB, qty:1}])
(2) Inventory Service: BEGIN TRANSACTION
(3) For each item:
    SELECT product_id, available_stock, version
    FROM inventory
    WHERE product_id = 'prodA' AND warehouse_id = 'wh_west'
    FOR UPDATE;  -- pessimistic lock on row
(4) Check: available_stock >= requested_quantity
    IF NO: ROLLBACK, throw InsufficientStockException
(5) UPDATE inventory
    SET reserved_stock = reserved_stock + 2,
        available_stock = available_stock - 2,
        version = version + 1
    WHERE product_id = 'prodA' AND warehouse_id = 'wh_west';
(6) INSERT INTO inventory_reservations (reservation_id, order_id, product_id,
    warehouse_id, quantity, status, expires_at)
    VALUES (uuid(), orderId, 'prodA', 'wh_west', 2, 'RESERVED', NOW() + 15 min);
(7) COMMIT
(8) Return reservation IDs to Order Service
```

**Reservation Expiry Job:**

```
Every 60 seconds:
(1) SELECT * FROM inventory_reservations
    WHERE status = 'RESERVED' AND expires_at < NOW();
(2) For each expired reservation:
    BEGIN TRANSACTION;
    UPDATE inventory SET reserved_stock = reserved_stock - qty,
                         available_stock = available_stock + qty
    WHERE product_id = ? AND warehouse_id = ?;
    UPDATE inventory_reservations SET status = 'EXPIRED' WHERE reservation_id = ?;
    COMMIT;
(3) Log expired reservations for monitoring
```

**Edge Cases:**
- **Multi-warehouse fulfillment**: If one warehouse lacks stock, check other warehouses. Split fulfillment across warehouses if needed.
- **Race condition on last unit**: Two concurrent checkouts for the last item. `SELECT FOR UPDATE` serializes access -- the first transaction wins, the second waits then sees available_stock = 0 and fails.
- **Reservation leak**: If Order Service crashes after reserving but before confirming, the 15-minute expiry job reclaims the stock automatically.

### 9.5 Payment Service

**Responsibilities:**
- Process payments via external payment gateways (Stripe, PayPal)
- Ensure idempotent payment processing (no double charges)
- Handle payment failures with clear error codes
- Process refunds for canceled or returned orders
- Store payment records for auditing and reconciliation

**Payment Flow:**

```
(1) Order Service --> Payment Service: charge(orderId, $6802.06, paymentMethodId, idempotencyKey)
(2) Payment Service: check idempotency_key in payments table
    SELECT * FROM payments WHERE idempotency_key = 'pay_ord_abc123';
(3) If found and status = CAPTURED: return existing payment (idempotent retry)
(4) If not found: INSERT payment record (status: PENDING)
(5) Payment Service --> Stripe API: POST /payment_intents
    {amount: 680206, currency: "usd", payment_method: "pm_visa_4242",
     confirm: true, idempotency_key: "pay_ord_abc123"}
(6) Stripe API --> Payment Service: 200 OK {id: "pi_abc", status: "succeeded"}
(7) Payment Service: UPDATE payments SET status = 'CAPTURED',
    gateway_ref = 'pi_abc' WHERE idempotency_key = 'pay_ord_abc123';
(8) Payment Service --> Order Service: PaymentResult(CAPTURED, payment_id)
```

**Refund Flow:**

```
(1) Order Service --> Payment Service: refund(paymentId, amount, reason)
(2) Payment Service: look up original payment record
(3) Payment Service --> Stripe API: POST /refunds
    {payment_intent: "pi_abc", amount: 680206}
(4) Stripe API --> Payment Service: refund confirmed
(5) Payment Service: UPDATE payments SET status = 'REFUNDED'
(6) Payment Service --> Kafka: publish PaymentRefundedEvent
```

**Idempotency Implementation:**

```
+------------------------------------------------------------------+
| Request with Idempotency-Key: "pay_ord_abc123"                   |
+------------------------------------------------------------------+
| First call:                                                       |
|   (1) Check DB: no record found for key                          |
|   (2) Insert PENDING record with key                             |
|   (3) Call Stripe API                                             |
|   (4) Update record to CAPTURED                                   |
|   (5) Return success                                              |
+------------------------------------------------------------------+
| Retry (same key):                                                 |
|   (1) Check DB: record found, status = CAPTURED                  |
|   (2) Return cached result immediately (no Stripe call)           |
+------------------------------------------------------------------+
| Retry (same key, PENDING -- previous call in progress/crashed):  |
|   (1) Check DB: record found, status = PENDING                   |
|   (2) Check Stripe with idempotency_key                          |
|   (3) If Stripe has result: update DB, return result              |
|   (4) If Stripe has no result: retry the charge                   |
+------------------------------------------------------------------+
```

**Edge Cases:**
- **Double charge prevention**: The idempotency key (one per order) ensures that retrying checkout never charges twice. Stripe also enforces idempotency on their end.
- **Gateway timeout**: If Stripe does not respond within 10 seconds, the payment status remains PENDING. The Order Service retries with the same idempotency key.
- **Partial refund**: For partial cancellations (some items returned), refund only the specific item amount, not the full order.
- **Currency precision**: All amounts stored as cents/smallest unit in the gateway call to avoid floating-point errors. Only converted to dollars for display.

### 9.6 Shipping Service

**Responsibilities:**
- Create shipment records when orders are confirmed
- Integrate with carrier APIs (UPS, FedEx, USPS, DHL)
- Generate tracking numbers
- Receive and relay tracking updates
- Calculate shipping costs and estimated delivery dates

**Shipment Flow:**

```
(1) Order Service --> Shipping Service: createShipment(orderId, items, address, method)
(2) Shipping Service: select best carrier based on method + destination + cost
(3) Shipping Service --> Carrier API (UPS): POST /shipments
    {destination: {...}, packages: [{weight: 2.5, dimensions: {...}}]}
(4) Carrier API --> Shipping Service: {tracking_number: "1Z999AA10123456784", label_url: "..."}
(5) Shipping Service: INSERT INTO shipments (shipment_id, order_id, carrier, tracking_number,
    status, estimated_delivery) VALUES (..., 'UPS', '1Z999AA...', 'CREATED', '2026-04-30')
(6) Shipping Service --> Kafka: publish ShipmentCreatedEvent

Tracking Updates (polling + webhooks):
(7) Carrier --> Shipping Service (webhook): package scanned at hub
(8) Shipping Service: UPDATE shipments SET status = 'IN_TRANSIT'
(9) Shipping Service --> Kafka: publish ShipmentStatusUpdatedEvent
(10) Kafka --> Notification Service: send "Your package is in transit" push notification

(11) Carrier --> Shipping Service (webhook): delivered
(12) Shipping Service: UPDATE shipments SET status = 'DELIVERED', actual_delivery = NOW()
(13) Shipping Service --> Kafka: publish ShipmentDeliveredEvent
(14) Kafka --> Order Service: update order status to DELIVERED
(15) Kafka --> Notification Service: send "Your package was delivered" notification
```

**Edge Cases:**
- **Carrier API downtime**: Queue shipment creation requests. Retry with exponential backoff. Order stays in CONFIRMED state until shipment is created.
- **Split shipments**: Large orders may ship in multiple packages. One order can have multiple shipment records.
- **Lost packages**: If no tracking update for X days, flag for investigation and trigger customer notification.

### 9.7 Notification Service

**Responsibilities:**
- Consume events from Kafka and send notifications to users
- Support multiple channels: email, push notification, SMS
- Template-based notification rendering
- Rate limiting to prevent notification spam
- Delivery tracking (sent, delivered, opened)

**Notification Flow:**

```
(1) Kafka --> Notification Service: consume OrderConfirmedEvent
(2) Notification Service: look up user preferences (email + push enabled)
(3) Notification Service: render email template with order details
(4) Notification Service --> Email Provider (SES/SendGrid): send order confirmation
(5) Notification Service --> Push Provider (FCM/APNs): send push notification
(6) Notification Service: log notification delivery status

Events that trigger notifications:
  - OrderConfirmedEvent     --> "Your order #ORD-12345 is confirmed!"
  - PaymentCapturedEvent    --> "Payment of $6802.06 received."
  - ShipmentCreatedEvent    --> "Your order has been shipped! Track: 1Z999AA..."
  - ShipmentInTransitEvent  --> "Your package is on its way!"
  - ShipmentDeliveredEvent  --> "Your package was delivered."
  - OrderCancelledEvent     --> "Your order has been cancelled. Refund issued."
  - PaymentRefundedEvent    --> "Refund of $6802.06 processed to VISA **** 4242."
```

**Edge Cases:**
- **Duplicate event consumption**: Kafka consumer may receive the same event twice (at-least-once delivery). Use event ID as deduplication key -- if notification already sent for that event ID, skip.
- **User notification preferences**: Respect user opt-outs. Check preferences before sending.
- **Rate limiting**: Maximum 10 notifications per user per hour. Batch updates if many status changes happen rapidly.

---

## 10. Saga Pattern Deep Dive

### Why Distributed Transactions Fail (2PC Problems)

In a monolithic system, a single database transaction can atomically update inventory, create an order, and process a payment. In a microservices architecture, each service owns its database. A single "checkout" operation spans multiple services and databases.

**Two-Phase Commit (2PC) -- and why it does not work at scale:**

```
Traditional 2PC:
                  +------------------+
                  | Transaction      |
                  | Coordinator      |
                  +--------+---------+
                           |
             +-------------+-------------+
             |             |             |
      +------v---+  +-----v----+  +----v------+
      |Inventory |  | Order    |  | Payment   |
      |   DB     |  |   DB     |  |   DB      |
      +----------+  +----------+  +-----------+

  Phase 1 (Prepare): Coordinator asks all: "Can you commit?"
    Inventory DB: "Yes, I can commit"  (holds row locks)
    Order DB:     "Yes, I can commit"  (holds row locks)
    Payment DB:   "Yes, I can commit"  (holds row locks)

  Phase 2 (Commit): Coordinator says: "Commit!"
    All three commit.

Problems with 2PC:
  (1) Blocking: All participants hold locks during PREPARE phase.
      If coordinator crashes between Phase 1 and Phase 2,
      all participants are stuck holding locks indefinitely.

  (2) Single Point of Failure: The coordinator is a bottleneck.
      If it goes down, the entire transaction is in limbo.

  (3) Performance: Network round-trips to all participants
      at each phase. Locks held across network hops.
      At 35K orders/sec (Black Friday), this is unacceptable.

  (4) Heterogeneous Databases: 2PC requires all databases to
      support XA transactions. Redis (cart) and Elasticsearch
      (products) do not support XA.

  (5) Availability: 2PC requires ALL participants to be up.
      If Payment Gateway is slow (1-2 sec), all other
      participants hold locks for that duration.
```

**Solution: Saga Pattern** -- break the distributed transaction into a sequence of local transactions, each with a compensating action.

### Choreography-Based Saga (Event-Driven)

In choreography, there is no central orchestrator. Each service listens for events and acts independently, publishing its own events for the next service in the chain.

```
Choreography-Based Saga: Checkout Flow

+----------+     +------------+     +-----------+     +----------+     +----------+
|  Order   |     | Inventory  |     |  Payment  |     | Shipping |     |Notification|
| Service  |     |  Service   |     |  Service  |     | Service  |     |  Service  |
+----+-----+     +-----+------+     +-----+-----+     +-----+----+     +-----+----+
     |                  |                  |                 |                |
     | (1) Create       |                  |                 |                |
     |  Order           |                  |                 |                |
     | (CREATED)        |                  |                 |                |
     |                  |                  |                 |                |
     |--OrderCreated--->|                  |                 |                |
     |   Event          |                  |                 |                |
     |            (2) Reserve              |                 |                |
     |                Stock                |                 |                |
     |                  |                  |                 |                |
     |                  |--StockReserved-->|                 |                |
     |                  |   Event          |                 |                |
     |                  |           (3) Process              |                |
     |                  |              Payment               |                |
     |                  |                  |                 |                |
     |<--PaymentCaptured|                  |                 |                |
     |   Event          |                  |                 |                |
     |                  |                  |                 |                |
     | (4) Confirm      |                  |                 |                |
     |  Order           |                  |                 |                |
     | (CONFIRMED)      |                  |                 |                |
     |                  |                  |                 |                |
     |--OrderConfirmed->|                  |                 |                |
     |   Event          |                  |                 |-->             |
     |                  | (5) Confirm      |                 |(6) Create     |
     |                  |  Reservation     |                 | Shipment      |
     |                  |                  |                 |               |
     |                  |                  |                 |          (7) Send
     |                  |                  |                 |          Notification
```

**Compensation Flow (Choreography -- Payment Fails):**

```
+----------+     +------------+     +-----------+
|  Order   |     | Inventory  |     |  Payment  |
| Service  |     |  Service   |     |  Service  |
+----+-----+     +-----+------+     +-----+-----+
     |                  |                  |
     |--OrderCreated--->|                  |
     |            Reserve Stock            |
     |                  |--StockReserved-->|
     |                  |           Process Payment
     |                  |                  |
     |                  |<--PaymentFailed--|
     |                  |   Event          |
     |                  |                  |
     |            (COMPENSATE)             |
     |            Release Stock            |
     |                  |                  |
     |<--StockReleased--|                  |
     |   Event          |                  |
     |                  |                  |
     | (COMPENSATE)     |                  |
     | Cancel Order     |                  |
     | (CANCELLED)      |                  |
```

### Orchestration-Based Saga (Central Orchestrator)

In orchestration, a central **Saga Orchestrator** (typically in the Order Service) coordinates the entire sequence, explicitly calling each service and handling failures.

```
Orchestration-Based Saga: Checkout Flow

                    +-------------------+
                    | Saga Orchestrator |
                    | (Order Service)   |
                    +---------+---------+
                              |
     +------------------------+------------------------+
     |                        |                        |
     |  (1) Reserve Stock     |                        |
     |----------------------->|                        |
     |                  +-----v------+                 |
     |                  | Inventory  |                 |
     |                  |  Service   |                 |
     |                  +-----+------+                 |
     |  (2) Stock Reserved    |                        |
     |<-----------------------+                        |
     |                                                 |
     |  (3) Process Payment                            |
     |------------------------------------------------>|
     |                                           +-----v------+
     |                                           |  Payment   |
     |                                           |  Service   |
     |                                           +-----+------+
     |  (4) Payment Captured                           |
     |<------------------------------------------------+
     |                                                 |
     |  (5) Confirm Reservation                        |
     |----------------------->|                        |
     |                  +-----v------+                 |
     |                  | Inventory  |                 |
     |                  |  Service   |                 |
     |                  +-----+------+                 |
     |  (6) Reservation Confirmed                      |
     |<-----------------------+                        |
     |                                                 |
     |  (7) Create Shipment                            |
     +---------------------------->+-------------------+
                              +----v-----+
                              | Shipping |
                              | Service  |
                              +----+-----+
     |  (8) Shipment Created       |
     |<----------------------------+
     |                                                 |
     | (9) Publish OrderConfirmedEvent --> Kafka        |
     | (10) Return success to client                   |
```

**Compensation Flow (Orchestration -- Payment Fails):**

```
                    +-------------------+
                    | Saga Orchestrator |
                    | (Order Service)   |
                    +---------+---------+
                              |
     |  (1) Reserve Stock     |
     |----------------------->| Inventory Service
     |  (2) Stock Reserved    |
     |<-----------------------+
     |                        |
     |  (3) Process Payment   |
     |------------------------------------------------>| Payment Service
     |  (4) PAYMENT FAILED    |                        |
     |<------------------------------------------------+
     |                        |
     |  --- COMPENSATION ---  |
     |                        |
     |  (5) Release Stock     |
     |----------------------->| Inventory Service
     |  (6) Stock Released    |
     |<-----------------------+
     |                        |
     |  (7) Cancel Order      |
     |  (8) Publish OrderCancelledEvent
     |  (9) Return 402 to client
```

**Compensation Actions for Each Step:**

```
+-------------------+-------------------------+-----------------------------------+
| Step              | Forward Action          | Compensation (Rollback)           |
+-------------------+-------------------------+-----------------------------------+
| 1. Create Order   | INSERT order (CREATED)  | UPDATE order status = CANCELLED   |
| 2. Reserve Stock  | Decrement available,    | Increment available,              |
|                   | increment reserved      | decrement reserved                |
| 3. Process Payment| Charge via Stripe       | Refund via Stripe                 |
| 4. Create Shipment| Create shipment record  | Cancel shipment with carrier      |
| 5. Send Notif.    | Send order confirmation  | Send cancellation notification    |
+-------------------+-------------------------+-----------------------------------+

Rules:
  - Compensations execute in REVERSE ORDER of the forward steps.
  - Compensations must be IDEMPOTENT (safe to retry).
  - Compensations may fail -- use retry with exponential backoff.
  - If a compensation itself fails after max retries,
    alert operations team for manual intervention.
```

### Comparison: Choreography vs Orchestration

```
+------------------------+----------------------------+-----------------------------+
| Aspect                 | Choreography               | Orchestration               |
+------------------------+----------------------------+-----------------------------+
| Coordination           | Decentralized (events)     | Centralized (orchestrator)  |
| Coupling               | Loose (services don't know | Tighter (orchestrator knows |
|                        | about each other)          | all participants)           |
| Complexity             | Grows with number of       | Centralized in one place    |
|                        | services (event spaghetti) |                             |
| Debugging              | Hard (distributed trace    | Easier (single place to     |
|                        | needed, no single view)    | see saga state)             |
| Adding new steps       | Add consumer, no change    | Modify orchestrator         |
|                        | to existing services       |                             |
| Failure handling       | Each service handles its   | Orchestrator handles all    |
|                        | own compensation            | compensations               |
| Testing                | Hard (integration tests    | Easier (unit test the       |
|                        | across all services)       | orchestrator logic)         |
| Scalability            | Better (no bottleneck)     | Orchestrator can bottleneck |
| Best for               | Simple sagas (2-3 steps)   | Complex sagas (4+ steps)    |
+------------------------+----------------------------+-----------------------------+

Recommendation for E-Commerce Checkout:
  USE ORCHESTRATION -- the checkout saga has 5+ steps with complex
  compensation logic. The orchestrator (Order Service) provides a single
  place to manage the saga state, making debugging and monitoring easier.
  At Amazon's scale, the orchestrator is itself horizontally scaled.
```

---

## 11. CQRS Pattern

### Why CQRS for E-Commerce?

E-commerce has **dramatically different read and write patterns**:

```
Reads (Query Side):
  - Product search: 500K QPS (browsing is the dominant activity)
  - Product detail: 200K QPS
  - Order history: 50K QPS
  - Read:Write ratio for catalog: 1000:1

Writes (Command Side):
  - Place order: 3.5K QPS (avg), 35K QPS (Black Friday)
  - Update inventory: 10K QPS
  - Cart updates: 6K QPS

Problem: Optimizing a single database for BOTH search (full-text, faceted)
AND transactional writes (ACID, locks) is impossible.

Solution: CQRS -- separate the read and write models.
```

### Command Side (Writes)

```
+-------------------+
| Command Side      |
+-------------------+
|                   |
| POST /orders      |  --> Order Service --> PostgreSQL (orders DB)
| POST /cart/items  |  --> Cart Service  --> Redis (cart)
| PUT /inventory    |  --> Inv. Service  --> PostgreSQL (inventory DB)
| POST /products    |  --> Product Svc   --> PostgreSQL (products DB)
|                   |
+-------------------+

Characteristics:
  - Strong consistency (ACID transactions)
  - Normalized data model (3NF)
  - Optimized for writes (minimal indexes)
  - PostgreSQL with row-level locking
```

### Query Side (Reads)

```
+-------------------+
| Query Side        |
+-------------------+
|                   |
| GET /products     |  --> Product Svc  --> Elasticsearch (search index)
| GET /products/:id |  --> Product Svc  --> Redis Cache --> PostgreSQL (fallback)
| GET /orders       |  --> Order Svc    --> Read Replica PostgreSQL
| GET /orders/:id   |  --> Order Svc    --> Redis Cache --> Read Replica
|                   |
+-------------------+

Characteristics:
  - Eventual consistency (acceptable for reads)
  - Denormalized data model (optimized for queries)
  - Materialized views, pre-computed aggregations
  - Elasticsearch for full-text search, Redis for hot data
  - Read replicas for PostgreSQL
```

### How Data Flows: Command Side -> Event -> Query Side

```
Write Path:
(1) Admin updates product price in PostgreSQL (command DB)
(2) Debezium CDC captures the row change
(3) CDC publishes ProductUpdatedEvent to Kafka
(4) Elasticsearch consumer updates the search index
(5) Cache invalidation consumer removes stale cache entries
(6) Search results reflect the new price within 2-5 seconds

+----------+     +-------+     +--------+     +---------------+
|PostgreSQL| --> |Debezium| --> | Kafka  | --> |Elasticsearch  |
|(Command) |     | (CDC)  |     |(Events)|     |(Query - Search)|
+----------+     +-------+     +---+----+     +---------------+
                                   |
                               +---v----------+
                               | Redis Cache   |
                               | (invalidate)  |
                               +--------------+
```

### Event Sourcing: Store Events, Rebuild State

```
Traditional approach: store current state
  orders table: {order_id: "abc", status: "CONFIRMED", total: 6802.06, ...}
  (We only know the CURRENT state. History is lost.)

Event Sourcing approach: store every event
  order_events table:
  +--------+------------------+---------+------------------------------------------+
  | seq_no | event_type       | order_id| data                                     |
  +--------+------------------+---------+------------------------------------------+
  | 1      | OrderCreated     | abc     | {items: [...], user: "789", total: 7048} |
  | 2      | PromoApplied     | abc     | {code: "SAVE10", discount: -699.80}      |
  | 3      | TaxCalculated    | abc     | {tax: 503.86, total: 6802.06}            |
  | 4      | StockReserved    | abc     | {reservations: [...]}                    |
  | 5      | PaymentCaptured  | abc     | {payment_id: "pay_xyz", amount: 6802.06} |
  | 6      | OrderConfirmed   | abc     | {status: "CONFIRMED"}                    |
  | 7      | ShipmentCreated  | abc     | {tracking: "1Z999AA..."}                 |
  +--------+------------------+---------+------------------------------------------+

  To get current state: replay events 1-7 in order.
  To get state at any point: replay events up to that point.
  To audit: full history of every change.

Benefits:
  - Complete audit trail (critical for financial systems)
  - Temporal queries ("what was the order state at 2:30 PM?")
  - Replay events to rebuild read models
  - Debug production issues by replaying exact sequence

Tradeoffs:
  - More storage (events accumulate)
  - Complexity in rebuilding state (snapshotting helps)
  - Eventual consistency between event store and read model
```

### Why Separate Read/Write Models for E-Commerce

```
+-----------------------------------------------------------------------+
| Without CQRS:                                                         |
|                                                                       |
| Single PostgreSQL database handles:                                   |
|   - Product search (full-text, faceted) --> SLOW (PostgreSQL is not ES)|
|   - Product writes (admin updates)       --> BLOCKED by search load   |
|   - Order reads (user history)           --> COMPETING with order writes|
|   - Order writes (checkout)              --> SLOWED by read indexes   |
|                                                                       |
| Result: Everything is mediocre. Reads are slow, writes are slow.     |
+-----------------------------------------------------------------------+

+-----------------------------------------------------------------------+
| With CQRS:                                                            |
|                                                                       |
| Write Side (PostgreSQL):               | Read Side:                   |
|   - Product writes: fast (few indexes) | - Search: Elasticsearch      |
|   - Order writes: fast (ACID)          |   (full-text, faceted, fast) |
|   - Inventory writes: fast (locks)     | - Product detail: Redis cache|
|                                        | - Order history: read replica|
|                                        | - Analytics: data warehouse  |
|                                                                       |
| Result: Each side optimized for its workload. Reads are fast.        |
| Writes are fast. They scale independently.                            |
+-----------------------------------------------------------------------+
```

---

## 12. Inventory Management

### The Overselling Problem

```
The Problem: Two users buy the last item at the same time.

Without proper locking:

  Time    User A                    Inventory DB              User B
  ----    ------                    ------------              ------
  T1      Read stock: 1 unit                                  Read stock: 1 unit
  T2      Check: 1 >= 1? YES                                  Check: 1 >= 1? YES
  T3      Decrement: 1 - 1 = 0                                Decrement: 1 - 1 = 0
  T4      Order confirmed                                     Order confirmed

  Result: BOTH orders confirmed, but only 1 unit exists. OVERSOLD!
```

### Solution 1: Pessimistic Locking (SELECT FOR UPDATE)

```sql
-- Pessimistic locking: acquire exclusive row lock BEFORE reading

BEGIN;

-- This locks the row. Other transactions trying to read this row
-- with FOR UPDATE will BLOCK until this transaction commits/rollbacks.
SELECT available_stock
FROM inventory
WHERE product_id = 'prod_abc123'
  AND warehouse_id = 'wh_west'
FOR UPDATE;

-- Check stock
-- If available_stock >= requested_quantity:
UPDATE inventory
SET available_stock = available_stock - 2,
    reserved_stock = reserved_stock + 2,
    version = version + 1
WHERE product_id = 'prod_abc123'
  AND warehouse_id = 'wh_west';

COMMIT;  -- Lock released here
```

```
Timeline with Pessimistic Locking:

  Time    User A                    Inventory DB              User B
  ----    ------                    ------------              ------
  T1      SELECT FOR UPDATE         (A acquires lock)
          stock = 1                                           SELECT FOR UPDATE
  T2      Check: 1 >= 1? YES                                  (B BLOCKED, waiting)
  T3      UPDATE: stock = 0                                    (B still waiting...)
  T4      COMMIT (lock released)                               (B lock acquired)
  T5                                                           stock = 0
  T6                                                           Check: 0 >= 1? NO
  T7                                                           ROLLBACK
                                                               "Out of stock"

  Result: Only User A gets the item. User B gets a clear error. No overselling.
```

**Pros:** Simple, correct, guaranteed no overselling.
**Cons:** Blocks concurrent transactions on the same product. Under heavy contention (flash sales), creates a bottleneck.

### Solution 2: Optimistic Locking (Version Column)

```sql
-- Optimistic locking: no lock acquired. Use version column to detect conflicts.

-- Step 1: Read current state (no lock)
SELECT available_stock, version
FROM inventory
WHERE product_id = 'prod_abc123'
  AND warehouse_id = 'wh_west';
-- Returns: available_stock = 1, version = 42

-- Step 2: Attempt update with version check
UPDATE inventory
SET available_stock = available_stock - 1,
    reserved_stock = reserved_stock + 1,
    version = version + 1
WHERE product_id = 'prod_abc123'
  AND warehouse_id = 'wh_west'
  AND version = 42;  -- Only succeeds if no one else changed it

-- Step 3: Check rows affected
-- If rows_affected = 1: success (we won the race)
-- If rows_affected = 0: conflict (someone else updated first) --> RETRY
```

```
Timeline with Optimistic Locking:

  Time    User A                    Inventory DB              User B
  ----    ------                    ------------              ------
  T1      Read: stock=1, v=42                                 Read: stock=1, v=42
  T2      UPDATE WHERE v=42         (A succeeds, v=43)
  T3      rows_affected=1 (WIN)                               UPDATE WHERE v=42
  T4                                                          rows_affected=0 (LOST)
  T5                                                          RETRY: Read stock=0, v=43
  T6                                                          Check: 0 >= 1? NO
  T7                                                          "Out of stock"

  Result: Same correctness, but no blocking. Losers retry.
```

**Pros:** No blocking, higher throughput under moderate contention.
**Cons:** Under heavy contention (many retries), can be slower than pessimistic. Retry storm during flash sales.

### Solution 3: Reservation Pattern (Reserve -> Confirm/Release)

```
The reservation pattern separates "I want this" from "I paid for this":

(1) RESERVE: Temporarily hold stock during checkout (15-minute TTL)
    - Decrements available_stock, increments reserved_stock
    - Creates a reservation record with expiry timestamp

(2) CONFIRM: Payment succeeded, convert reservation to committed
    - Decrements reserved_stock, increments committed_stock
    - Reservation status: RESERVED -> CONFIRMED

(3) RELEASE: Payment failed or timeout expired
    - Increments available_stock, decrements reserved_stock
    - Reservation status: RESERVED -> RELEASED or EXPIRED

+-------------------------------------------------------------------+
| Stock States:                                                     |
|                                                                   |
| total_stock = 100 (physical units in warehouse)                   |
| reserved_stock = 15 (held for in-progress checkouts)              |
| available_stock = 85 (total - reserved, available for new orders) |
|                                                                   |
| Invariant: available_stock = total_stock - reserved_stock         |
| Constraint: available_stock >= 0  (enforced at DB level)          |
+-------------------------------------------------------------------+
```

```
Reservation Lifecycle:

  User starts checkout
        |
   +----v----+      +----------+
   | RESERVED| ---->| CONFIRMED| (payment succeeded)
   +----+----+      +----------+
        |
        |  (payment failed or 15 min expired)
        |
   +----v----+
   | RELEASED|
   +---------+

  Background Job (every 60 seconds):
    SELECT * FROM inventory_reservations
    WHERE status = 'RESERVED' AND expires_at < NOW();
    --> Release each expired reservation
```

### Overselling Prevention: Database-Level CHECK Constraint

```sql
-- The ultimate safety net: a CHECK constraint at the database level
-- Even if application logic has a bug, the DB prevents negative stock.

ALTER TABLE inventory
ADD CONSTRAINT chk_available_stock_non_negative
CHECK (available_stock >= 0);

-- If any UPDATE tries to make available_stock < 0, PostgreSQL
-- raises an error and the transaction fails. This is the last line
-- of defense against overselling.
```

### Flash Sale / Limited Stock Handling

```
Flash Sale: 100 units of iPhone, 100,000 users try to buy simultaneously.

Problem: 100K concurrent requests hitting one row (product_id = 'iphone_16')
causes extreme lock contention with pessimistic locking, or extreme retry
storms with optimistic locking.

Solution: Token Bucket / Queue-Based Approach

(1) Pre-generate 100 "purchase tokens" in Redis:
    LPUSH flash:iphone_16 token_001 token_002 ... token_100

(2) User clicks "Buy Now":
    RPOP flash:iphone_16
    - If a token is returned: user gets a checkout slot
    - If nil (empty list): "SOLD OUT" immediately (no DB hit)

(3) User with token has 5 minutes to complete checkout
    - Normal checkout flow (reserve, pay, confirm)
    - If they don't complete, token is returned: LPUSH flash:iphone_16 token_xxx

(4) Benefits:
    - Only 100 users ever hit the database (not 100K)
    - Redis RPOP is atomic and O(1) -- handles millions of requests
    - "SOLD OUT" response is instant for 99.9% of users
    - No inventory contention in PostgreSQL

Alternative: Leaky Bucket Queue
    - All 100K requests go into a Kafka queue
    - Consumer processes them in order, first 100 succeed
    - Rest get "SOLD OUT" response
    - Benefit: fairness (first-come-first-served)
    - Drawback: users wait in queue (latency)
```

---

## 13. Concurrency

### Concurrent Checkout for the Same Cart

```
Problem: User clicks "Place Order" twice (double-click, network retry).

  Request A: POST /orders/checkout (idempotency_key: "idem_abc")
  Request B: POST /orders/checkout (idempotency_key: "idem_abc")  (retry)

Solution: Idempotency Key

  (1) Request A arrives first:
      - Check: no order with idempotency_key = "idem_abc" --> proceed
      - Create order, reserve stock, charge payment
      - Save order with idempotency_key = "idem_abc"
      - Return 201 Created

  (2) Request B arrives (retry):
      - Check: order with idempotency_key = "idem_abc" EXISTS
      - Return the existing order (same response as Request A)
      - No duplicate processing

  Implementation:
    UNIQUE constraint on orders.idempotency_key
    First INSERT succeeds, second INSERT fails with UNIQUE violation
    On UNIQUE violation: SELECT existing order and return it
```

### Concurrent Checkouts: Two Users, Last Item

```
Problem: User A and User B both have the same item in their carts.
Only 1 unit is available. Both click "Place Order" at the same time.

  Timeline:
  T1: User A --> Order Service: checkout (product X, qty 1)
  T1: User B --> Order Service: checkout (product X, qty 1)
  T2: Both reach Inventory Service at the same time

  With SELECT FOR UPDATE:
  T2: User A: SELECT FOR UPDATE --> acquires row lock, available_stock = 1
  T2: User B: SELECT FOR UPDATE --> BLOCKED (waiting for A's lock)
  T3: User A: UPDATE available_stock = 0, reserved = 1 --> COMMIT
  T4: User B: lock acquired, reads available_stock = 0 --> INSUFFICIENT_STOCK

  Result: User A gets the item. User B gets a clear error.
  The Saga compensates: User B's order is cancelled (no payment charged).
```

### Concurrent Inventory Updates (Restocking + Checkout)

```
Problem: Warehouse restocks +100 units while a checkout is reserving -1 unit.

  With row-level locking:
  T1: Restock: SELECT FOR UPDATE, stock = 50, reserved = 5
  T1: Checkout: SELECT FOR UPDATE --> BLOCKED (waiting for restock lock)
  T2: Restock: UPDATE total_stock = 150, available_stock = 145 --> COMMIT
  T3: Checkout: lock acquired, reads available_stock = 145
  T4: Checkout: UPDATE available_stock = 144, reserved = 6 --> COMMIT

  Result: Both operations succeed correctly. No data corruption.
  The serialization order is guaranteed by the database.
```

### Idempotent Payments (Retry Safety)

```
Problem: Network timeout during payment. Client retries.
Must NOT double-charge the customer.

  Request 1: POST /payments {order_id: "abc", idempotency_key: "pay_abc"}
  --> Stripe charges card, returns success
  --> Response lost due to network timeout (client never receives it)

  Request 2 (retry): POST /payments {order_id: "abc", idempotency_key: "pay_abc"}
  --> Payment Service checks DB: payment with idempotency_key exists, status = CAPTURED
  --> Return cached result WITHOUT calling Stripe again

  Defense in depth:
  Layer 1: Application-level idempotency (DB check before calling Stripe)
  Layer 2: Stripe's own idempotency (same idempotency_key = same result)
  Layer 3: Order-payment 1:1 constraint (UNIQUE on orders.payment_id)
```

### Handling Stale Cart Prices

```
Problem: User adds item to cart at $50. Price changes to $55 before checkout.

  Solution: Price Re-Validation at Checkout

  (1) Cart stores the price at the time of adding (for display only)
  (2) At checkout, Order Service fetches CURRENT prices from Product Service
  (3) If price changed:
      - Option A: Auto-update and inform user ("Price changed from $50 to $55")
      - Option B: Fail checkout, ask user to review updated cart
  (4) Order records the price AT THE TIME OF PURCHASE (snapshot)
      - This is the legally binding price
      - Future price changes do not affect existing orders
```

---

## 14. Scaling

### Product Catalog: CDN + Elasticsearch

```
Product Catalog Scaling:

+----------+
| Client   |
+----+-----+
     |
+----v---------+
| CDN          |  (1) Static assets: product images, JS, CSS
| (CloudFront) |      500M products * 5 images = 2.5B images cached at edge
+----+---------+      Cache-Control: public, max-age=86400
     |
+----v---------+
| API Gateway  |
+----+---------+
     |
+----v---------+
| Product      |  (2) Search: Elasticsearch cluster
| Catalog Svc  |      - 15 data nodes, 3 master nodes
| (12 instances|      - Sharded by category (electronics, clothing, books...)
|  behind LB)  |      - 3 replicas per shard (read scaling)
+----+---------+      - Index size: ~2.5 TB across all shards
     |
     +-----> Elasticsearch: handles 500K search QPS
     |       (each replica can serve reads independently)
     |
     +-----> Redis (product detail cache): 200K reads/sec
     |       Cache: product:{id} -> JSON, TTL 5 min
     |       Hit rate: ~85% (popular products cached)
     |
     +-----> PostgreSQL (product write master)
             Single master with 3 read replicas
             Writes: ~500 QPS (admin/seller product updates)
```

### Cart: Redis Cluster

```
Cart Scaling:

Redis Cluster:
  - 6 masters, 6 replicas (12 nodes total)
  - Hash slots distributed across masters
  - Key: cart:{userId} --> hashed to determine shard
  - Total memory: 200M active carts * 300 bytes avg = ~60 GB
  - Per-node: ~10 GB (fits in memory easily)
  - Throughput: 500M cart ops/day = ~6K ops/sec avg, ~60K peak
  - Single Redis node handles 100K+ ops/sec (plenty of headroom)

Failover:
  - Redis Sentinel monitors masters
  - Master failure: replica promoted in 5-15 seconds
  - During failover: cart reads from replica (slightly stale)
  - Cart writes queued in application, retried after failover
```

### Orders: PostgreSQL with Sharding

```
Order DB Scaling:

  100M orders/day * 2 KB = 200 GB/day
  Monthly: ~6 TB
  Yearly: ~73 TB

Strategy: Shard by user_id (consistent hashing)

  Shard 0: users whose hash(userId) % 16 = 0
  Shard 1: users whose hash(userId) % 16 = 1
  ...
  Shard 15: users whose hash(userId) % 16 = 15

  Per-shard: ~6.25M orders/day, ~12.5 GB/day
  Each shard: 1 master + 2 read replicas

  +----+  +----+  +----+       +------+
  | S0 |  | S1 |  | S2 | ...  | S15  |
  +----+  +----+  +----+       +------+
    |M      |M      |M            |M
   / \     / \     / \           / \
  R1  R2  R1  R2  R1  R2       R1  R2

Why shard by user_id:
  (1) "My orders" query hits ONE shard (user_id is the query key)
  (2) No cross-shard joins needed for user-facing queries
  (3) Orders and order_items co-located (same shard via order -> user)
  (4) Even distribution: users spread evenly across shards

Time-based partitioning WITHIN each shard:
  - Monthly partitions on created_at
  - Old partitions (>1 year) moved to cold storage
  - Queries on recent orders are fast (hit only recent partition)
```

### Inventory: Single Master with Hot Standby

```
Inventory DB Scaling:

  500M products * 50 bytes = ~25 GB (fits on one beefy server)
  Inventory writes: ~10K QPS (reservations + confirmations)

Strategy: Single master (strong consistency required)
  - Master handles all writes (reserve, confirm, release)
  - 2 synchronous replicas (zero data loss)
  - Read replicas for "check availability" soft queries

  For flash sales (extreme contention on single product):
  - Use Redis token bucket in front (Section 12)
  - Only token holders hit the database
  - Reduces DB load from 100K QPS to 100 QPS for that product

  If single master becomes a bottleneck:
  - Shard by product_id range
  - Each shard handles a subset of products
  - Flash sale product might still be a hotspot --> Redis queue
```

### Auto-Scaling for Black Friday (10x Spike)

```
Normal Day:
  - Order Service: 8 instances
  - Product Catalog: 12 instances
  - Cart Service: 6 instances
  - Payment Service: 4 instances

Black Friday (10x):
  - Order Service: 80 instances (auto-scale on CPU/request count)
  - Product Catalog: 50 instances (search volume surges)
  - Cart Service: 30 instances (massive cart activity)
  - Payment Service: 40 instances (payment gateway becomes bottleneck)
  - Elasticsearch: add 30 more replicas (read scaling)
  - Redis: add 12 more nodes (cart volume)
  - Kafka: add partitions (event throughput)

Pre-scaling (done 1 hour before event):
  - Scale stateless services to 5x baseline
  - Warm caches with popular product data
  - Pre-provision payment gateway capacity (coordinate with Stripe)
  - Enable circuit breakers on non-critical services (recommendations, reviews)
  - Switch to degraded mode: disable personalized search, serve cached results

Dynamic scaling during event:
  - Kubernetes HPA (Horizontal Pod Autoscaler) on CPU > 70%
  - Custom metrics: order queue depth, payment latency
  - Scale-up time: ~2 minutes (container pull + health check)
```

---

## 15. Database Choice

```
+------------------+----------------+----------------------------------------------+
| Component        | Database       | Rationale                                    |
+------------------+----------------+----------------------------------------------+
| Product Catalog  | PostgreSQL     | Source of truth for product data.             |
| (write)          |                | ACID guarantees for admin/seller updates.    |
|                  |                | JSONB for flexible product attributes.       |
+------------------+----------------+----------------------------------------------+
| Product Search   | Elasticsearch  | Full-text search with relevance scoring.     |
| (read)           |                | Faceted filtering (brand, price range, etc). |
|                  |                | Sub-50ms query latency at 500K QPS.          |
|                  |                | Horizontal scaling via shards + replicas.    |
+------------------+----------------+----------------------------------------------+
| Shopping Cart    | Redis          | Sub-millisecond reads/writes.                |
| (read/write)     |                | Hash data structure maps cleanly to cart.    |
|                  |                | TTL for automatic cart expiry.               |
|                  |                | In-memory: handles 500M cart ops/day easily. |
|                  |                | No ACID needed (cart is not financial data). |
+------------------+----------------+----------------------------------------------+
| Orders           | PostgreSQL     | ACID transactions for order creation.        |
| (read/write)     | (sharded)      | Strong consistency for financial records.    |
|                  |                | Sharded by user_id for horizontal scaling.   |
|                  |                | Partitioned by created_at for query perf.    |
|                  |                | Read replicas for order history queries.     |
+------------------+----------------+----------------------------------------------+
| Inventory        | PostgreSQL     | ACID + row-level locking for stock updates.  |
| (read/write)     |                | CHECK constraints prevent negative stock.    |
|                  |                | SELECT FOR UPDATE for pessimistic locking.   |
|                  |                | Strong consistency required (no overselling).|
+------------------+----------------+----------------------------------------------+
| Payments         | PostgreSQL     | Financial records require ACID + durability. |
| (read/write)     |                | UNIQUE constraint on idempotency_key.        |
|                  |                | Audit trail with full transaction history.   |
+------------------+----------------+----------------------------------------------+
| Shipments        | PostgreSQL     | Structured data with status tracking.        |
| (read/write)     |                | Joins with orders table (same shard).        |
+------------------+----------------+----------------------------------------------+
| Event Bus        | Apache Kafka   | High-throughput event streaming (500K/sec).  |
| (events)         |                | Durable, replicated, ordered within partition|
|                  |                | Decouples services (pub/sub model).          |
|                  |                | Replay capability for event sourcing.        |
+------------------+----------------+----------------------------------------------+
| Product Cache    | Redis          | Cache hot product data (5-min TTL).          |
| (read cache)     |                | Reduce PostgreSQL read load by 85%.          |
+------------------+----------------+----------------------------------------------+
| Session Store    | Redis          | Guest session management, CSRF tokens.       |
+------------------+----------------+----------------------------------------------+
| Analytics        | ClickHouse /   | OLAP queries on order/revenue data.          |
| (read)           | BigQuery       | Columnar storage for aggregations.           |
+------------------+----------------+----------------------------------------------+
```

### Why Not a Single Database?

```
"Why not just use PostgreSQL for everything?"

  (1) Search: PostgreSQL full-text search (tsvector) is adequate for small
      datasets but cannot match Elasticsearch's relevance scoring, faceted
      search, or performance at 500M products and 500K QPS.

  (2) Cart: PostgreSQL can store carts, but every "add to cart" would be
      a disk write with ACID overhead. Redis handles 100K ops/sec per node
      with sub-millisecond latency. Cart data is ephemeral -- ACID is overkill.

  (3) Events: PostgreSQL can be used as a message queue (LISTEN/NOTIFY),
      but it cannot match Kafka's throughput (500K events/sec), durability,
      consumer group management, or replay capability.

  (4) Scale: A single PostgreSQL instance maxes out at ~50K QPS (reads).
      With 1B daily page views, we need distributed reads across multiple
      specialized systems.

The tradeoff: operational complexity (managing 5+ data stores) vs performance
and scalability. At Amazon's scale, the complexity is justified.
```

---

## 16. CAP Theorem

```
CAP Theorem: In a distributed system, you can only guarantee TWO of three:
  - Consistency (C): Every read returns the most recent write
  - Availability (A): Every request receives a response
  - Partition Tolerance (P): System works despite network partitions

Since network partitions ALWAYS happen in distributed systems,
the real choice is between CP and AP.
```

### CP: Inventory and Payments (Consistency over Availability)

```
Inventory Service: MUST be CP
  - Reason: Overselling is unacceptable. If we cannot guarantee that
    the stock check is current, we must reject the request rather than
    risk selling a product we don't have.
  - Implementation: PostgreSQL single master with synchronous replication.
    All writes go to master. If master is unavailable, writes FAIL
    (we sacrifice availability to preserve consistency).
  - During network partition:
    - Master partition: can process writes (available)
    - Replica partition: rejects writes (unavailable but consistent)

Payment Service: MUST be CP
  - Reason: Double-charging or lost payments are unacceptable.
  - Implementation: Idempotency keys, exactly-once semantics.
    If payment status is unknown (gateway timeout), do NOT retry
    blindly -- query the gateway first.
  - During network partition:
    - If cannot reach payment gateway: FAIL (do not guess)
    - If cannot reach payment DB: FAIL (cannot record payment)

Order Service: CP for writes, AP for reads
  - Writes (create order, update status): strong consistency
    via PostgreSQL master.
  - Reads (order history): can serve from read replica
    (slightly stale is OK for user viewing their past orders).
```

### AP: Product Catalog and Cart (Availability over Consistency)

```
Product Catalog (Search): AP
  - Reason: A slightly stale search result (price changed 2 seconds ago)
    is FAR better than returning an error page. Users abandon sites
    that show errors.
  - Implementation: Elasticsearch replicas can serve reads even if
    the master is down. CDC sync may lag, so search results can be
    2-5 seconds stale.
  - During network partition:
    - Elasticsearch replicas continue serving cached data
    - Writes to PostgreSQL (product updates) queue until partition heals
    - Users see slightly stale results but site remains functional

Shopping Cart: AP
  - Reason: Cart is not financial data. If a user adds an item and
    the Redis replica hasn't synced yet, worst case they see the item
    appear a moment later. Blocking the user with an error is worse.
  - Implementation: Redis cluster with async replication.
    During failover, cart reads may be slightly stale.
  - During network partition:
    - Cart reads continue from available replicas
    - Cart writes may be lost if master fails before replication
    - On failover: user might need to re-add last item (minor UX issue)
    - At checkout: cart is re-validated anyway (prices, stock)
```

### CAP Summary Table

```
+------------------+--------+-------------------------------------------------+
| Component        | Choice | Justification                                   |
+------------------+--------+-------------------------------------------------+
| Inventory        | CP     | Overselling is a business-critical failure.     |
|                  |        | Better to reject an order than oversell.        |
+------------------+--------+-------------------------------------------------+
| Payment          | CP     | Financial correctness is non-negotiable.        |
|                  |        | Double-charge or lost payment = customer trust. |
+------------------+--------+-------------------------------------------------+
| Order (writes)   | CP     | Order creation must be consistent.              |
|                  |        | Lost or duplicate orders are unacceptable.      |
+------------------+--------+-------------------------------------------------+
| Order (reads)    | AP     | Order history can be 1-2 seconds stale.         |
|                  |        | Better to show slightly stale than error.       |
+------------------+--------+-------------------------------------------------+
| Product Catalog  | AP     | Stale product listing is acceptable (2-5 sec).  |
|                  |        | Always show products, never show blank page.    |
+------------------+--------+-------------------------------------------------+
| Shopping Cart    | AP     | Cart is ephemeral, re-validated at checkout.    |
|                  |        | Availability trumps perfect consistency.        |
+------------------+--------+-------------------------------------------------+
| Notifications    | AP     | Delayed or duplicate notification is acceptable.|
|                  |        | Missing notification: user can check order page.|
+------------------+--------+-------------------------------------------------+
```

---

## 17. Cloud Services

```
+---------------------------+----------------------------+---------------------------+
| Component                 | AWS                        | GCP                       |
+---------------------------+----------------------------+---------------------------+
| API Gateway               | Amazon API Gateway         | Apigee / Cloud Endpoints  |
| Load Balancer             | ALB (Application LB)      | Cloud Load Balancing      |
| Compute (Services)        | EKS (Kubernetes) / ECS    | GKE (Kubernetes)          |
| Auto-Scaling              | EKS HPA + Cluster Autoscaler | GKE HPA + Node Auto-Provisioning |
| Product DB (Write)        | Amazon RDS PostgreSQL      | Cloud SQL (PostgreSQL)    |
| Product Search            | Amazon OpenSearch          | Elastic Cloud on GCP      |
| Cart / Cache              | Amazon ElastiCache (Redis) | Cloud Memorystore (Redis) |
| Order DB (Sharded)        | Amazon RDS / Aurora        | Cloud SQL / Cloud Spanner |
| Inventory DB              | Amazon RDS PostgreSQL      | Cloud SQL (PostgreSQL)    |
| Payment DB                | Amazon RDS PostgreSQL      | Cloud SQL (PostgreSQL)    |
| Event Bus                 | Amazon MSK (Kafka)         | Confluent on GCP / Pub/Sub|
| CDN (Images, Static)      | Amazon CloudFront          | Cloud CDN                 |
| Object Storage (Images)   | Amazon S3                  | Cloud Storage             |
| DNS                       | Amazon Route 53            | Cloud DNS                 |
| Monitoring                | CloudWatch + X-Ray         | Cloud Monitoring + Trace  |
| Logging                   | CloudWatch Logs            | Cloud Logging             |
| Secrets Management        | AWS Secrets Manager        | Secret Manager            |
| CI/CD                     | CodePipeline / GitHub Actions | Cloud Build             |
| Notifications (Email)     | Amazon SES                 | SendGrid (third-party)    |
| Notifications (Push)      | Amazon SNS / Pinpoint      | Firebase Cloud Messaging  |
| Notifications (SMS)       | Amazon SNS                 | Twilio (third-party)      |
| Change Data Capture       | AWS DMS / Debezium on EKS  | Debezium on GKE           |
| Analytics / Warehouse     | Amazon Redshift            | BigQuery                  |
+---------------------------+----------------------------+---------------------------+
```

---

## 18. Tradeoffs Summary

```
+-------------------------------+-------------------------------+-------------------------------+
| Decision                      | Alternative                   | Why We Chose This             |
+-------------------------------+-------------------------------+-------------------------------+
| Microservices over Monolith   | Monolith (simpler to start)   | At 100M orders/day, each     |
|                               |                               | service needs independent     |
|                               |                               | scaling. Cart scales          |
|                               |                               | differently than Payment.     |
+-------------------------------+-------------------------------+-------------------------------+
| Saga over 2PC                 | Two-Phase Commit              | 2PC blocks, has single point |
|                               |                               | of failure, and doesn't work |
|                               |                               | with heterogeneous DBs       |
|                               |                               | (Redis, ES, Stripe).         |
+-------------------------------+-------------------------------+-------------------------------+
| Orchestration over            | Choreography (event-driven,   | Checkout has 5+ steps with   |
| Choreography for checkout     | decentralized)                | complex compensation.        |
|                               |                               | Orchestrator gives single    |
|                               |                               | place to debug and monitor.  |
+-------------------------------+-------------------------------+-------------------------------+
| Pessimistic locking for       | Optimistic locking (version   | Flash sales create extreme   |
| inventory (default)           | column, retry on conflict)    | contention. Pessimistic lock |
|                               |                               | serializes correctly. Redis  |
|                               |                               | token bucket in front for    |
|                               |                               | flash sales.                 |
+-------------------------------+-------------------------------+-------------------------------+
| Redis for cart over           | PostgreSQL (ACID cart)        | Cart needs sub-ms latency    |
| PostgreSQL                    |                               | for 500M ops/day. ACID is    |
|                               |                               | overkill -- cart is re-      |
|                               |                               | validated at checkout.        |
+-------------------------------+-------------------------------+-------------------------------+
| Elasticsearch for search      | PostgreSQL full-text search   | 500M products with faceted   |
| over PostgreSQL               | (tsvector/tsquery)            | search, relevance scoring,   |
|                               |                               | and 500K QPS. ES is purpose- |
|                               |                               | built for this workload.     |
+-------------------------------+-------------------------------+-------------------------------+
| CQRS over shared DB           | Single DB for reads + writes  | Read:write ratio is 1000:1   |
|                               |                               | for catalog. Separate models |
|                               |                               | allow each to be optimized   |
|                               |                               | independently.               |
+-------------------------------+-------------------------------+-------------------------------+
| Kafka over RabbitMQ           | RabbitMQ (simpler, lower      | Need 500K events/sec with    |
|                               | latency for small scale)      | durability, ordering, and    |
|                               |                               | replay capability for event  |
|                               |                               | sourcing.                    |
+-------------------------------+-------------------------------+-------------------------------+
| Shard orders by user_id       | Shard by order_id             | "My orders" is the primary   |
| over order_id                 |                               | query pattern. Sharding by   |
|                               |                               | user_id keeps all of a       |
|                               |                               | user's orders on one shard.  |
+-------------------------------+-------------------------------+-------------------------------+
| 15-min reservation TTL        | No reservation (decrement     | Without reservation, payment |
|                               | stock immediately)            | failure leaves stock          |
|                               |                               | decremented. With TTL,       |
|                               |                               | abandoned checkouts auto-    |
|                               |                               | release stock.               |
+-------------------------------+-------------------------------+-------------------------------+
| CP for inventory/payment,     | AP everywhere (eventual       | Overselling and double-      |
| AP for catalog/cart           | consistency for all)          | charging are catastrophic.   |
|                               |                               | Catalog/cart staleness is    |
|                               |                               | harmless.                    |
+-------------------------------+-------------------------------+-------------------------------+
| Denormalized order snapshots  | Store only product_id in      | Prices and product details   |
| (price, title in order_items) | order_items, JOIN to products | change over time. The order  |
|                               |                               | must record what the user    |
|                               |                               | actually paid. JOIN would    |
|                               |                               | return CURRENT price, not    |
|                               |                               | the price at purchase time.  |
+-------------------------------+-------------------------------+-------------------------------+
```

---

## 19. Interview Talking Points

### Opening Statement (30 seconds)

> "I'd design this as a microservices architecture with 7 core services: Product Catalog, Cart, Order, Inventory, Payment, Shipping, and Notification. The key challenge is the checkout flow, which is a distributed transaction spanning Inventory and Payment. I'd use the Saga pattern with orchestration -- the Order Service acts as the orchestrator, coordinating reserve-stock, charge-payment, confirm-stock, and create-shipment steps, with compensation actions for each failure scenario."

### Key Discussion Points

**1. "How do you prevent overselling?"**
> "Three layers of defense: (1) Pessimistic locking at the database level -- SELECT FOR UPDATE serializes concurrent access to the same product's inventory row. (2) A CHECK constraint on available_stock >= 0 as the ultimate safety net. (3) The reservation pattern -- stock is reserved during checkout with a 15-minute TTL. If checkout fails or times out, the reservation expires and stock is automatically released."

**2. "Why Saga over 2PC?"**
> "2PC has three fatal flaws at e-commerce scale: it's blocking (all participants hold locks during prepare phase), the coordinator is a single point of failure, and it doesn't work with heterogeneous data stores -- our checkout touches Redis (cart), PostgreSQL (orders, inventory), and Stripe (payment gateway). Saga replaces the atomic transaction with a sequence of local transactions, each with a compensating action. If payment fails after inventory is reserved, we execute the compensation: release the reserved stock."

**3. "Why CQRS?"**
> "Our catalog has a 1000:1 read-to-write ratio. Product search needs full-text search with faceted filtering at 500K QPS -- that's Elasticsearch's sweet spot, not PostgreSQL's. But product writes need ACID guarantees. CQRS lets us optimize each side independently: PostgreSQL for writes, Elasticsearch for reads, connected via CDC through Kafka. The tradeoff is 2-5 seconds of eventual consistency on the read side, which is acceptable for product search."

**4. "How do you handle Black Friday?"**
> "Pre-scale stateless services to 5x baseline one hour before the event. Use Redis token buckets for flash sale items -- only token holders hit the database, reducing contention from 100K to 100 QPS. Enable circuit breakers on non-critical services (recommendations, reviews). CDN absorbs product page traffic. Cart is in Redis, which handles spikes natively. Auto-scale on Kubernetes HPA for sustained load. The bottleneck shifts to the payment gateway -- we pre-coordinate with Stripe for increased rate limits."

**5. "Walk me through a payment failure during checkout."**
> "The Order Service, acting as Saga orchestrator, has already reserved inventory (step 1) when payment fails (step 2). The compensation kicks in: (a) release inventory reservation -- Inventory Service increments available_stock and marks the reservation as RELEASED; (b) update order status to CANCELLED; (c) publish an OrderCancelledEvent to Kafka; (d) Notification Service sends a 'payment failed' message to the user. Each compensation is idempotent -- if the release message is retried, checking that the reservation is already RELEASED prevents double-release."

**6. "How do you handle idempotent payments?"**
> "Three layers: (1) Application-level: the checkout request includes an Idempotency-Key header. Before calling Stripe, we check our payments table for that key. If found, return the cached result. (2) Gateway-level: Stripe accepts an idempotency_key parameter -- even if our check fails, Stripe won't process the same key twice. (3) Database-level: a UNIQUE constraint on idempotency_key in the payments table prevents duplicate records even under race conditions."

**7. "Why shard orders by user_id instead of order_id?"**
> "The primary query pattern is 'show me my orders' -- which hits one shard when sharded by user_id. If we shard by order_id, 'my orders' becomes a scatter-gather across ALL shards, which is slow and expensive. The tradeoff: admin queries like 'find all orders for product X' now require a scatter-gather, but that's an internal tool with lower latency requirements. User-facing performance takes priority."

### Complexity Laddering (from Simple to Advanced)

```
Level 1 (Junior): "It's a monolith with a single database."
  --> Missing: scaling, distributed transactions, service boundaries

Level 2 (Mid): "Microservices with REST calls between them."
  --> Missing: Saga pattern, inventory locking, CQRS

Level 3 (Senior): "Microservices with Saga orchestration, CQRS,
  pessimistic locking for inventory, Redis cart, Elasticsearch search,
  Kafka for event-driven communication."
  --> This is the target level.

Level 4 (Staff+): "Add event sourcing for orders, sharding strategy
  for orders DB, flash sale token bucket pattern, Black Friday
  pre-scaling playbook, and explain exactly how each service
  degrades under partition."
  --> Bonus points in interview.
```

### Red Flags to Avoid in Interview

```
(1) DON'T say "use a distributed transaction" without explaining why 2PC fails.
(2) DON'T forget inventory locking when two users buy the last item.
(3) DON'T store cart in PostgreSQL without justifying the latency cost.
(4) DON'T use a single database for product search at 500M products.
(5) DON'T skip compensation/rollback when discussing the checkout flow.
(6) DON'T ignore idempotency for payments -- this is the #1 follow-up question.
(7) DON'T propose AP for inventory or payments -- overselling and double-charging
    are unacceptable.
(8) DON'T forget to mention the reservation TTL -- without it, abandoned
    checkouts permanently lock up inventory.
```

### Time Management (45-minute interview)

```
Minutes 0-5:   Clarify requirements, state assumptions (scale, QPS)
Minutes 5-10:  Draw high-level architecture (7 services, data stores)
Minutes 10-20: Walk through checkout flow (happy path + failure)
Minutes 20-30: Deep dive (interviewer picks: Saga, inventory, CQRS)
Minutes 30-40: Scaling, database choices, CAP tradeoffs
Minutes 40-45: Edge cases, monitoring, anything the interviewer wants

Tip: Let the interviewer steer. If they want to go deep on Saga,
spend 15 minutes there and briefly touch scaling. If they want
inventory, go deep on locking and flash sales. Read their cues.
```
