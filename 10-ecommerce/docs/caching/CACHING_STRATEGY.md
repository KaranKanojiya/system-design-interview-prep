# Caching Strategies for the E-Commerce System (Amazon)

> Interview-ready reference for a Senior Java developer.
> E-commerce caching is a SPLIT strategy -- cache aggressively for reads (catalog, search), but tread carefully for writes (inventory, payments).
> The hardest part: keeping cache consistent during checkout when real money and real stock are at stake.

---

## Table of Contents

| Section | Key Point |
|---------|-----------|
| What to Cache vs Not | Catalog and search = cache aggressively. Inventory writes = never cache. |
| Product Catalog Caching | CDN + Redis, 80% of traffic never hits backend |
| Shopping Cart Caching | Redis with write-through to DynamoDB |
| Inventory Caching | Read cache OK, write path MUST go to DB |
| Price Caching | Redis with event-driven invalidation |
| Search Result Caching | Elasticsearch + Redis for popular queries |
| Flash Sale Handling | Pre-warm, atomic decrement, queue-based |
| Cache Consistency at Checkout | Read-through to DB for inventory and prices |
| Multi-Level Caching | Browser -> CDN -> Redis -> Service -> DB |
| Cache Sizing & Eviction | LRU with TTL per data type |
| Interview Q&A | Ready-to-use answers |

---

## What to Cache vs What NOT to Cache

### The Caching Decision Matrix

```
  +--------------------------------------------------------------------+
  |                      CACHE DECISION MATRIX                         |
  +--------------------------------------------------------------------+
  |                                                                    |
  |  CACHE AGGRESSIVELY                 CACHE CAREFULLY                |
  |  (high hit rate, stale OK)          (stale = money lost)           |
  |  ============================       =============================  |
  |                                                                    |
  |  +---------------------+            +---------------------+        |
  |  | Product Catalog     |            | Inventory Stock     |        |
  |  | CDN + Redis         |            | READ: Redis cache   |        |
  |  | TTL: 30-60 seconds  |            | WRITE: DB only!     |        |
  |  | Hit rate: 95%+      |            | Stale stock = over- |        |
  |  +---------------------+            | selling             |        |
  |                                     +---------------------+        |
  |  +---------------------+                                           |
  |  | Search Results      |            +---------------------+        |
  |  | Elasticsearch +     |            | Payment State       |        |
  |  | Redis for popular   |            | NEVER cache         |        |
  |  | TTL: 5 minutes      |            | Always read from DB |        |
  |  +---------------------+            | Stale = double-     |        |
  |                                     | charge              |        |
  |  +---------------------+            +---------------------+        |
  |  | Shopping Cart       |                                           |
  |  | Redis (primary)     |            +---------------------+        |
  |  | Write-through to DB |            | Order State         |        |
  |  | TTL: 7 days         |            | Cache only for      |        |
  |  +---------------------+            | display; saga reads |        |
  |                                     | from DB             |        |
  |  +---------------------+            +---------------------+        |
  |  | Price Display       |                                           |
  |  | Redis + CDN         |            DO NOT CACHE:                  |
  |  | TTL: 5 minutes      |            - Idempotency keys             |
  |  | Event-invalidated   |            - Saga compensation state      |
  |  +---------------------+            - Payment transaction IDs      |
  |                                     - Inventory reserve/release    |
  +--------------------------------------------------------------------+
```

### The Golden Rule

```
  +--------------------------------------------------------------------+
  |                                                                    |
  |  CACHE for DISPLAY.  Read from DB for DECISIONS.                   |
  |                                                                    |
  |  Product page shows price $999 (from cache)?  FINE.               |
  |  Checkout charges $999 (from cache)?           DANGEROUS.          |
  |                                                                    |
  |  Search shows "In Stock" (from cache)?         FINE.               |
  |  Reserve inventory based on cache?             CATASTROPHIC.       |
  |                                                                    |
  |  DISPLAY LAYER: Read from cache (fast, stale OK)                   |
  |  TRANSACTION LAYER: Read from DB (slower, must be correct)         |
  |                                                                    |
  +--------------------------------------------------------------------+
```

---

## 1. Product Catalog Caching: CDN + Redis

### Architecture

```
  +--------+     +--------+     +---------+     +----------+     +----------+
  | Client |---->| CDN    |---->| Redis   |---->| Product  |---->| Postgres |
  | (user) |     | (edge) |     | (L2     |     | Service  |     | (source  |
  |        |     |        |     |  cache) |     |          |     |  of truth|
  +--------+     +--------+     +---------+     +----------+     +----------+

  TRAFFIC DISTRIBUTION:
  100% requests
    |
    +-- 80% served by CDN (edge cache)        <-- never reaches backend
    |
    +-- 15% served by Redis (L2 cache)        <-- hits Redis, not DB
    |
    +-- 5% reaches PostgreSQL (cache miss)    <-- actual DB queries

  RESULT: 95% cache hit rate.
  10,000 requests/sec at CDN -> 500 requests/sec at DB.
  DB needs to handle 20x LESS traffic.
```

### CDN Caching Strategy

```
  CDN LAYER (CloudFront / Cloudflare):

  WHAT TO CACHE AT CDN:
  +--------------------------------------------------------------------+
  | Content               | TTL     | Cache Key                        |
  +-----------------------+---------+----------------------------------+
  | Product detail page   | 30 sec  | /products/{productId}            |
  | Category listing      | 60 sec  | /categories/{categoryId}?page=N  |
  | Product images        | 24 hrs  | /images/{imageId}.jpg            |
  | Static assets (JS/CSS)| 30 days | /assets/{hash}.js                |
  | Search results page   | 0 (no)  | N/A (too personalized)           |
  | Cart page             | 0 (no)  | N/A (user-specific)              |
  +-----------------------+---------+----------------------------------+

  CACHE HEADERS:
  Product detail: Cache-Control: public, max-age=30, s-maxage=30
  Product image:  Cache-Control: public, max-age=86400, immutable
  Cart page:      Cache-Control: private, no-store

  INVALIDATION:
  (1) Price change event from Kafka
  (2) Product Service calls CDN invalidation API
  (3) CDN purges cached product page
  (4) Next request fetches fresh data from origin
  (5) Worst case: 30 seconds of stale data (TTL)
```

### Redis L2 Cache

```
  REDIS CACHE FOR PRODUCT CATALOG:

  KEY: product:{productId}
  VALUE: JSON string of product details
  TTL: 300 seconds (5 minutes)

  EXAMPLE:
  SET product:PROD-1 '{"id":"PROD-1","name":"iPhone 15","price":999.00,
      "category":"Electronics","rating":4.5,"inStock":true,
      "imageUrl":"/images/iphone15.jpg","description":"..."}' EX 300

  CACHE-ASIDE PATTERN:
  (1) GET product:PROD-1
  (2) If HIT -> return cached product (deserialized from JSON)
  (3) If MISS -> query PostgreSQL -> SET in Redis with TTL -> return
```

### Numbered Call Chain -- Product Page Load

```
  Client          CDN             Redis           ProductService    PostgreSQL
    |               |               |                  |                |
    | (1) GET       |               |                  |                |
    | /products/    |               |                  |                |
    |  PROD-1       |               |                  |                |
    |-------------->|               |                  |                |
    |               |               |                  |                |
    |               | (2) check     |                  |                |
    |               |  CDN cache    |                  |                |
    |               |  MISS         |                  |                |
    |               |               |                  |                |
    |               | (3) forward   |                  |                |
    |               |  to origin    |                  |                |
    |               |-------------->|                  |                |
    |               |               |                  |                |
    |               | (redundant    | (4) GET          |                |
    |               |  for CDN)     | product:PROD-1   |                |
    |               |               | HIT!             |                |
    |               |               |                  |                |
    |               | (5) return    |                  |                |
    |               |  from Redis   |                  |                |
    |               |<--------------|                  |                |
    |               |               |                  |                |
    |               | (6) cache     |                  |                |
    |               |  at CDN       |                  |                |
    |               |  (30s TTL)    |                  |                |
    |               |               |                  |                |
    |  Product JSON |               |                  |                |
    |  (from Redis  |               |                  |                |
    |   via CDN)    |               |                  |                |
    |<--------------|               |                  |                |
    |               |               |                  |                |

  NEXT REQUEST (within 30 seconds):
  CDN HIT -> response in <10ms (edge location) -- never reaches backend
```

---

## 2. Shopping Cart Caching: Redis with Write-Through

### Architecture

```
  +--------+     +---------+     +----------+
  | Client |---->| Redis   |---->| DynamoDB |
  | (user) |     | (primary|     | (durable |
  |        |     |  store) |     |  backup) |
  +--------+     +---------+     +----------+

  WRITE-THROUGH PATTERN:
  (1) Client adds item to cart
  (2) Write to Redis (fast, <1ms)
  (3) Write to DynamoDB (durable, <10ms)
  (4) Return success to client (after Redis write, don't wait for DynamoDB)

  Actually: write-behind (async to DynamoDB) for speed
  But: write-through for correctness guarantee
```

### Redis Cart Data Model

```
  KEY: cart:{userId}
  TYPE: Hash (one field per cart item)
  TTL: 604800 (7 days -- abandoned cart cleanup)

  OPERATIONS:

  ADD ITEM:
  HSET cart:USR-456 item:PROD-1 '{"productId":"PROD-1","name":"iPhone 15",
      "quantity":1,"unitPrice":999.00,"addedAt":"2024-01-15T10:30:00Z"}'

  UPDATE QUANTITY:
  HSET cart:USR-456 item:PROD-1 '{"productId":"PROD-1","name":"iPhone 15",
      "quantity":2,"unitPrice":999.00,"addedAt":"2024-01-15T10:30:00Z"}'

  REMOVE ITEM:
  HDEL cart:USR-456 item:PROD-1

  GET FULL CART:
  HGETALL cart:USR-456

  CLEAR CART (post-checkout):
  DEL cart:USR-456

  WHY HASH (not String with JSON):
  - Single item update = HSET on one field (no read-modify-write)
  - No need to deserialize entire cart to add/remove one item
  - Atomic per-field operations
```

### Cart Consistency During Checkout

```
  CRITICAL MOMENT: User clicks "Place Order"

  +--------------------------------------------------------------------+
  |  STEP 1: Read cart from Redis (fast)                               |
  |          This is the LAST time we read from cache.                 |
  |                                                                    |
  |  STEP 2: For EACH item in cart:                                    |
  |    (a) Re-validate product exists (DB, not cache)                  |
  |    (b) Re-validate price (DB, not cache)                           |
  |    (c) Re-validate stock (DB, not cache)                           |
  |                                                                    |
  |  WHY RE-VALIDATE?                                                  |
  |  - Product might be discontinued since added to cart (3 days ago)  |
  |  - Price might have changed (flash sale ended)                     |
  |  - Stock might be gone (10,000 users competing for 5 items)        |
  |                                                                    |
  |  STEP 3: If any validation fails:                                  |
  |    -> Update cart in Redis (remove unavailable items)              |
  |    -> Notify user: "Some items in your cart have changed"          |
  |    -> Show updated cart for confirmation                           |
  |                                                                    |
  |  STEP 4: If all validations pass:                                  |
  |    -> Execute saga (reserve inventory + charge payment)            |
  |    -> Use DB-validated prices (not cached prices)                  |
  +--------------------------------------------------------------------+
```

### Numbered Call Chain -- Checkout Cart Validation

```
  OrderService     CartService    Redis     ProductService    PostgreSQL    InventoryService
      |                |           |             |                |              |
      | (1) getCart    |           |             |                |              |
      |  ("USR-456")   |           |             |                |              |
      |--------------->|           |             |                |              |
      |                | (2) HGETALL             |                |              |
      |                |  cart:USR-456           |                |              |
      |                |---------->|             |                |              |
      |                |  3 items  |             |                |              |
      |                |<----------|             |                |              |
      |  Cart(3 items) |           |             |                |              |
      |<---------------|           |             |                |              |
      |                |           |             |                |              |
      | (3) validate   |           |             |                |              |
      |  each item     |           |             |                |              |
      |                |           |             |                |              |
      | (4) getProduct |           |             |                |              |
      |  (PROD-1)      |           |             |                |              |
      |  FROM DB!      |           |             |                |              |
      |----------------------------------------->|                |              |
      |                |           |             | (5) SELECT     |              |
      |                |           |             |  FROM products |              |
      |                |           |             |  WHERE id=     |              |
      |                |           |             |  'PROD-1'      |              |
      |                |           |             |--------------->|              |
      |                |           |             |  Product       |              |
      |                |           |             |<---------------|              |
      |  Product       |           |             |                |              |
      |  (real price   |           |             |                |              |
      |   from DB)     |           |             |                |              |
      |<-----------------------------------------|                |              |
      |                |           |             |                |              |
      | (6) check real |           |             |                |              |
      |  time stock    |           |             |                |              |
      |--------------------------------------------------------------------->|
      |                |           |             |                | (7) check   |
      |                |           |             |                |  DynamoDB   |
      |  stock = 3     |           |             |                |  stock      |
      |<---------------------------------------------------------------------|
      |                |           |             |                |              |
      | (8) all valid  |           |             |                |              |
      |  -> execute    |           |             |                |              |
      |  saga with DB  |           |             |                |              |
      |  prices        |           |             |                |              |
```

---

## 3. Inventory Caching: The Dangerous Cache

### Why Inventory Caching Is Dangerous

```
  THE PROBLEM:

  Redis cache says: iPhone stock = 5
  DynamoDB (truth) says: iPhone stock = 0 (all reserved)

  If we reserve based on cache:
  -> 5 users think they got an iPhone
  -> 5 orders placed for 0 iPhones
  -> 5 angry customers + 5 refunds + support tickets

  RULE: NEVER make reservation decisions based on cached inventory.
```

### Safe Inventory Caching Strategy

```
  +--------------------------------------------------------------------+
  |                 INVENTORY CACHING RULES                            |
  +--------------------------------------------------------------------+
  |                                                                    |
  |  READ PATH (display "In Stock" / "Only 3 left"):                  |
  |  ================================================                 |
  |  (1) Read from Redis cache (TTL: 10 seconds)                      |
  |  (2) Display approximate stock level to user                      |
  |  (3) This is for UX only -- "3 left!" creates urgency             |
  |  (4) Stale by 10 seconds? User sees "3 left" but it's really 1   |
  |      -> Still OK for display purposes                              |
  |                                                                    |
  |  WRITE PATH (reserve / release / deduct):                         |
  |  ============================================                      |
  |  (1) ALWAYS go to DynamoDB (source of truth)                      |
  |  (2) Conditional write: stock >= requested quantity                |
  |  (3) After successful write: update Redis cache                   |
  |  (4) NEVER read from cache then write to DB (TOCTOU race!)        |
  |                                                                    |
  |  CACHE UPDATE PATTERN (write-through):                            |
  |  (1) DynamoDB reserve succeeds                                     |
  |  (2) Update Redis: SET inventory:PROD-1 <new_stock> EX 10         |
  |  (3) If Redis update fails: no big deal (TTL will expire)         |
  |  (4) Next read will re-populate from DB                           |
  |                                                                    |
  +--------------------------------------------------------------------+
```

### TOCTOU Race Condition -- Why Read-Cache-Then-Write-DB Fails

```
  TIME-OF-CHECK TO TIME-OF-USE (TOCTOU):

  Thread A                    Redis Cache              DynamoDB
    |                            |                        |
    | (1) GET inventory:PROD-1   |                        |
    |--------------------------->|                        |
    |   stock = 5                |                        |
    |<---------------------------|                        |
    |                            |                        |
    |  "5 >= 1? OK, reserve"    |                        |
    |                            |                        |
    |                            |     [Meanwhile...]     |
    |                            |     Thread B reserves  |
    |                            |     4 items directly   |
    |                            |     in DynamoDB        |
    |                            |     stock = 1          |
    |                            |                        |
    | (2) UPDATE DynamoDB        |                        |
    |   SET stock = stock - 1    |                        |
    |   (no condition check!)    |                        |
    |-------------------------------------------------->|
    |   stock = 0                |                        |
    |<--------------------------------------------------|
    |                            |                        |
    |  Looks OK... but what if stock was actually 0      |
    |  and Thread B took the last one?                   |
    |                            |                        |
    |  THE FIX: Conditional write in DynamoDB             |
    |  SET stock = stock - 1 WHERE stock >= 1            |
    |  (the condition check IS the reservation)           |
    |  No separate read step. Atomic check-and-decrement. |
```

### Redis Inventory Cache Data Model

```
  KEY: inventory:{productId}
  VALUE: stock count (integer as string)
  TTL: 10 seconds (short! stock changes fast)

  SET inventory:PROD-1 "500" EX 10
  GET inventory:PROD-1  -> "500"

  CACHE-ASIDE (read path only):
  (1) GET inventory:PROD-1
  (2) If HIT -> display stock level (approximate)
  (3) If MISS -> query DynamoDB -> SET in Redis with 10s TTL -> display

  WRITE-THROUGH (after successful DynamoDB reserve):
  (1) DynamoDB reserve succeeds (stock: 500 -> 499)
  (2) SET inventory:PROD-1 "499" EX 10
  (3) All subsequent reads (within 10s) see 499

  WHY 10-SECOND TTL:
  - Stock changes frequently (especially flash sales)
  - Longer TTL = more stale reads = misleading "In Stock" labels
  - Shorter TTL = more DB reads = higher cost
  - 10 seconds is the sweet spot for most products
  - Flash sale items: TTL = 1 second (or no cache at all)
```

---

## 4. Price Caching: Event-Driven Invalidation

### Architecture

```
  +----------+     +---------+     +---------+     +---------+
  | Admin    |---->| Product |---->| Kafka   |---->| Cache   |
  | (price   |     | Service |     | (price. |     | Invalid.|
  |  change) |     |         |     |  changed|     | Service |
  +----------+     +---------+     |  event) |     +---------+
                        |          +---------+          |
                        |                               |
                        v                               v
                   +----------+                   +---------+
                   | Postgres |                   | Redis   |
                   | (source  |                   | (DEL    |
                   |  of truth|                   |  price: |
                   +----------+                   |  PROD-1)|
                                                  +---------+

  FLOW:
  (1) Admin changes iPhone price from $999 to $899
  (2) Product Service updates PostgreSQL
  (3) Product Service publishes price.changed event to Kafka
  (4) Cache Invalidation Service consumes event
  (5) DEL price:PROD-1 from Redis (invalidate)
  (6) DEL product:PROD-1 from Redis (invalidate product cache too)
  (7) CDN invalidation API call (purge product page)
  (8) Next request triggers cache-aside: reads $899 from DB, populates caches
```

### Price Cache Data Model

```
  KEY: price:{productId}
  VALUE: price as string
  TTL: 300 seconds (5 minutes)

  SET price:PROD-1 "999.00" EX 300
  GET price:PROD-1  -> "999.00"

  INVALIDATION TRIGGERS:
  +--------------------------------------------------------------------+
  | Trigger                | Action                                     |
  +-----------------------+--------------------------------------------+
  | Price change event     | DEL price:{productId}                     |
  | Flash sale start       | DEL price:{productId} for all sale items  |
  | Flash sale end         | DEL price:{productId} for all sale items  |
  | Bulk price update      | FLUSHDB (nuclear option) or loop DEL     |
  | TTL expiry             | Automatic (Redis handles it)              |
  +-----------------------+--------------------------------------------+

  CHECKOUT PRICE VALIDATION:
  At checkout, NEVER use cached price. Always:
  SELECT price FROM products WHERE id = 'PROD-1'
  This ensures the customer pays the REAL price, not a stale cached price.
```

### Price Mismatch Handling

```
  SCENARIO: Cached price = $999, DB price = $899 (flash sale started)

  +--------------------------------------------------------------------+
  |  CASE 1: DB price LOWER than cached price                         |
  |  -> Charge $899 (DB price is source of truth)                      |
  |  -> User sees lower price at checkout -> pleasant surprise         |
  |  -> No notification needed                                         |
  |                                                                    |
  |  CASE 2: DB price HIGHER than cached price                         |
  |  -> DO NOT silently charge more!                                   |
  |  -> Show message: "Price has changed to $999. Continue?"           |
  |  -> User decides to proceed or abandon                             |
  |  -> Legal requirement in most jurisdictions                        |
  |                                                                    |
  |  CASE 3: Product no longer exists                                  |
  |  -> Remove from cart                                               |
  |  -> Show message: "iPhone 15 is no longer available"               |
  |  -> User continues with remaining items                            |
  +--------------------------------------------------------------------+
```

---

## 5. Search Result Caching: Elasticsearch + Redis

### Architecture

```
  +--------+     +---------+     +---------------+     +----------+
  | Client |---->| Redis   |---->| Elasticsearch |---->| Product  |
  | search |     | (query  |     | (inverted     |     | Service  |
  | "iphone"|    |  cache) |     |  index)       |     | (for new |
  +--------+     +---------+     +---------------+     |  data)   |
                                                       +----------+

  POPULAR QUERY CACHING:
  - Top 1000 queries (by frequency) are cached in Redis
  - "iphone", "laptop", "headphones" = always cached
  - "purple unicorn headband size M" = never cached (unique query)
```

### Search Cache Strategy

```
  CACHE KEY DESIGN:
  search:{hash(query + filters + sort + page)}

  EXAMPLES:
  search:abc123  ->  Results for "iphone" with default sort, page 1
  search:def456  ->  Results for "iphone" filtered by price <$500, page 1
  search:ghi789  ->  Results for "laptop" sorted by rating, page 2

  WHY HASH THE KEY:
  - Query strings can be very long
  - Filters create combinatorial explosion
  - MD5/SHA hash gives fixed-length key

  TTL: 300 seconds (5 minutes)
  - Search results change slowly (new products added every few hours)
  - 5-minute staleness is invisible to users
  - Fresh enough for price changes (with event invalidation)

  CACHE-ASIDE:
  (1) Hash the search query + filters
  (2) GET search:{hash} from Redis
  (3) If HIT -> return cached results (sub-millisecond)
  (4) If MISS -> query Elasticsearch (5-50ms) -> SET in Redis -> return
```

### Popular Query Pre-Warming

```
  STARTUP / SCHEDULED JOB (every 30 minutes):

  +--------------------------------------------------------------------+
  | Step | Action                                                      |
  +------+-------------------------------------------------------------+
  | 1    | Read top 1000 queries from analytics (Kafka/ClickHouse)     |
  | 2    | For each query: execute against Elasticsearch               |
  | 3    | Store result in Redis with 10-minute TTL                    |
  | 4    | These queries are "always warm" -- never a cold cache miss  |
  +------+-------------------------------------------------------------+

  WHY PRE-WARM:
  - Top 1000 queries account for ~60% of total search traffic
  - Cold start after Redis restart would spike Elasticsearch load
  - Pre-warming ensures consistent <5ms response time

  ZIPF DISTRIBUTION:
  +--------------------------------------------------------------------+
  | Rank | Query         | Traffic Share | Cache Status                |
  +------+---------------+---------------+-----------------------------+
  | 1    | "iphone"      | 2.5%          | Always cached (pre-warmed)  |
  | 2    | "laptop"      | 2.0%          | Always cached (pre-warmed)  |
  | 3    | "headphones"  | 1.5%          | Always cached (pre-warmed)  |
  | ...  | ...           | ...           | ...                         |
  | 1000 | "usb hub"     | 0.01%         | Cached (pre-warmed)         |
  | 1001 | "cat sweater" | 0.005%        | Cached on first hit         |
  | 5000 | "purple uni.."| <0.001%       | Not cached (too unique)     |
  +------+---------------+---------------+-----------------------------+
```

---

## 6. Flash Sale Handling: The Ultimate Caching Challenge

### The Problem

```
  FLASH SALE: iPhone at $499 (50% off). 1000 units. Starts at 12:00 PM.

  12:00:00 PM: 500,000 users hit "Buy" simultaneously
  Normal flow: each user reads inventory -> checks stock -> reserves
  Result: 500,000 reads + writes to DynamoDB in 1 second

  PROBLEMS:
  (1) DynamoDB throttling (even with auto-scale, ramp-up takes 5-15 min)
  (2) 499,000 users get "Out of Stock" after waiting 30 seconds
  (3) 1,000 lucky users get the item, but checkout is slow
  (4) Everyone thinks the site is broken
```

### Flash Sale Architecture

```
  +--------------------------------------------------------------------+
  |                     FLASH SALE ARCHITECTURE                        |
  +--------------------------------------------------------------------+
  |                                                                    |
  |  BEFORE SALE (pre-warm):                                           |
  |  (1) Load 1000 units into Redis counter                            |
  |      SET flash:PROD-1:stock 1000                                   |
  |                                                                    |
  |  (2) Pre-warm product cache                                        |
  |      SET product:PROD-1 '{"price":499,...}' EX 3600                |
  |                                                                    |
  |  (3) Pre-warm CDN with flash sale page                             |
  |                                                                    |
  |  DURING SALE:                                                      |
  |                                                                    |
  |  500,000 users                                                     |
  |      |                                                             |
  |      v                                                             |
  |  +--------+     +---------+                                        |
  |  | Rate   |---->| Redis   |   DECR flash:PROD-1:stock              |
  |  | Limiter|     | (atomic |   If result >= 0: user gets a "token"  |
  |  | (10K/s)|     |  DECR)  |   If result < 0: "Sold Out" instantly  |
  |  +--------+     +---------+                                        |
  |                      |                                             |
  |         +------------+                                             |
  |         |            |                                             |
  |     result >= 0   result < 0                                       |
  |     (got token)   (sold out)                                       |
  |         |            |                                             |
  |         v            v                                             |
  |  +-----------+  "Sold Out"                                         |
  |  | Order     |  (instant                                           |
  |  | Queue     |   response                                          |
  |  | (Kafka/   |   <1ms)                                             |
  |  |  SQS)     |                                                     |
  |  +-----------+                                                     |
  |         |                                                          |
  |         v                                                          |
  |  +-----------+     +----------+     +----------+                   |
  |  | Order     |---->| DynamoDB |---->| Payment  |                   |
  |  | Worker    |     | (actual  |     | Service  |                   |
  |  | (async)   |     |  reserve)|     |          |                   |
  |  +-----------+     +----------+     +----------+                   |
  |                                                                    |
  +--------------------------------------------------------------------+
```

### Flash Sale: Step by Step

```
  STEP 1: PRE-WARM (30 minutes before sale)
  ==========================================

  Admin triggers flash sale preparation:
  (1) SET flash:PROD-1:stock 1000              -- Redis counter
  (2) SET flash:PROD-1:active "true" EX 7200   -- sale active flag (2 hour window)
  (3) Pre-warm product cache with sale price
  (4) Notify CDN to cache flash sale landing page
  (5) Scale up API servers (horizontal auto-scale)
  (6) Pre-provision DynamoDB capacity (if not on-demand)

  STEP 2: GATE KEEPER (Redis atomic decrement)
  =============================================

  User clicks "Buy":
  (1) Rate limiter: allow 10K requests/second (reject excess)
  (2) DECR flash:PROD-1:stock
      - Returns 999 (first user): SUCCESS -> issue purchase token
      - Returns 0 (1000th user): SUCCESS -> issue last purchase token
      - Returns -1 (1001st user): SOLD OUT -> instant rejection
      - Returns -499000 (500000th user): SOLD OUT -> instant rejection

  WHY REDIS DECR:
  - Atomic (no race conditions)
  - O(1) operation (<0.1ms)
  - Handles 100K+ ops/sec on single node
  - 499,000 "sold out" responses in <1ms each (no DB hit)

  STEP 3: QUEUE-BASED PROCESSING
  ===============================

  1000 users with purchase tokens:
  (1) Token + order details -> Kafka/SQS queue
  (2) Order Worker consumes at steady rate (e.g., 100/second)
  (3) Worker executes saga: reserve (DynamoDB) -> pay -> ship
  (4) User sees "Processing your order..." with polling/WebSocket
  (5) Worker completes: notify user "Order confirmed!"

  WHY QUEUE:
  - 1000 orders at 100/sec = 10 seconds to process all
  - Much better than 1000 concurrent DB writes in 1 second
  - DynamoDB handles 100 writes/sec easily (no throttling)
  - User experience: "Your order is being processed" (acceptable)

  STEP 4: RECONCILIATION (after sale ends)
  =========================================

  (1) Compare Redis counter with DynamoDB stock
  (2) If Redis says 0 remaining but DynamoDB says 5 remaining:
      -> Some orders failed in the queue (payment declined, etc.)
      -> Release those 5 back: INCRBY flash:PROD-1:stock 5
      -> Optionally: notify waitlisted users
  (3) If Redis went below 0 (shouldn't happen with DECR):
      -> Log anomaly for investigation
      -> Never oversold because queue processes against real DB
```

### Flash Sale: Why This Works

```
  TRAFFIC HANDLING ANALYSIS:

  500,000 requests in 1 second:

  WITHOUT FLASH SALE ARCHITECTURE:
  - 500,000 DB reads (check stock)
  - 500,000 DB writes (attempt reserve)
  - DB melts. Site crashes. Twitter outrage. CEO apologizes.

  WITH FLASH SALE ARCHITECTURE:
  - Rate limiter: 490,000 rejected (instant response)
  - 10,000 reach Redis DECR
  - 1,000 get tokens (Redis: <1ms each)
  - 9,000 get "Sold Out" (Redis: <1ms each)
  - 1,000 queued for processing
  - 100 DB writes/second (comfortable)

  COST:
  - Redis: 10,000 DECR operations = $0.001
  - DynamoDB: 1,000 conditional writes = $0.00125
  - Total backend cost: less than 1 cent per flash sale
```

---

## 7. Cache Consistency at Checkout: The Critical Path

### The Problem

```
  CHECKOUT IS WHERE CACHE CONSISTENCY MATTERS MOST.

  During checkout, we are:
  (1) Reserving real inventory (money value)
  (2) Charging real money (payment)
  (3) Creating a legal obligation (order)

  A cache-based error at checkout means:
  - Overselling (reserved stock we don't have)
  - Wrong price (charged $899 when price is $999)
  - Missing product (sold a discontinued item)

  Therefore: CHECKOUT BYPASSES ALL CACHES.
```

### Checkout Read Path: Cache Bypass

```
  NORMAL READ (product page):        CHECKOUT READ (place order):
  ============================        ==============================

  Client -> CDN -> Redis -> DB        Client -> DB (directly!)
  (fast, stale OK)                    (slower, MUST be correct)

  +--------------------------------------------------------------------+
  | Data Point       | Product Page     | Checkout                     |
  +------------------+------------------+------------------------------+
  | Product price    | Redis cache      | PostgreSQL (SELECT price)    |
  | Stock level      | Redis cache      | DynamoDB (conditional write) |
  | Product exists   | Redis cache      | PostgreSQL (SELECT id)       |
  | Shipping address | Redis (session)  | Validated against address DB |
  | Discount code    | Redis cache      | PostgreSQL (validate + mark  |
  |                  |                  |  as used, atomic)             |
  +------------------+------------------+------------------------------+
```

### Numbered Call Chain -- Checkout Cache Bypass

```
  OrderService     PricingStrategy    ProductRepo(DB)    InventoryRepo(DB)    SagaOrchestrator
      |                  |                 |                   |                    |
      | (1) validate     |                 |                   |                    |
      |  cart items      |                 |                   |                    |
      |                  |                 |                   |                    |
      | (2) for each item:                 |                   |                    |
      |  findById(prodId)|                 |                   |                    |
      |  [SKIP CACHE]    |                 |                   |                    |
      |---------------------------------->|                   |                    |
      |  Product (from DB)|                |                   |                    |
      |<----------------------------------|                   |                    |
      |                  |                 |                   |                    |
      | (3) check item   |                 |                   |                    |
      |  still exists +  |                 |                   |                    |
      |  price unchanged |                 |                   |                    |
      |                  |                 |                   |                    |
      | (4) calculate    |                 |                   |                    |
      |  total (DB prices|                 |                   |                    |
      |  not cached!)    |                 |                   |                    |
      |----------------->|                 |                   |                    |
      |  $135.00         |                 |                   |                    |
      |<-----------------|                 |                   |                    |
      |                  |                 |                   |                    |
      | (5) execute saga |                 |                   |                    |
      |  (inventory +    |                 |                   |                    |
      |   payment)       |                 |                   |                    |
      |                  |                 |                   |                    |
      |  Saga reads from |                 |                   |                    |
      |  DB, NOT cache:  |                 |                   |                    |
      |                  |                 |                   |                    |
      |-------------------------------------------------------------->|
      |                  |                 |                   |       |
      |                  |                 |       (6) reserve |       |
      |                  |                 |        inventory  |       |
      |                  |                 |       [DynamoDB   |       |
      |                  |                 |        conditional|       |
      |                  |                 |        write]     |       |
      |                  |                 |                   |       |
      |                  |                 |       (7) charge  |       |
      |                  |                 |        payment    |       |
      |                  |                 |       [Stripe API |       |
      |                  |                 |        with idem- |       |
      |                  |                 |        potency    |       |
      |                  |                 |        key]       |       |
      |                  |                 |                   |       |
      |  SagaResult      |                 |                   |       |
      |  (SUCCESS)       |                 |                   |       |
      |<--------------------------------------------------------------|
      |                  |                 |                   |       |
      | (8) update caches|                 |                   |       |
      |  (post-commit):  |                 |                   |       |
      |  - inventory     |                 |                   |       |
      |    cache updated |                 |                   |       |
      |  - product cache |                 |                   |       |
      |    unchanged     |                 |                   |       |
```

---

## 8. Multi-Level Caching Architecture

### The Full Caching Stack

```
  +--------------------------------------------------------------------+
  |                     MULTI-LEVEL CACHE STACK                        |
  +--------------------------------------------------------------------+
  |                                                                    |
  |  LEVEL 1: Browser Cache (client-side)                              |
  |  ========================================                          |
  |  - Static assets: JS, CSS, images (Cache-Control: max-age=86400)  |
  |  - Product images (immutable, content-addressed URLs)              |
  |  - NOT: prices, stock, cart (must be fresh)                        |
  |  - Hit rate: ~40% of static asset requests                        |
  |                                                                    |
  |  LEVEL 2: CDN (edge cache)                                        |
  |  ========================================                          |
  |  - Product pages (TTL: 30 seconds)                                 |
  |  - Category pages (TTL: 60 seconds)                                |
  |  - Product images (TTL: 24 hours)                                  |
  |  - NOT: cart, checkout, user-specific pages                        |
  |  - Hit rate: ~80% of product page requests                        |
  |                                                                    |
  |  LEVEL 3: Redis (application cache)                                |
  |  ========================================                          |
  |  - Product details (TTL: 5 minutes)                                |
  |  - Prices (TTL: 5 minutes, event-invalidated)                      |
  |  - Shopping cart (TTL: 7 days, write-through)                      |
  |  - Search results (TTL: 5 minutes, popular queries)                |
  |  - Inventory (TTL: 10 seconds, display only)                       |
  |  - Session data (TTL: 30 minutes)                                  |
  |  - Hit rate: ~95% of remaining requests                           |
  |                                                                    |
  |  LEVEL 4: Application-Level Cache (JVM)                            |
  |  ========================================                          |
  |  - Configuration data (loaded at startup)                          |
  |  - Category tree (refreshed every hour)                            |
  |  - Feature flags (refreshed every minute)                          |
  |  - Hit rate: 100% (always in memory)                              |
  |                                                                    |
  |  LEVEL 5: Database (source of truth)                               |
  |  ========================================                          |
  |  - PostgreSQL: orders, payments, products                          |
  |  - DynamoDB: inventory                                             |
  |  - Elasticsearch: search index                                     |
  |  - Only ~5% of original traffic reaches here                       |
  |                                                                    |
  +--------------------------------------------------------------------+

  EFFECTIVE MULTIPLIER:
  1,000,000 requests/sec at the edge
  -> 200,000 after CDN (80% hit)
  -> 10,000 after Redis (95% hit)
  -> 10,000 reach database

  That's a 100x reduction in database load.
```

### Cache TTL Decision Matrix

```
  +--------------------------------------------------------------------+
  | Data Type            | L1 Browser | L2 CDN  | L3 Redis | Reason    |
  +----------------------+------------+---------+----------+-----------+
  | Product images       | 24 hours   | 24 hrs  | N/A      | Immutable |
  | Product details      | 0 (no)     | 30 sec  | 5 min    | Moderate  |
  |                      |            |         |          | change    |
  | Prices               | 0 (no)     | 30 sec  | 5 min    | Event     |
  |                      |            |         |          | invalidtd |
  | Inventory (display)  | 0 (no)     | 0 (no)  | 10 sec   | Changes   |
  |                      |            |         |          | fast      |
  | Search results       | 0 (no)     | 0 (no)  | 5 min    | Personal  |
  | Shopping cart         | 0 (no)     | 0 (no)  | 7 days   | User-     |
  |                      |            |         |          | specific  |
  | Category tree        | 0 (no)     | 60 sec  | 1 hour   | Rarely    |
  |                      |            |         |          | changes   |
  | Static assets        | 30 days    | 30 days | N/A      | Versioned |
  |                      |            |         |          | URLs      |
  +----------------------+------------+---------+----------+-----------+
```

---

## 9. Cache Sizing and Eviction

### Redis Memory Budget

```
  MEMORY ESTIMATION:

  +--------------------------------------------------------------------+
  | Cache Type          | # Entries    | Avg Size  | Total Memory      |
  +---------------------+--------------+-----------+-------------------+
  | Product details     | 1M products  | 2 KB      | 2 GB              |
  | Prices              | 1M products  | 64 bytes  | 64 MB             |
  | Shopping carts      | 500K active  | 5 KB      | 2.5 GB            |
  | Search results      | 100K queries | 10 KB     | 1 GB              |
  | Inventory (display) | 1M products  | 64 bytes  | 64 MB             |
  | Sessions            | 500K users   | 1 KB      | 500 MB            |
  +---------------------+--------------+-----------+-------------------+
  | TOTAL               |              |           | ~6.2 GB           |
  +---------------------+--------------+-----------+-------------------+

  RECOMMENDATION:
  - Redis instance: r6g.xlarge (26 GB memory) -- plenty of headroom
  - Redis Cluster: 3 primary + 3 replica = 6 nodes for HA
  - Total cluster memory: 78 GB (12x headroom for growth + overhead)
```

### Eviction Policy

```
  REDIS EVICTION POLICY: allkeys-lru

  WHY allkeys-lru (not volatile-lru):
  - Some keys might lose their TTL (programming error)
  - allkeys-lru evicts ANY key based on LRU, even without TTL
  - Prevents OOM even if TTL setting is buggy

  EVICTION PRIORITY (natural via LRU):
  +--------------------------------------------------------------------+
  | High Priority (rarely evicted)    | Low Priority (evicted first)   |
  +-----------------------------------+--------------------------------+
  | Active shopping carts             | Old search results             |
  | Current flash sale data           | Products not viewed in hours   |
  | Session data for active users     | Expired promotional data       |
  | Top 1000 search queries           | Inventory for obscure products |
  +-----------------------------------+--------------------------------+

  LRU naturally keeps hot data and evicts cold data.
  No manual eviction logic needed.
```

---

## 10. Cache Stampede Prevention

### The Problem

```
  CACHE STAMPEDE (Thundering Herd):

  t=0: Popular product cache expires (TTL = 300 seconds)
  t=0.001: 10,000 concurrent requests for this product
  t=0.001: All 10,000 see CACHE MISS
  t=0.001: All 10,000 query PostgreSQL simultaneously
  t=0.001: PostgreSQL: "help, 10,000 identical queries!"

  +----------+     +---------+     +---------+
  |10,000    |     | Redis   |     | Postgres|
  |requests  |---->| MISS!   |---->| 10,000  |
  |(same key)|     |         |     | queries |  <-- STAMPEDE!
  +----------+     +---------+     +---------+
```

### Solutions

```
  SOLUTION 1: MUTEX / LOCK (single-flight)
  =========================================

  (1) First request acquires Redis lock: SET product:PROD-1:lock 1 NX EX 5
  (2) If lock acquired: query DB, populate cache, release lock
  (3) Other 9,999 requests: see lock exists, wait 50ms, retry from cache
  (4) By retry time, cache is populated -> all get cache HIT

  Redis pseudo-code:
  function getProduct(productId):
      value = GET product:{productId}
      if value != null: return value  // cache hit

      // Try to acquire lock
      locked = SET product:{productId}:lock 1 NX EX 5
      if locked:
          value = queryDB(productId)
          SET product:{productId} value EX 300
          DEL product:{productId}:lock
          return value
      else:
          // Another thread is loading -- wait and retry
          sleep(50ms)
          return GET product:{productId}  // should be populated now


  SOLUTION 2: STALE-WHILE-REVALIDATE
  ====================================

  (1) Store data with two TTLs: soft TTL (5 min) and hard TTL (10 min)
  (2) When soft TTL expires: return stale data AND trigger async refresh
  (3) Background thread refreshes cache from DB
  (4) Users never see a cache miss (always get data, sometimes stale)

  KEY: product:{productId}
  VALUE: {"data": {...}, "softExpiry": "2024-01-15T10:35:00Z"}
  TTL: 600 seconds (hard expiry)

  Read flow:
  (1) GET product:PROD-1
  (2) If softExpiry < now(): return data + trigger async refresh
  (3) If hardExpiry (Redis TTL) < now(): cache miss, blocking read from DB


  SOLUTION 3: JITTER (for bulk expiry prevention)
  =================================================

  Instead of: TTL = 300 seconds (all expire at the same time)
  Use: TTL = 300 + random(0, 60) seconds (expire at slightly different times)

  This prevents thousands of keys from expiring simultaneously.
```

---

## Interview Q&A

| Question | Answer |
|----------|--------|
| "How do you cache product data?" | Multi-level: Browser (static assets) -> CDN (product pages, 30s TTL) -> Redis (product details, 5 min TTL) -> DB. 95% hit rate means only 5% of traffic reaches the database. |
| "What about cache consistency for prices?" | Event-driven invalidation. Price change -> Kafka event -> invalidate Redis + CDN. Checkout always reads from DB, never cache. Stale prices are display-only -- we never charge a cached price. |
| "How do you handle inventory caching?" | Very carefully. Read path: Redis cache with 10-second TTL for "In Stock" display. Write path: ALWAYS DynamoDB conditional write. Never make reservation decisions based on cache -- TOCTOU race condition. |
| "What happens during a flash sale?" | Pre-warm Redis counter (SET flash:PROD-1:stock 1000). Users get Redis DECR (atomic, <0.1ms). If result >= 0: purchase token -> queue. If < 0: "Sold Out" instantly. Queue processes orders against real DB at steady rate. |
| "How do you prevent cache stampede?" | Mutex pattern: first request acquires lock, queries DB, populates cache. Others wait 50ms and retry (cache should be warm by then). Also: stale-while-revalidate and TTL jitter for bulk expiry prevention. |
| "What's the checkout cache strategy?" | Cache bypass. At checkout, we skip CDN, skip Redis, go straight to DB for prices, inventory, and product validation. This is the AP-to-CP boundary -- display uses cache, transactions use DB. |
| "How do you handle cart caching?" | Redis Hash per user (write-through to DynamoDB for durability). Hash fields are per-item, so adding/removing one item doesn't require serializing the entire cart. 7-day TTL for abandoned cart cleanup. |
| "What's your cache eviction policy?" | allkeys-lru. LRU naturally keeps hot data (active carts, popular products) and evicts cold data (old search results, stale inventory). No manual eviction logic needed. |
| "How much Redis memory do you need?" | ~6 GB for 1M products + 500K carts + 100K search results. We run r6g.xlarge (26 GB) with 3+3 cluster for HA. 4x headroom for growth and Redis overhead. |
| "Cache vs speed vs correctness tradeoff?" | Cache for speed on reads (display layer). Bypass cache for correctness on writes (transaction layer). The boundary is checkout -- everything before it is AP (cached), everything during it is CP (DB-direct). |
