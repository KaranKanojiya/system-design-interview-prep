# Caching Strategies for the Payment System (Stripe/UPI)

> Interview-ready reference for a Senior Java developer.
> Payment system caching is EXTREMELY conservative -- cache the wrong thing and you double-charge, lose money, or corrupt the ledger.
> The golden rule: CACHE for SPEED, read from DB for DECISIONS.

---

## Table of Contents

| Section | Key Point |
|---------|-----------|
| What to Cache vs Not | Idempotency keys and exchange rates = cache. Payment status and ledger = NEVER cache. |
| Idempotency Cache | Redis SET NX with TTL -- the most critical cache in the system |
| Exchange Rate Cache | Cache-aside, 5-minute TTL, lock rate at authorization |
| Merchant Config Cache | Read-through, 10-minute TTL |
| What NOT to Cache | Payment status, ledger entries, account balances |
| Webhook Retry Queue | Redis sorted set by next retry time |
| Cache Failure Handling | What happens when Redis dies |
| Multi-Level Caching | Redis -> DB fallback (no CDN for payments) |
| Cache Sizing & TTL | Per data type TTL strategy |
| Interview Q&A | Ready-to-use answers |

---

## What to Cache vs What NOT to Cache

### The Caching Decision Matrix

```
  +----------------------------------------------------------------------+
  |                      CACHE DECISION MATRIX                            |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  SAFE TO CACHE                       NEVER CACHE                     |
  |  (speed optimization,                (stale = money lost)            |
  |   stale is tolerable)                                                |
  |  ============================        =============================   |
  |                                                                      |
  |  +---------------------+             +---------------------+         |
  |  | Idempotency Keys    |             | Payment Status      |         |
  |  | Redis SET NX        |             | MUST read from DB   |         |
  |  | TTL: 24 hours       |             | Stale status =      |         |
  |  | Purpose: prevent    |             | double-charge or    |         |
  |  | double-processing   |             | missed refund       |         |
  |  +---------------------+             +---------------------+         |
  |                                                                      |
  |  +---------------------+             +---------------------+         |
  |  | Exchange Rates      |             | Ledger Entries      |         |
  |  | Cache-aside         |             | NEVER cache         |         |
  |  | TTL: 5 minutes      |             | Books MUST balance  |         |
  |  | Stale rate for      |             | from DB, not cache  |         |
  |  | display = OK        |             | Stale entry =       |         |
  |  +---------------------+             | audit failure       |         |
  |                                      +---------------------+         |
  |  +---------------------+                                             |
  |  | Merchant Config     |             +---------------------+         |
  |  | Read-through        |             | Account Balances    |         |
  |  | TTL: 10 minutes     |             | MUST read from DB   |         |
  |  | Webhook URL, API    |             | Stale balance =     |         |
  |  | version, etc.       |             | overdraft or        |         |
  |  +---------------------+             | insufficient funds  |         |
  |                                      | not caught          |         |
  |                                      +---------------------+         |
  |                                                                      |
  |                                      +---------------------+         |
  |                                      | Transaction IDs     |         |
  |                                      | NEVER cache         |         |
  |                                      | Each must be unique |         |
  |                                      | and DB-authoritative|         |
  |                                      +---------------------+         |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### The Golden Rule

```
  +----------------------------------------------------------------------+
  |                                                                      |
  |  CACHE for SPEED.  Read from DB for DECISIONS.                       |
  |                                                                      |
  |  Exchange rate shows $83.50/USD on dashboard (from cache)?   FINE.   |
  |  Checkout converts at $83.50/USD (from cache)?               RISKY.  |
  |  Checkout converts at rate locked at authorization time?     CORRECT.|
  |                                                                      |
  |  Merchant webhook URL from cache for delivery?               FINE.   |
  |  Account balance from cache to approve payment?              DANGER. |
  |  Account balance from DB with SELECT FOR UPDATE?             CORRECT.|
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Why Each Decision

```
  +----------------------------------------------------------------------+
  |              WHY EACH CACHING DECISION                                |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  +-----------------------------+------------------------------------+|
  |  | Data                        | Cache? | Reason                    ||
  |  +-----------------------------+--------+---------------------------+|
  |  | Idempotency keys            | YES    | Fast lookup is critical   ||
  |  |                             |        | for preventing double-    ||
  |  |                             |        | charge. Redis SET NX is   ||
  |  |                             |        | atomic and sub-ms.        ||
  |  +-----------------------------+--------+---------------------------+|
  |  | Exchange rates              | YES    | Forex API is slow (100ms+ ||
  |  |                             |        | per call). Rates don't    ||
  |  |                             |        | change per-second. 5-min  ||
  |  |                             |        | staleness is acceptable.  ||
  |  +-----------------------------+--------+---------------------------+|
  |  | Merchant config             | YES    | Webhook URL, API version  ||
  |  |                             |        | change rarely. 10-min TTL ||
  |  |                             |        | saves DB roundtrip.       ||
  |  +-----------------------------+--------+---------------------------+|
  |  | Payment status              | NO     | If cache says PROCESSING  ||
  |  |                             |        | but DB says AUTHORIZED,   ||
  |  |                             |        | a capture request would   ||
  |  |                             |        | fail incorrectly.         ||
  |  +-----------------------------+--------+---------------------------+|
  |  | Ledger entries              | NO     | Ledger is append-only,    ||
  |  |                             |        | ACID-protected. Caching   ||
  |  |                             |        | risks showing stale       ||
  |  |                             |        | balances. Audit queries   ||
  |  |                             |        | MUST hit DB.              ||
  |  +-----------------------------+--------+---------------------------+|
  |  | Account balances            | NO     | Stale balance = approve   ||
  |  |                             |        | payment that overdrafts.  ||
  |  |                             |        | SELECT FOR UPDATE in DB   ||
  |  |                             |        | with row lock is the only ||
  |  |                             |        | safe approach.            ||
  |  +-----------------------------+--------+---------------------------+|
  |                                                                      |
  +----------------------------------------------------------------------+
```

---

## Idempotency Cache: Redis SET NX with TTL

### The Most Critical Cache in the System

```
  +----------------------------------------------------------------------+
  |              IDEMPOTENCY CACHE ARCHITECTURE                           |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  PURPOSE: Prevent double-charge when client retries.                 |
  |  MECHANISM: Redis SET NX (atomic check-and-set)                      |
  |  TTL: 24 hours (auto-cleanup of old keys)                            |
  |                                                                      |
  |  WHY REDIS (not DB UNIQUE constraint alone):                         |
  |  - SET NX is O(1), sub-millisecond                                   |
  |  - DB UNIQUE constraint requires INSERT attempt + catch exception    |
  |  - Redis check happens BEFORE any processing (fail fast)             |
  |  - DB UNIQUE is the BACKUP (defense in depth), not the primary       |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Idempotency Flow: Step by Step

```
  +----------------------------------------------------------------------+
  |              IDEMPOTENCY FLOW (HAPPY PATH)                            |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Client              PaymentService            Redis                 |
  |    |                      |                      |                   |
  |    | (1) POST /payments   |                      |                   |
  |    |  Idempotency-Key:    |                      |                   |
  |    |  "idem-abc-123"      |                      |                   |
  |    |--------------------->|                      |                   |
  |    |                      |                      |                   |
  |    |                      | (2) SET              |                   |
  |    |                      |  idem:abc-123        |                   |
  |    |                      |  "PENDING"           |                   |
  |    |                      |  NX                  |                   |
  |    |                      |  EX 86400            |                   |
  |    |                      |--------------------->|                   |
  |    |                      |                      |                   |
  |    |                      |  "OK"                |                   |
  |    |                      |  (key set, we own    |                   |
  |    |                      |   the lock)          |                   |
  |    |                      |<---------------------|                   |
  |    |                      |                      |                   |
  |    |                      | (3) Process payment  |                   |
  |    |                      |  (fraud check,       |                   |
  |    |                      |   charge bank,       |                   |
  |    |                      |   record ledger)     |                   |
  |    |                      |                      |                   |
  |    |                      | (4) SET              |                   |
  |    |                      |  idem:abc-123        |                   |
  |    |                      |  '{"status":         |                   |
  |    |                      |   "SUCCESS",         |                   |
  |    |                      |   "auth":"AUTH-X"}'  |                   |
  |    |                      |  XX                  |                   |
  |    |                      |  EX 86400            |                   |
  |    |                      |--------------------->|                   |
  |    |                      |  "OK"                |                   |
  |    |                      |<---------------------|                   |
  |    |                      |                      |                   |
  |    |  200 OK              |                      |                   |
  |    |  {"status":          |                      |                   |
  |    |   "SUCCESS",         |                      |                   |
  |    |   "auth": "AUTH-X"}  |                      |                   |
  |    |<---------------------|                      |                   |
  |                                                                      |
  +----------------------------------------------------------------------+

  +----------------------------------------------------------------------+
  |              IDEMPOTENCY FLOW (RETRY -- DUPLICATE)                    |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Client              PaymentService            Redis                 |
  |    |                      |                      |                   |
  |    | (1) POST /payments   |                      |                   |
  |    |  Idempotency-Key:    |                      |                   |
  |    |  "idem-abc-123"      |                      |                   |
  |    |  (SAME key as before)|                      |                   |
  |    |--------------------->|                      |                   |
  |    |                      |                      |                   |
  |    |                      | (2) SET              |                   |
  |    |                      |  idem:abc-123        |                   |
  |    |                      |  "PENDING"           |                   |
  |    |                      |  NX                  |                   |
  |    |                      |  EX 86400            |                   |
  |    |                      |--------------------->|                   |
  |    |                      |                      |                   |
  |    |                      |  NIL                 |                   |
  |    |                      |  (key exists! Not    |                   |
  |    |                      |   our lock)          |                   |
  |    |                      |<---------------------|                   |
  |    |                      |                      |                   |
  |    |                      | (3) GET              |                   |
  |    |                      |  idem:abc-123        |                   |
  |    |                      |--------------------->|                   |
  |    |                      |                      |                   |
  |    |                      |  '{"status":         |                   |
  |    |                      |   "SUCCESS",         |                   |
  |    |                      |   "auth":"AUTH-X"}'  |                   |
  |    |                      |<---------------------|                   |
  |    |                      |                      |                   |
  |    |  200 OK              |                      |                   |
  |    |  {"status":          |  [BANK NOT CHARGED   |                   |
  |    |   "SUCCESS",         |   AGAIN -- cached    |                   |
  |    |   "auth": "AUTH-X"}  |   result returned]   |                   |
  |    |<---------------------|                      |                   |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Race Condition Handling

```
  +----------------------------------------------------------------------+
  |              CONCURRENT REQUESTS WITH SAME IDEMPOTENCY KEY            |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Two requests arrive at the EXACT same time with key "idem-abc-123"  |
  |                                                                      |
  |  Request A           Redis               Request B                   |
  |     |                  |                     |                       |
  |     | (1A) SET NX      |      (1B) SET NX   |                       |
  |     |  "idem:abc-123"  |  "idem:abc-123"    |                       |
  |     |  "PENDING"       |  "PENDING"          |                       |
  |     |----------------->|<--------------------|                       |
  |     |                  |                     |                       |
  |     |  Redis executes commands SEQUENTIALLY (single-threaded)        |
  |     |                  |                     |                       |
  |     |  "OK"            |                     |                       |
  |     |  (A wins the     |                     |                       |
  |     |   lock)          |                     |                       |
  |     |<-----------------|                     |                       |
  |     |                  |                     |                       |
  |     |                  |  NIL                |                       |
  |     |                  |  (B loses -- key    |                       |
  |     |                  |   already exists)   |                       |
  |     |                  |-------------------->|                       |
  |     |                  |                     |                       |
  |     | (2A) Process     |                     | (2B) GET key          |
  |     |  payment         |                     |  -> "PENDING"         |
  |     |  (charge bank)   |                     |  (not done yet)       |
  |     |                  |                     |                       |
  |     |                  |                     | (3B) Wait 100ms,      |
  |     |                  |                     |  poll again           |
  |     |                  |                     |                       |
  |     | (3A) SET XX      |                     |                       |
  |     |  "SUCCESS"       |                     |                       |
  |     |----------------->|                     |                       |
  |     |                  |                     |                       |
  |     |                  |                     | (4B) GET key          |
  |     |                  |                     |  -> "SUCCESS"         |
  |     |                  |                     |  (result ready!)      |
  |     |                  |                     |                       |
  |     |  Return SUCCESS  |                     |  Return SUCCESS       |
  |     |  (processed)     |                     |  (cached, no bank    |
  |     |                  |                     |   charge)             |
  |                                                                      |
  |  KEY: Redis SET NX is ATOMIC. Even if two requests arrive at the     |
  |  exact same nanosecond, Redis processes them sequentially.           |
  |  Only one wins. The other must wait or return cached result.         |
  +----------------------------------------------------------------------+
```

### Failure Scenarios

```
  +----------------------------------------------------------------------+
  |              IDEMPOTENCY CACHE FAILURE SCENARIOS                       |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  SCENARIO 1: Payment processing fails after acquiring lock           |
  |  +---------------------------------------------------------------+  |
  |  | (1) SET NX "idem:abc-123" "PENDING" -> OK                    |  |
  |  | (2) Charge bank -> DECLINED (insufficient funds)              |  |
  |  | (3) SET "idem:abc-123" '{"status":"DECLINED"}' XX             |  |
  |  |                                                               |  |
  |  | Client retries? Gets DECLINED from cache (correct behavior).  |  |
  |  | Client should NOT retry with same idem key for declined       |  |
  |  | payments -- generate a new key if retrying with different     |  |
  |  | payment details.                                              |  |
  |  +---------------------------------------------------------------+  |
  |                                                                      |
  |  SCENARIO 2: Service crashes after acquiring lock                    |
  |  +---------------------------------------------------------------+  |
  |  | (1) SET NX "idem:abc-123" "PENDING" -> OK                    |  |
  |  | (2) Service crashes before bank charge                        |  |
  |  | (3) Key "idem:abc-123" stuck in "PENDING" state               |  |
  |  |                                                               |  |
  |  | SOLUTION: Stale lock detection                                |  |
  |  | - Store timestamp in the PENDING value                        |  |
  |  | - SET NX "idem:abc-123" '{"status":"PENDING","ts":1234567}'   |  |
  |  | - If another request finds PENDING and ts > 30 seconds old:   |  |
  |  |   DEL key, retry SET NX (claim the expired lock)              |  |
  |  | - If PENDING and ts < 30 seconds: wait (processing in flight) |  |
  |  +---------------------------------------------------------------+  |
  |                                                                      |
  |  SCENARIO 3: Redis is completely down                                |
  |  +---------------------------------------------------------------+  |
  |  | (1) SET NX -> CONNECTION_REFUSED                              |  |
  |  | (2) FAIL FAST. Return 503 Service Unavailable.                |  |
  |  | (3) Do NOT process without idempotency check.                 |  |
  |  |                                                               |  |
  |  | WHY: Processing without idempotency = risk of double-charge.  |  |
  |  | Better to be temporarily unavailable (CP choice).             |  |
  |  |                                                               |  |
  |  | FALLBACK (if SLA requires higher availability):               |  |
  |  | - Fall back to DB UNIQUE constraint on idempotency_key column |  |
  |  | - INSERT INTO payments (..., idempotency_key) VALUES (...)    |  |
  |  | - If UNIQUE violation: duplicate, return cached from DB       |  |
  |  | - Slower (10ms vs 0.5ms) but correct                         |  |
  |  +---------------------------------------------------------------+  |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Implementation

```java
public class RedisIdempotencyRepository implements IdempotencyRepository {
    private final JedisPool jedisPool;
    private static final int TTL_SECONDS = 86400; // 24 hours
    private static final int STALE_THRESHOLD_MS = 30000; // 30 seconds

    @Override
    public boolean tryAcquire(String idempotencyKey) {
        try (Jedis jedis = jedisPool.getResource()) {
            String value = "{\"status\":\"PENDING\",\"ts\":" +
                System.currentTimeMillis() + "}";
            // SET NX EX: atomic check-and-set with TTL
            String result = jedis.set(
                "idem:" + idempotencyKey,
                value,
                SetParams.setParams().nx().ex(TTL_SECONDS)
            );
            return "OK".equals(result);
        }
    }

    @Override
    public Optional<PaymentResult> get(String idempotencyKey) {
        try (Jedis jedis = jedisPool.getResource()) {
            String value = jedis.get("idem:" + idempotencyKey);
            if (value == null) return Optional.empty();

            // Parse the cached result
            JsonObject json = JsonParser.parseString(value).getAsJsonObject();
            String status = json.get("status").getAsString();

            if ("PENDING".equals(status)) {
                // Check if stale (crash recovery)
                long ts = json.get("ts").getAsLong();
                if (System.currentTimeMillis() - ts > STALE_THRESHOLD_MS) {
                    // Stale lock -- delete and let caller retry
                    jedis.del("idem:" + idempotencyKey);
                    return Optional.empty();
                }
                // Still processing -- caller should wait
                return Optional.of(PaymentResult.pending());
            }

            return Optional.of(PaymentResult.fromJson(value));
        }
    }

    @Override
    public void store(String idempotencyKey, PaymentResult result) {
        try (Jedis jedis = jedisPool.getResource()) {
            // XX: only set if key exists (update PENDING -> result)
            jedis.set(
                "idem:" + idempotencyKey,
                result.toJson(),
                SetParams.setParams().xx().ex(TTL_SECONDS)
            );
        }
    }

    @Override
    public void remove(String idempotencyKey) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del("idem:" + idempotencyKey);
        }
    }
}
```

---

## Exchange Rate Cache: Cache-Aside with 5-Minute TTL

### Architecture

```
  +----------------------------------------------------------------------+
  |              EXCHANGE RATE CACHING                                     |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  PATTERN: Cache-Aside (Lazy Loading)                                 |
  |  TTL: 5 minutes                                                      |
  |  SOURCE: Forex API (Open Exchange Rates, Fixer.io)                   |
  |                                                                      |
  |  CurrencyService       Redis                  Forex API              |
  |       |                  |                       |                   |
  |       | (1) GET          |                       |                   |
  |       |  exchange:       |                       |                   |
  |       |  USD_INR         |                       |                   |
  |       |----------------->|                       |                   |
  |       |                  |                       |                   |
  |       |  [CACHE HIT]     |                       |                   |
  |       |  "83.50"         |                       |                   |
  |       |<-----------------|                       |                   |
  |       |                  |                       |                   |
  |       |  Return 83.50    |                       |                   |
  |       |  (no API call)   |                       |                   |
  |       |                  |                       |                   |
  |  --- 5 minutes later, TTL expires ---            |                   |
  |       |                  |                       |                   |
  |       | (2) GET          |                       |                   |
  |       |  exchange:       |                       |                   |
  |       |  USD_INR         |                       |                   |
  |       |----------------->|                       |                   |
  |       |                  |                       |                   |
  |       |  [CACHE MISS]    |                       |                   |
  |       |  NIL             |                       |                   |
  |       |<-----------------|                       |                   |
  |       |                  |                       |                   |
  |       | (3) GET /latest  |                       |                   |
  |       |  ?base=USD       |                       |                   |
  |       |----------------------------------------->|                   |
  |       |  {"USD_INR":     |                       |                   |
  |       |   83.72}         |                       |                   |
  |       |<-----------------------------------------|                   |
  |       |                  |                       |                   |
  |       | (4) SET          |                       |                   |
  |       |  exchange:       |                       |                   |
  |       |  USD_INR         |                       |                   |
  |       |  "83.72"         |                       |                   |
  |       |  EX 300          |                       |                   |
  |       |----------------->|                       |                   |
  |       |                  |                       |                   |
  |       |  Return 83.72    |                       |                   |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Critical: Lock Rate at Authorization Time

```
  +----------------------------------------------------------------------+
  |              EXCHANGE RATE LOCKING                                     |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  PROBLEM: Rate changes between authorization and settlement.         |
  |                                                                      |
  |  Time T1 (authorization): USD/INR = 83.50                            |
  |    Customer authorized to pay INR 8,350 for $100 purchase.           |
  |                                                                      |
  |  Time T2 (settlement, 2 days later): USD/INR = 84.00                 |
  |    If we re-convert: INR 8,350 / 84.00 = $99.40                     |
  |    Merchant receives $0.60 less than expected!                       |
  |                                                                      |
  |  SOLUTION: Lock the exchange rate at authorization time.             |
  |                                                                      |
  |  Payment record stores:                                              |
  |  +---------------------------------------------------------------+  |
  |  | payment_id: PAY-001                                           |  |
  |  | amount: 8350.00                                               |  |
  |  | currency: INR                                                 |  |
  |  | settlement_amount: 100.00                                     |  |
  |  | settlement_currency: USD                                      |  |
  |  | exchange_rate: 83.50        <-- LOCKED at authorization time  |  |
  |  | exchange_rate_locked_at: 2024-01-15T10:30:00Z                 |  |
  |  +---------------------------------------------------------------+  |
  |                                                                      |
  |  At settlement: use the LOCKED rate, not the current rate.           |
  |  Cache is ONLY used for display/estimation, NEVER for settlement.    |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Implementation

```java
public class CurrencyService {
    private static class Holder {
        private static final CurrencyService INSTANCE = new CurrencyService();
    }

    private final Map<String, BigDecimal> exchangeRates = new ConcurrentHashMap<>();
    private Instant lastUpdated;
    private static final long CACHE_TTL_MS = 300_000; // 5 minutes

    public static CurrencyService getInstance() {
        return Holder.INSTANCE;
    }

    public BigDecimal getRate(Currency from, Currency to) {
        String key = from.name() + "_" + to.name();

        // (1) Check in-memory cache (or Redis in production)
        BigDecimal cached = exchangeRates.get(key);
        if (cached != null && !isCacheExpired()) {
            return cached;
        }

        // (2) Cache miss -- refresh from API
        synchronized (this) {
            // Double-check after acquiring lock
            if (isCacheExpired()) {
                refreshRates();
            }
        }

        return exchangeRates.get(key);
    }

    public ExchangeRateLock lockRate(Currency from, Currency to) {
        // (1) Get current rate (from cache or API)
        BigDecimal rate = getRate(from, to);

        // (2) Return a lock object -- this rate is frozen for this payment
        return new ExchangeRateLock(from, to, rate, Instant.now());
    }

    private boolean isCacheExpired() {
        return lastUpdated == null ||
            Duration.between(lastUpdated, Instant.now()).toMillis() > CACHE_TTL_MS;
    }
}
```

---

## Merchant Config Cache: Read-Through

### Architecture

```
  +----------------------------------------------------------------------+
  |              MERCHANT CONFIG CACHING                                   |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  PATTERN: Read-Through Cache                                         |
  |  TTL: 10 minutes                                                     |
  |  DATA: webhook URL, API version, notification preferences            |
  |                                                                      |
  |  Read-Through vs Cache-Aside:                                        |
  |  +---------------------------------------------------------------+  |
  |  | Cache-Aside: caller checks cache, on miss calls DB, fills     |  |
  |  |   cache. Caller manages the cache.                            |  |
  |  |                                                               |  |
  |  | Read-Through: cache itself loads from DB on miss. Caller      |  |
  |  |   only talks to cache, never directly to DB.                  |  |
  |  |                                                               |  |
  |  | We use Read-Through for merchant config because:              |  |
  |  | - Multiple services need merchant data (WebhookService,       |  |
  |  |   PaymentService, DashboardService)                           |  |
  |  | - Read-through centralizes DB access in one place             |  |
  |  | - No risk of different services having different cache logic   |  |
  |  +---------------------------------------------------------------+  |
  |                                                                      |
  |  WebhookService        MerchantCache          PostgreSQL             |
  |       |                     |                      |                 |
  |       | (1) getMerchant     |                      |                 |
  |       |  ("MERCH-100")      |                      |                 |
  |       |-------------------->|                      |                 |
  |       |                     |                      |                 |
  |       |                     | [CACHE HIT]          |                 |
  |       |                     | Return cached        |                 |
  |       |  Merchant{          | merchant             |                 |
  |       |   webhookUrl: ...,  |                      |                 |
  |       |   apiVersion: v2}   |                      |                 |
  |       |<--------------------|                      |                 |
  |       |                     |                      |                 |
  |  --- 10 minutes later ---   |                      |                 |
  |       |                     |                      |                 |
  |       | (2) getMerchant     |                      |                 |
  |       |  ("MERCH-100")      |                      |                 |
  |       |-------------------->|                      |                 |
  |       |                     |                      |                 |
  |       |                     | [CACHE MISS]         |                 |
  |       |                     |                      |                 |
  |       |                     | (3) SELECT * FROM    |                 |
  |       |                     |  merchants WHERE     |                 |
  |       |                     |  id = 'MERCH-100'    |                 |
  |       |                     |--------------------->|                 |
  |       |                     |  Merchant row        |                 |
  |       |                     |<---------------------|                 |
  |       |                     |                      |                 |
  |       |                     | (4) Cache merchant   |                 |
  |       |                     |  with 10-min TTL     |                 |
  |       |                     |                      |                 |
  |       |  Merchant{          |                      |                 |
  |       |   webhookUrl: ...,  |                      |                 |
  |       |   apiVersion: v2}   |                      |                 |
  |       |<--------------------|                      |                 |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Why Read-Through for Merchant, Cache-Aside for Exchange Rates

```
  +----------------------------------------------------------------------+
  |              CACHE PATTERN SELECTION                                   |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  +-------------------+-----------------------+---------------------+ |
  |  | Data              | Pattern               | Why                 | |
  |  +-------------------+-----------------------+---------------------+ |
  |  | Exchange Rates    | Cache-Aside           | CurrencyService is  | |
  |  |                   | (caller manages cache) | the only consumer. | |
  |  |                   |                       | It knows when to    | |
  |  |                   |                       | refresh (5-min TTL).| |
  |  +-------------------+-----------------------+---------------------+ |
  |  | Merchant Config   | Read-Through          | Multiple consumers  | |
  |  |                   | (cache manages DB)    | (Webhook, Payment,  | |
  |  |                   |                       | Dashboard). Central | |
  |  |                   |                       | cache prevents      | |
  |  |                   |                       | inconsistent logic. | |
  |  +-------------------+-----------------------+---------------------+ |
  |  | Idempotency Keys  | Write-Behind is WRONG!| Must be synchronous.| |
  |  |                   | Direct write to Redis  | SET NX must be     | |
  |  |                   | + DB backup            | atomic and instant.| |
  |  +-------------------+-----------------------+---------------------+ |
  |                                                                      |
  +----------------------------------------------------------------------+
```

---

## Why Ledger is NEVER Cached

### The Financial Accuracy Argument

```
  +----------------------------------------------------------------------+
  |              WHY THE LEDGER IS NEVER CACHED                           |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  SCENARIO: Ledger entry cached in Redis, stale for 2 seconds.       |
  |                                                                      |
  |  Time T0: Ledger in DB: Customer balance = $500                      |
  |           Ledger in Cache: Customer balance = $500 (fresh)           |
  |                                                                      |
  |  Time T1: Payment of $450 processed.                                 |
  |           DB updated: balance = $50                                   |
  |           Cache NOT updated yet (write-behind, 2s lag)               |
  |                                                                      |
  |  Time T2 (1 second later): Another payment request for $100.         |
  |           Balance check from cache: $500 (STALE!)                    |
  |           $500 >= $100? YES -- approve payment.                      |
  |           DB balance: $50 - $100 = -$50. OVERDRAFT!                  |
  |                                                                      |
  |  WHO COVERS THE -$50?                                                |
  |  In real money, somebody loses $50. The platform? The merchant?      |
  |  This is why you NEVER cache financial balances.                     |
  |                                                                      |
  |  CORRECT APPROACH:                                                   |
  |  +---------------------------------------------------------------+  |
  |  | SELECT balance FROM accounts                                  |  |
  |  | WHERE account_id = 'CUST-001'                                 |  |
  |  | FOR UPDATE;   -- row-level lock, prevents concurrent reads    |  |
  |  |                                                               |  |
  |  | -- If balance >= payment amount: proceed                      |  |
  |  | -- If not: decline immediately                                |  |
  |  |                                                               |  |
  |  | UPDATE accounts SET balance = balance - 100                   |  |
  |  | WHERE account_id = 'CUST-001'                                 |  |
  |  | AND balance >= 100;   -- DB-level overdraft prevention        |  |
  |  |                                                               |  |
  |  | -- rows_affected = 0? Insufficient funds.                     |  |
  |  | -- rows_affected = 1? Payment approved.                       |  |
  |  +---------------------------------------------------------------+  |
  |                                                                      |
  |  THE PATTERN: SELECT FOR UPDATE + conditional UPDATE.                |
  |  No cache involved. Every balance check hits the DB.                 |
  |  Slower (5ms vs 0.5ms) but CORRECT. In payments, correctness > speed.|
  |                                                                      |
  +----------------------------------------------------------------------+
```

### What About Read Replicas?

```
  +----------------------------------------------------------------------+
  |              READ REPLICAS FOR LEDGER: BE CAREFUL                      |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  PostgreSQL read replicas have REPLICATION LAG (ms to seconds).      |
  |                                                                      |
  |  SAFE to read from replica:                                          |
  |  +---------------------------------------------------------------+  |
  |  | - Dashboard: "Show me last 30 days of transactions"           |  |
  |  | - Reporting: "Total revenue this month"                       |  |
  |  | - Merchant analytics: "Transaction count by day"              |  |
  |  |                                                               |  |
  |  | Why safe: these are DISPLAY queries. 1-second staleness is    |  |
  |  | invisible to the user. The dashboard refreshes every 30s.     |  |
  |  +---------------------------------------------------------------+  |
  |                                                                      |
  |  MUST read from primary:                                             |
  |  +---------------------------------------------------------------+  |
  |  | - Balance check for payment approval                          |  |
  |  | - Idempotency check (is this payment already processed?)      |  |
  |  | - Refund eligibility (current payment status)                 |  |
  |  | - Ledger balance verification (audit queries)                 |  |
  |  |                                                               |  |
  |  | Why: these are DECISION queries. Stale data = wrong decision. |  |
  |  | Must use SELECT FOR UPDATE on primary.                        |  |
  |  +---------------------------------------------------------------+  |
  |                                                                      |
  +----------------------------------------------------------------------+
```

---

## Webhook Retry Queue: Redis Sorted Set

### Architecture

```
  +----------------------------------------------------------------------+
  |              WEBHOOK RETRY QUEUE (Redis ZSET)                         |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  DATA STRUCTURE: Redis Sorted Set (ZSET)                             |
  |  KEY: webhook:retry                                                  |
  |  SCORE: next retry timestamp (epoch seconds)                         |
  |  MEMBER: webhook delivery ID                                         |
  |                                                                      |
  |  WHY REDIS ZSET (not Kafka, not SQS):                               |
  |  - Need to fetch "all deliveries ready for retry NOW"               |
  |  - ZRANGEBYSCORE 0 {now} is O(log N + M) -- fast range query        |
  |  - Score = next retry time -- naturally sorted by urgency            |
  |  - Easy to update retry time (ZADD with new score)                   |
  |  - Kafka: no time-based selection (would need delay topics)          |
  |  - SQS: visibility timeout works but less flexible                   |
  |                                                                      |
  |  OPERATIONS:                                                         |
  |                                                                      |
  |  (1) Webhook delivery fails:                                         |
  |  ZADD webhook:retry {now + delay} {delivery_id}                      |
  |  -- Schedule retry at now + exponential backoff delay                 |
  |                                                                      |
  |  (2) Retry worker (runs every 10 seconds):                           |
  |  ZRANGEBYSCORE webhook:retry 0 {now} LIMIT 0 100                    |
  |  -- Fetch up to 100 deliveries whose retry time has passed           |
  |                                                                      |
  |  (3) Delivery succeeds:                                              |
  |  ZREM webhook:retry {delivery_id}                                    |
  |  -- Remove from retry queue                                          |
  |                                                                      |
  |  (4) Delivery fails again:                                           |
  |  ZADD webhook:retry {now + next_delay} {delivery_id}                 |
  |  -- Reschedule with increased delay                                  |
  |                                                                      |
  |  (5) Max retries exceeded:                                           |
  |  ZREM webhook:retry {delivery_id}                                    |
  |  -- Remove from queue, mark as FAILED in PostgreSQL                  |
  |  -- Alert on merchant dashboard                                      |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Retry Worker Flow

```
  +----------------------------------------------------------------------+
  |              RETRY WORKER FLOW                                        |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  RetryWorker            Redis                 WebhookService         |
  |  (runs every 10s)       (ZSET)                                       |
  |       |                   |                       |                  |
  |       | (1) ZRANGEBYSCORE |                       |                  |
  |       |  webhook:retry    |                       |                  |
  |       |  0 {now}          |                       |                  |
  |       |  LIMIT 0 100      |                       |                  |
  |       |------------------>|                       |                  |
  |       |                   |                       |                  |
  |       |  ["DEL-001",      |                       |                  |
  |       |   "DEL-007",      |                       |                  |
  |       |   "DEL-012"]      |                       |                  |
  |       |<------------------|                       |                  |
  |       |                   |                       |                  |
  |       | (2) For each delivery:                    |                  |
  |       |                   |                       |                  |
  |       |  attemptDelivery  |                       |                  |
  |       |  ("DEL-001")      |                       |                  |
  |       |-------------------------------------->|                      |
  |       |                   |                  |                       |
  |       |  [SUCCESS]        |                  |                       |
  |       |<--------------------------------------|                      |
  |       |                   |                       |                  |
  |       | (3) ZREM          |                       |                  |
  |       |  webhook:retry    |                       |                  |
  |       |  "DEL-001"        |                       |                  |
  |       |------------------>|                       |                  |
  |       |                   |                       |                  |
  |       |  attemptDelivery  |                       |                  |
  |       |  ("DEL-007")      |                       |                  |
  |       |-------------------------------------->|                      |
  |       |                   |                  |                       |
  |       |  [FAILED]         |                  |                       |
  |       |<--------------------------------------|                      |
  |       |                   |                       |                  |
  |       | (4) Check retry count                     |                  |
  |       |  DEL-007 attempt #3                       |                  |
  |       |  Next delay: 30 minutes                   |                  |
  |       |                   |                       |                  |
  |       | ZADD              |                       |                  |
  |       |  webhook:retry    |                       |                  |
  |       |  {now + 1800}     |                       |                  |
  |       |  "DEL-007"        |                       |                  |
  |       |------------------>|                       |                  |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Implementation

```java
public class WebhookRetryQueue {
    private final JedisPool jedisPool;
    private static final String RETRY_KEY = "webhook:retry";
    private static final int MAX_RETRIES = 7;

    // Exponential backoff delays in seconds
    private static final int[] RETRY_DELAYS = {
        0,       // attempt 1: immediate
        60,      // attempt 2: 1 minute
        300,     // attempt 3: 5 minutes
        1800,    // attempt 4: 30 minutes
        7200,    // attempt 5: 2 hours
        28800,   // attempt 6: 8 hours
        86400    // attempt 7: 24 hours
    };

    public void scheduleRetry(String deliveryId, int attemptNumber) {
        if (attemptNumber >= MAX_RETRIES) {
            // Max retries exceeded -- mark as permanently failed
            remove(deliveryId);
            // Update status in PostgreSQL: PERMANENTLY_FAILED
            return;
        }

        int delay = RETRY_DELAYS[Math.min(attemptNumber, RETRY_DELAYS.length - 1)];
        double nextRetryTime = System.currentTimeMillis() / 1000.0 + delay;

        try (Jedis jedis = jedisPool.getResource()) {
            jedis.zadd(RETRY_KEY, nextRetryTime, deliveryId);
        }
    }

    public List<String> fetchDueRetries(int limit) {
        try (Jedis jedis = jedisPool.getResource()) {
            double now = System.currentTimeMillis() / 1000.0;
            // Fetch deliveries whose retry time has passed
            return new ArrayList<>(
                jedis.zrangeByScore(RETRY_KEY, 0, now, 0, limit)
            );
        }
    }

    public void remove(String deliveryId) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.zrem(RETRY_KEY, deliveryId);
        }
    }
}
```

---

## Cache Failure Handling: What Happens When Redis Dies

```
  +----------------------------------------------------------------------+
  |              CACHE FAILURE HANDLING                                    |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Redis is a CRITICAL dependency for payments (idempotency).          |
  |  Here's what happens when it fails, per cache use case:              |
  |                                                                      |
  |  +----------------------------+------------------------------------+ |
  |  | Cache Use Case             | When Redis Dies                     | |
  |  +----------------------------+------------------------------------+ |
  |  | Idempotency Keys           | FAIL FAST: return 503.             | |
  |  |                            | Fallback: DB UNIQUE constraint.    | |
  |  |                            | NEVER process without idempotency. | |
  |  +----------------------------+------------------------------------+ |
  |  | Exchange Rates             | DEGRADE: use last known rates      | |
  |  |                            | from in-memory CurrencyService.    | |
  |  |                            | If no rates at all: fail the       | |
  |  |                            | cross-currency payment.            | |
  |  +----------------------------+------------------------------------+ |
  |  | Merchant Config            | DEGRADE: read directly from        | |
  |  |                            | PostgreSQL. Slower (10ms vs 1ms)   | |
  |  |                            | but correct. Payments continue.    | |
  |  +----------------------------+------------------------------------+ |
  |  | Webhook Retry Queue        | DEGRADE: retries pause. Webhook    | |
  |  |                            | delivery records are in PostgreSQL.| |
  |  |                            | Background job can query DB for    | |
  |  |                            | pending deliveries (slower).       | |
  |  +----------------------------+------------------------------------+ |
  |                                                                      |
  |  REDIS HIGH AVAILABILITY:                                            |
  |  +---------------------------------------------------------------+  |
  |  | Production setup: Redis Sentinel or Redis Cluster              |  |
  |  | - 3 Redis nodes (1 primary, 2 replicas)                       |  |
  |  | - Sentinel monitors and auto-failovers on primary failure     |  |
  |  | - Failover time: 5-30 seconds                                 |  |
  |  | - During failover: idempotency falls back to DB UNIQUE        |  |
  |  +---------------------------------------------------------------+  |
  |                                                                      |
  +----------------------------------------------------------------------+
```

---

## Cache Sizing and TTL Strategy

```
  +----------------------------------------------------------------------+
  |              CACHE SIZING AND TTL                                      |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  +-------------------+----------+-------------+--------------------+ |
  |  | Cache             | TTL      | Est. Size   | Eviction Policy    | |
  |  +-------------------+----------+-------------+--------------------+ |
  |  | Idempotency Keys  | 24 hours | ~200 bytes  | TTL auto-expiry    | |
  |  |                   |          | per key.    | (no manual evict)  | |
  |  |                   |          | 1M keys =   |                    | |
  |  |                   |          | ~200MB      |                    | |
  |  +-------------------+----------+-------------+--------------------+ |
  |  | Exchange Rates    | 5 min    | ~50 bytes   | TTL auto-expiry    | |
  |  |                   |          | per pair.   | + manual refresh   | |
  |  |                   |          | 100 pairs = |                    | |
  |  |                   |          | ~5KB        |                    | |
  |  +-------------------+----------+-------------+--------------------+ |
  |  | Merchant Config   | 10 min   | ~500 bytes  | LRU (maxmemory-   | |
  |  |                   |          | per merchant| policy allkeys-lru)| |
  |  |                   |          | 10K merch = |                    | |
  |  |                   |          | ~5MB        |                    | |
  |  +-------------------+----------+-------------+--------------------+ |
  |  | Webhook Retry Set | N/A      | ~100 bytes  | Explicit ZREM on   | |
  |  |                   | (managed)| per entry.  | success/max retry  | |
  |  |                   |          | 10K pending |                    | |
  |  |                   |          | = ~1MB      |                    | |
  |  +-------------------+----------+-------------+--------------------+ |
  |  | TOTAL             |          | ~210MB      |                    | |
  |  +-------------------+----------+-------------+--------------------+ |
  |                                                                      |
  |  SIZING NOTE: A single Redis instance handles 1M+ idempotency keys  |
  |  comfortably. The bottleneck is never Redis memory -- it's the DB    |
  |  transaction that follows the Redis check.                           |
  |                                                                      |
  +----------------------------------------------------------------------+
```

---

## Interview Q&A

### Q1: "Why cache idempotency keys in Redis instead of just using a DB UNIQUE constraint?"

> "Speed and atomicity. Redis SET NX is sub-millisecond and atomic -- it checks AND sets in one operation. A DB UNIQUE constraint requires an INSERT attempt, catching the duplicate key exception, and then querying for the existing result. That's 3 operations vs 1. For a payment system handling thousands of requests per second, this matters. But we still have the DB UNIQUE constraint as a safety net -- defense in depth."

### Q2: "What happens if your Redis cache and PostgreSQL disagree on idempotency?"

> "Redis is the fast path; PostgreSQL is the source of truth. If Redis says 'not seen' but PostgreSQL has the payment (Redis restarted and lost data), the DB UNIQUE constraint catches it. If Redis says 'seen' but PostgreSQL doesn't have it (stale PENDING from crashed request), the stale lock detection kicks in -- if PENDING for over 30 seconds, we delete the Redis key and retry. The two systems are eventually consistent with PostgreSQL winning."

### Q3: "Why is the ledger NEVER cached?"

> "Because a stale balance means approving payments that overdraft. If the cache says $500 but the DB has $50 (another payment just went through), we'd approve a $100 payment and the account goes to -$50. In a payment system, correctness is more important than speed. We use SELECT FOR UPDATE on the primary PostgreSQL instance for every balance check. It's 5ms instead of 0.5ms, but it's never wrong."

### Q4: "How do you handle exchange rate caching for cross-currency payments?"

> "Cache-aside with 5-minute TTL for display. But for actual payment processing, we LOCK the rate at authorization time and store it with the payment record. Settlement uses the locked rate, not the current rate. This way, even if the rate changes between authorization and settlement (could be days for card payments), the amounts are consistent."

### Q5: "Why Redis Sorted Set for webhook retries instead of Kafka?"

> "Webhook retries need time-based scheduling -- 'deliver this at T+30min'. Redis ZRANGEBYSCORE gives us exactly this: fetch all entries with score (retry time) less than now. Kafka doesn't support fetching messages by timestamp easily -- you'd need delay topics or a separate scheduler. Redis ZSET is purpose-built for this: O(log N) insert, O(log N + M) range query, and trivial reschedule by updating the score."

### Q6: "If Redis dies during a payment, what happens?"

> "For idempotency: we return 503 immediately. We NEVER process a payment without an idempotency check -- the risk of double-charge is too high. If SLA requires higher availability, we fall back to a DB UNIQUE constraint check (slower, 10ms vs 0.5ms, but correct). For exchange rates: we use the last known rate from the in-memory CurrencyService Singleton. For merchant config: we read directly from PostgreSQL. For webhook retries: retries pause until Redis is back, but delivery records are safely in PostgreSQL."
