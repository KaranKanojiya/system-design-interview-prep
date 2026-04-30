# Caching Strategies for the Stock Trading Platform (Zerodha/Upstox)

> Interview-ready reference for a Senior Java developer.
> Stock trading caching has a sharp divide: market data is cached aggressively
> (sub-second TTL, 50 lakh concurrent readers), but order state and margin
> are NEVER cached (stale data = financial risk). The order book is in-memory
> but is NOT a "cache" -- it IS the primary data structure during trading hours.
> The golden rule: **cache for DISPLAY, read from DB for DECISIONS.**

---

## Table of Contents

| Section | Key Point |
|---------|-----------|
| What to Cache vs Not | Market data = cache. Margin/order state = NEVER cache. |
| Market Data Cache | Redis HASH per symbol, 500ms TTL, sub-ms reads |
| Order Book (In-Memory) | TreeMap -- not a cache, it IS the source of truth |
| Portfolio Cache | Redis, invalidated on trade execution |
| Stock Master Data | Long TTL, loaded once at pre-market |
| What NOT to Cache | Order state, margin, live risk exposure |
| Pre-Market Cache Warming | Load stocks, previous close, circuit limits |
| Post-Market Persistence | Persist order book state, reconcile |
| Cache Failure Handling | What happens when Redis dies |
| Multi-Level Caching | L1 (local) + L2 (Redis) architecture |
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
  |  (speed optimization,                (stale = financial risk)        |
  |   stale is tolerable)                                                |
  |  ============================        =============================   |
  |                                                                      |
  |  +---------------------+             +---------------------+         |
  |  | Market Data (LTP)   |             | Order State          |         |
  |  | Redis HASH           |             | MUST read from DB   |         |
  |  | TTL: 500ms          |             | Stale order status = |         |
  |  | Purpose: display    |             | user sees wrong info,|         |
  |  | prices to 50 lakh   |             | double-cancel, or    |         |
  |  | concurrent users    |             | trades on cancelled  |         |
  |  +---------------------+             | order                |         |
  |                                      +---------------------+         |
  |  +---------------------+                                             |
  |  | Portfolio Summary   |             +---------------------+         |
  |  | Redis HASH           |             | Margin / Balance    |         |
  |  | TTL: none (event-   |             | MUST be real-time   |         |
  |  | invalidated on      |             | Stale margin =      |         |
  |  | trade execution)    |             | user trades beyond  |         |
  |  +---------------------+             | their means.        |         |
  |                                      | Broker absorbs loss.|         |
  |  +---------------------+             +---------------------+         |
  |  | Stock Master Data   |                                             |
  |  | Redis HASH + local  |             +---------------------+         |
  |  | TTL: 24 hours       |             | Live Risk Exposure  |         |
  |  | Symbol, lot size,   |             | MUST compute real-  |         |
  |  | tick size don't     |             | time from positions |         |
  |  | change intraday     |             | + open orders +     |         |
  |  +---------------------+             | market prices.      |         |
  |                                      | Stale exposure =    |         |
  |  +---------------------+             | risk breach.        |         |
  |  | Previous Day Close  |             +---------------------+         |
  |  | Redis STRING         |                                             |
  |  | TTL: until next      |             +---------------------+         |
  |  | market close        |             | Idempotency Keys    |         |
  |  | Used for %change    |             | Redis SET NX (this  |         |
  |  | calculation         |             | IS a cache but it's |         |
  |  +---------------------+             | authoritative, not  |         |
  |                                      | derived data)       |         |
  |  +---------------------+             +---------------------+         |
  |  | Circuit Limits      |                                             |
  |  | Redis HASH           |                                             |
  |  | TTL: until exchange |                                             |
  |  | revises (intraday   |                                             |
  |  | very rare)          |                                             |
  |  +---------------------+                                             |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### The Golden Rule

```
  +----------------------------------------------------------------------+
  |                                                                      |
  |  CACHE for DISPLAY:                                                  |
  |  - Market data on user's screen (LTP, bid/ask, volume)              |
  |  - Portfolio summary (holdings, unrealized P&L)                      |
  |  - Historical charts (OHLCV candles)                                 |
  |  - Watchlist prices                                                  |
  |                                                                      |
  |  READ FROM DB for DECISIONS:                                         |
  |  - Margin check before order placement                               |
  |  - Position check before risk validation                             |
  |  - Order status before cancel/modify                                 |
  |  - Account balance before settlement                                 |
  |                                                                      |
  |  WHY: A user seeing a 50ms stale LTP causes zero harm.               |
  |  A margin check using a 50ms stale balance can cause the broker      |
  |  to lose crores of rupees in a volatile market.                      |
  +----------------------------------------------------------------------+
```

---

## Market Data Cache -- Redis with Sub-Second TTL

### Architecture

```
  +----------------------------------------------------------------------+
  |  MARKET DATA CACHING ARCHITECTURE                                     |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Exchange (NSE/BSE)                                                  |
  |       |                                                              |
  |       | (1) Trade tick: RELIANCE @ 2490, qty=100                     |
  |       v                                                              |
  |  +-------------------+                                               |
  |  | Feed Handler      |  (2) Decode exchange binary format            |
  |  | (co-located)      |      Extract symbol, price, qty, timestamp    |
  |  +-------------------+                                               |
  |       |                                                              |
  |       | (3) Publish to Kafka: topic=market-data.ticks, key=RELIANCE  |
  |       v                                                              |
  |  +-------------------+                                               |
  |  | Market Data       |  (4) Consume from Kafka                       |
  |  | Service           |      Update Redis HASH:                       |
  |  |                   |      HSET market:RELIANCE ltp 2490            |
  |  |                   |           bid 2489 ask 2491                   |
  |  |                   |           volume 12450100                     |
  |  |                   |      EXPIRE market:RELIANCE 1                 |
  |  |                   |      (500ms in production, 1s for safety)     |
  |  +-------------------+                                               |
  |       |                                                              |
  |       | (5) WebSocket push to subscribed clients                     |
  |       v                                                              |
  |  +-------------------+                                               |
  |  | WebSocket Gateway |  (6) Fan-out to connected clients             |
  |  | (multiple nodes)  |      Only send symbols user is watching       |
  |  +-------------------+                                               |
  |       |                                                              |
  |       v                                                              |
  |  User sees updated price on screen                                   |
  |                                                                      |
  |  Cache read path (user opens app or refreshes):                      |
  |  (a) Client sends HTTP GET /market-data?symbols=RELIANCE,TCS        |
  |  (b) API server calls HGETALL market:RELIANCE, HGETALL market:TCS   |
  |  (c) Redis responds in < 0.5ms                                       |
  |  (d) API returns JSON to client                                      |
  |  (e) Subsequent updates via WebSocket (push, not poll)               |
  +----------------------------------------------------------------------+
```

### Redis Commands for Market Data

```
  +----------------------------------------------------------------------+
  |  REDIS COMMANDS -- MARKET DATA OPERATIONS                             |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  WRITE (by Market Data Service, on every tick):                      |
  |                                                                      |
  |  MULTI                                                               |
  |    HSET market:RELIANCE ltp 2490.50 bid 2490.00 ask 2491.00         |
  |         volume 12450100 high 2510.00 low 2475.00                     |
  |         timestamp 1714107330123                                      |
  |    PEXPIRE market:RELIANCE 500     -- 500ms TTL                      |
  |  EXEC                                                                |
  |                                                                      |
  |  Why MULTI? Atomic update -- no client reads half-updated data.      |
  |  Why PEXPIRE 500? If feed stops, data auto-expires in 500ms.         |
  |  Client sees "no data" instead of stale data.                        |
  |                                                                      |
  |  READ (by API server, on client request):                            |
  |                                                                      |
  |  HGETALL market:RELIANCE                                             |
  |  -> {"ltp":"2490.50","bid":"2490.00","ask":"2491.00",               |
  |      "volume":"12450100","high":"2510.00","low":"2475.00",          |
  |      "timestamp":"1714107330123"}                                    |
  |                                                                      |
  |  Latency: 0.1-0.5ms (Redis single-threaded, memory-only)            |
  |                                                                      |
  |  BATCH READ (user watching 20 symbols):                              |
  |                                                                      |
  |  PIPELINE                                                            |
  |    HGETALL market:RELIANCE                                           |
  |    HGETALL market:TCS                                                |
  |    HGETALL market:INFY                                               |
  |    ... (20 commands)                                                 |
  |  END PIPELINE                                                        |
  |                                                                      |
  |  Latency: 0.5-1ms for 20 symbols (pipelining amortizes roundtrips)  |
  |                                                                      |
  |  PUBLISH (for WebSocket push):                                       |
  |                                                                      |
  |  PUBLISH channel:market:RELIANCE                                     |
  |    '{"ltp":2490.50,"bid":2490.00,"ask":2491.00,"vol":12450100}'     |
  |                                                                      |
  |  WebSocket gateways subscribe to PUBLISH channels.                   |
  |  Only relay symbols that the connected user is watching.             |
  +----------------------------------------------------------------------+
```

### TTL Strategy for Market Data

```
  +----------------------------------------------------------------------+
  |  MARKET DATA TTL STRATEGY                                             |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Data Field        | TTL     | Why This TTL                          |
  |  ------------------+---------+---------------------------------------+
  |  LTP (Last Traded  | 500ms   | Ticks arrive every ~100ms for active |
  |  Price)            |         | stocks. 500ms = 5x safety margin.    |
  |                    |         | If no tick in 500ms, data expires     |
  |                    |         | and client shows "stale" indicator.   |
  |  ------------------+---------+---------------------------------------+
  |  Bid/Ask           | 500ms   | Changes with every order book update.|
  |                    |         | Same TTL as LTP for consistency.     |
  |  ------------------+---------+---------------------------------------+
  |  Volume            | 500ms   | Updated on every trade. Part of the  |
  |                    |         | same HASH, same TTL.                 |
  |  ------------------+---------+---------------------------------------+
  |  Day High/Low      | 500ms   | Part of same HASH. Could be longer   |
  |                    |         | but simpler to keep uniform.         |
  |  ------------------+---------+---------------------------------------+
  |  Previous Close    | 24h     | Does not change during trading.      |
  |                    |         | Set during pre-market cache warming. |
  |  ------------------+---------+---------------------------------------+
  |  Circuit Limits    | 24h     | Set by exchange pre-market. Rarely   |
  |                    |         | revised intraday (only on extreme    |
  |                    |         | volatility -- exchange notifies).    |
  |  ------------------+---------+---------------------------------------+
  |  52-Week High/Low  | 1h      | Changes rarely. Refresh periodically.|
  |  ------------------+---------+---------------------------------------+
  |                                                                      |
  |  KEY INSIGHT: The 500ms TTL is a SAFETY NET, not the primary         |
  |  freshness mechanism. The primary mechanism is the Kafka consumer     |
  |  that updates Redis on every tick (every ~100ms). The TTL is          |
  |  there to handle the case where the feed stops entirely -- data      |
  |  auto-expires instead of becoming silently stale.                    |
  +----------------------------------------------------------------------+
```

---

## Order Book -- In-Memory, NOT a Cache

### Why the Order Book is NOT a Cache

```
  +----------------------------------------------------------------------+
  |  ORDER BOOK: THE PRIMARY DATA STRUCTURE, NOT A CACHE                  |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  CRITICAL DISTINCTION:                                               |
  |                                                                      |
  |  A cache is a COPY of authoritative data stored elsewhere.           |
  |  The order book is the AUTHORITATIVE data during trading hours.      |
  |                                                                      |
  |  +------------------------------------------------------------------+|
  |  | Property          | Cache                | Order Book             ||
  |  +-------------------+----------------------+------------------------+|
  |  | Source of truth?  | No. DB is truth.     | YES during trading.    ||
  |  | Can be rebuilt?   | Yes, from DB.        | Only from WAL replay.  ||
  |  | TTL?              | Yes, expires.         | No. Lives until market ||
  |  |                   |                      | close or order cancel. ||
  |  | Stale OK?         | Sometimes.           | NEVER. It IS the      ||
  |  |                   |                      | current state.         ||
  |  | On miss?          | Read from DB.        | N/A -- no "miss".     ||
  |  |                   |                      | If order not in book,  ||
  |  |                   |                      | it doesn't exist.      ||
  |  +-------------------+----------------------+------------------------+|
  |                                                                      |
  |  During trading hours:                                               |
  |  - Order arrives -> added to in-memory OrderBook                     |
  |  - Match happens -> in-memory OrderBook is mutated                   |
  |  - The DB is a BACKUP, not the source of truth for matching          |
  |  - Matching NEVER reads from DB -- only from the in-memory book      |
  |                                                                      |
  |  After trading hours:                                                |
  |  - OrderBook state persisted to DB                                   |
  |  - Next day: rebuilt from DB + any pending orders                    |
  |                                                                      |
  |  On crash:                                                           |
  |  - Replay WAL (write-ahead log) to rebuild order book                |
  |  - Every order placed is logged to WAL BEFORE matching               |
  |  - Replay is deterministic (single-threaded, same order = same result)|
  +----------------------------------------------------------------------+
```

### Order Book Memory Layout

```
  +----------------------------------------------------------------------+
  |  ORDER BOOK MEMORY STRUCTURE                                          |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Per symbol (e.g., RELIANCE):                                        |
  |                                                                      |
  |  TreeMap<Double, PriceLevel> bids (reverse order)                    |
  |  +-------+----+-----------------------------------------------+     |
  |  | Price  | Qty| Orders (FIFO Deque<Order>)                    |     |
  |  +-------+----+-----------------------------------------------+     |
  |  | 2500  | 300| [O-1(100,09:15:01), O-5(200,09:15:03)]        |     |
  |  | 2499.5| 500| [O-7(200,09:15:02), O-8(150,09:15:04),        |     |
  |  |       |    |  O-9(150,09:15:05)]                            |     |
  |  | 2498  | 100| [O-12(100,09:15:06)]                           |     |
  |  +-------+----+-----------------------------------------------+     |
  |                                                                      |
  |  TreeMap<Double, PriceLevel> asks (natural order)                    |
  |  +-------+----+-----------------------------------------------+     |
  |  | 2501  | 150| [O-3(100,09:15:01), O-6(50,09:15:02)]         |     |
  |  | 2502  | 200| [O-4(200,09:15:03)]                           |     |
  |  | 2505  | 400| [O-10(250,09:15:04), O-11(150,09:15:05)]      |     |
  |  +-------+----+-----------------------------------------------+     |
  |                                                                      |
  |  Memory estimate per symbol:                                         |
  |  - Average 100 price levels * 2 sides = 200 TreeMap entries          |
  |  - Each entry: ~64 bytes (key + pointer + RB tree overhead)          |
  |  - Average 10 orders per level = 2000 orders                         |
  |  - Each order: ~200 bytes                                            |
  |  - Total: ~200 * 64 + 2000 * 200 = ~413 KB per symbol               |
  |  - 5000 symbols: ~2 GB total                                        |
  |  - Fits easily in memory of a modern server (64-256 GB RAM)          |
  |                                                                      |
  |  In production (NSE):                                                |
  |  - ~5000 actively traded symbols                                     |
  |  - Peak: millions of resting orders across all symbols               |
  |  - Total memory: 10-50 GB (with optimized C++ structures)           |
  +----------------------------------------------------------------------+
```

---

## Portfolio Cache -- Redis with Event-Driven Invalidation

### Architecture

```
  +----------------------------------------------------------------------+
  |  PORTFOLIO CACHING STRATEGY                                           |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Cache Strategy: Write-Through Invalidation                          |
  |  - On trade execution: invalidate portfolio cache                    |
  |  - On next read: recompute from DB, cache result                     |
  |  - No TTL: only invalidated on trade events                          |
  |                                                                      |
  |  Write path (trade executed):                                        |
  |                                                                      |
  |  MatchingEngine                                                      |
  |       |                                                              |
  |       | (1) Trade: Buy 100 RELIANCE @ 2490                           |
  |       v                                                              |
  |  +-------------------+                                               |
  |  | Settlement        |  (2) Update PostgreSQL:                       |
  |  | Service           |      positions SET qty=600 WHERE user='u1'    |
  |  |                   |      AND symbol='RELIANCE'                    |
  |  +-------------------+                                               |
  |       |                                                              |
  |       | (3) Invalidate cache:                                        |
  |       |     DEL portfolio:user-1                                     |
  |       v                                                              |
  |  [Redis] portfolio:user-1 -> DELETED                                |
  |                                                                      |
  |  Read path (user opens portfolio):                                   |
  |                                                                      |
  |  User App                                                            |
  |       |                                                              |
  |       | (a) GET /portfolio                                           |
  |       v                                                              |
  |  +-------------------+                                               |
  |  | API Server        |  (b) HGETALL portfolio:user-1                 |
  |  |                   |      -> MISS (cache was invalidated)          |
  |  +-------------------+                                               |
  |       |                                                              |
  |       | (c) Query PostgreSQL:                                        |
  |       |     SELECT * FROM positions WHERE user_id = 'user-1'        |
  |       v                                                              |
  |  +-------------------+                                               |
  |  | PostgreSQL        |  (d) Returns: RELIANCE(600, avg=2455),       |
  |  |                   |               TCS(300, avg=3800)              |
  |  +-------------------+                                               |
  |       |                                                              |
  |       | (e) Enrich with current prices from market data cache:       |
  |       |     HGET market:RELIANCE ltp -> 2490                        |
  |       |     HGET market:TCS ltp -> 3890                             |
  |       v                                                              |
  |  Compute unrealized P&L:                                             |
  |  RELIANCE: (2490 - 2455) * 600 = +21,000                            |
  |  TCS:      (3890 - 3800) * 300 = +27,000                            |
  |  Total unrealized P&L: +48,000                                      |
  |       |                                                              |
  |       | (f) Cache result:                                            |
  |       |     HSET portfolio:user-1                                    |
  |       |       RELIANCE '{"qty":600,"avg":2455,"pnl":21000}'         |
  |       |       TCS '{"qty":300,"avg":3800,"pnl":27000}'              |
  |       |       total_pnl '48000'                                     |
  |       v                                                              |
  |  Return enriched portfolio to user                                   |
  |                                                                      |
  |  IMPORTANT: The cached P&L becomes stale as market prices move.      |
  |  Options:                                                            |
  |  (1) Recompute every 5 seconds (timer-based refresh) -- simple       |
  |  (2) Push updated P&L via WebSocket on every price tick -- real-time |
  |  (3) Client computes P&L locally from cached positions + live LTP    |
  |      -> Zerodha does this (Kite sends positions, client computes)    |
  +----------------------------------------------------------------------+
```

### Numbered Call Chain -- Portfolio Cache Invalidation

```
1.  Trade executed: Buy 100 RELIANCE @ 2490 for user-1
2.  SettlementService updates PostgreSQL: position qty 500 -> 600
3.  SettlementService publishes Kafka event: trades.executed
4.  CacheInvalidationConsumer reads event from Kafka
5.  DEL portfolio:user-1 (invalidate entire portfolio cache for user)
6.  User-1 opens portfolio on Kite app
7.  API server: HGETALL portfolio:user-1 -> MISS
8.  Query PostgreSQL for positions -> RELIANCE(600), TCS(300)
9.  HGET market:RELIANCE ltp -> 2490
10. HGET market:TCS ltp -> 3890
11. Compute P&L, cache result in portfolio:user-1
12. Return enriched portfolio to user
```

---

## Stock Master Data -- Long TTL Cache

### What Gets Cached

```
  +----------------------------------------------------------------------+
  |  STOCK MASTER DATA CACHE                                              |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Stock master data changes VERY rarely (symbol additions,            |
  |  lot size changes, exchange holidays). Safe to cache for 24 hours.   |
  |                                                                      |
  |  Redis structure:                                                    |
  |                                                                      |
  |  Key: stock:{symbol}                                                 |
  |  Type: HASH                                                          |
  |  TTL: 86400 (24 hours)                                               |
  |                                                                      |
  |  HSET stock:RELIANCE                                                 |
  |    name        "Reliance Industries Ltd"                             |
  |    exchange    "NSE"                                                 |
  |    lot_size    1                                                     |
  |    tick_size   0.05                                                  |
  |    upper_circuit 2800.00                                             |
  |    lower_circuit 2200.00                                             |
  |    prev_close  2470.00                                               |
  |    isin        "INE002A01018"                                        |
  |    sector      "Oil & Gas"                                           |
  |                                                                      |
  |  What does NOT change intraday:                                      |
  |  +------------------------------------------------------------------+|
  |  | Field          | Change Frequency     | Safe TTL                 ||
  |  +----------------+----------------------+--------------------------+|
  |  | symbol         | Never                | Infinite                 ||
  |  | name           | Never                | Infinite                 ||
  |  | exchange       | Never                | Infinite                 ||
  |  | lot_size       | Quarterly (F&O only) | 24 hours                 ||
  |  | tick_size      | Very rare            | 24 hours                 ||
  |  | isin           | Never                | Infinite                 ||
  |  | sector         | Very rare            | 24 hours                 ||
  |  +----------------+----------------------+--------------------------+|
  |                                                                      |
  |  What DOES change intraday (separate cache):                         |
  |  +------------------------------------------------------------------+|
  |  | Field          | Change Frequency     | Cache Strategy           ||
  |  +----------------+----------------------+--------------------------+|
  |  | upper_circuit  | Set pre-market,      | Cache at pre-market,     ||
  |  |                | revised on index     | listen for exchange      ||
  |  |                | circuit trigger       | revision events          ||
  |  +----------------+----------------------+--------------------------+|
  |  | lower_circuit  | Same as above        | Same as above            ||
  |  +----------------+----------------------+--------------------------+|
  |  | prev_close     | Set at market close  | Cache once, TTL=24h      ||
  |  +----------------+----------------------+--------------------------+|
  |  | trading_halted | Rare (extreme events)| NOT cached -- real-time  ||
  |  |                |                      | from exchange            ||
  |  +----------------+----------------------+--------------------------+|
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Local Cache (L1) for Stock Data

```
  +----------------------------------------------------------------------+
  |  L1 LOCAL CACHE FOR STOCK MASTER DATA                                 |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Why L1 (in-process) + L2 (Redis):                                   |
  |  - Stock data is read on EVERY order (risk check needs circuit       |
  |    limits, lot size for validation)                                   |
  |  - Redis roundtrip: 0.5ms. Acceptable but adds up under load.        |
  |  - Local ConcurrentHashMap: < 0.001ms. 500x faster.                  |
  |  - Stock data rarely changes -> safe to cache locally for minutes    |
  |                                                                      |
  |  Implementation:                                                     |
  |                                                                      |
  |  public class StockCache {                                           |
  |      // L1: in-process cache (ConcurrentHashMap)                     |
  |      private final Map<String, Stock> localCache =                   |
  |          new ConcurrentHashMap<>();                                  |
  |      private volatile long lastRefreshed = 0;                        |
  |      private static final long REFRESH_INTERVAL = 60_000; // 1 min  |
  |                                                                      |
  |      // L2: Redis                                                    |
  |      private final RedisClient redis;                                |
  |                                                                      |
  |      // L3: PostgreSQL (source of truth)                             |
  |      private final StockRepository stockRepository;                  |
  |                                                                      |
  |      public Stock getStock(String symbol) {                          |
  |          // L1 hit: < 0.001ms                                        |
  |          Stock stock = localCache.get(symbol);                       |
  |          if (stock != null && !isRefreshNeeded()) {                  |
  |              return stock;                                           |
  |          }                                                           |
  |                                                                      |
  |          // L2 hit: ~0.5ms                                           |
  |          stock = redis.hgetall("stock:" + symbol);                   |
  |          if (stock != null) {                                        |
  |              localCache.put(symbol, stock);                          |
  |              return stock;                                           |
  |          }                                                           |
  |                                                                      |
  |          // L3 hit: ~2-5ms                                           |
  |          stock = stockRepository.findBySymbol(symbol);               |
  |          redis.hset("stock:" + symbol, stock.toMap());               |
  |          redis.expire("stock:" + symbol, 86400);                     |
  |          localCache.put(symbol, stock);                              |
  |          return stock;                                               |
  |      }                                                               |
  |  }                                                                   |
  |                                                                      |
  |  Read latency by cache level:                                        |
  |  +--------+----------+----------------------------------------------+|
  |  | Level  | Latency  | When                                         ||
  |  +--------+----------+----------------------------------------------+|
  |  | L1     | < 0.001ms| 99% of reads (stock data rarely changes)     ||
  |  | L2     | ~0.5ms   | After L1 refresh or first read               ||
  |  | L3     | ~2-5ms   | Cold start or after L2 TTL expiry            ||
  |  +--------+----------+----------------------------------------------+|
  +----------------------------------------------------------------------+
```

---

## What NOT to Cache -- Critical Anti-Patterns

### Order State -- NEVER Cache

```
  +----------------------------------------------------------------------+
  |  ORDER STATE: ALWAYS READ FROM DB                                     |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  WHY NOT CACHE:                                                      |
  |                                                                      |
  |  Scenario: User cancels order. Cache still shows OPEN.               |
  |                                                                      |
  |  User                 Cache (stale)        DB (truth)                |
  |    |                     |                     |                      |
  |    | Cancel ORD-001      |                     |                      |
  |    |-------------------->|                     |                      |
  |    |                     |  OPEN (stale!)      |  CANCELLED           |
  |    |                     |                     |                      |
  |    | Meanwhile, matching engine tries to fill ORD-001:               |
  |    |                                                                 |
  |    | MatchingEngine reads cache: status=OPEN -> proceed with match   |
  |    | But DB says CANCELLED -> trade should NOT happen                |
  |    | Result: INVALID TRADE on a cancelled order                      |
  |    | User sued, broker fined.                                        |
  |                                                                      |
  |  SOLUTION: MatchingEngine reads from in-memory OrderBook             |
  |  (not a cache -- it IS the source of truth during trading).          |
  |  Cancel removes the order from the OrderBook atomically.             |
  |  API reads order status from PostgreSQL for display.                 |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Margin / Balance -- NEVER Cache

```
  +----------------------------------------------------------------------+
  |  MARGIN: ALWAYS READ FROM DB (or computed real-time)                  |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  WHY NOT CACHE:                                                      |
  |                                                                      |
  |  Scenario: User has 100,000 margin. Places 5 orders worth 20,000    |
  |  each. Cache shows 100,000 for all 5 risk checks.                   |
  |                                                                      |
  |  Order 1: margin check -> cache says 100,000 >= 20,000 -> PASS      |
  |  Order 2: margin check -> cache says 100,000 >= 20,000 -> PASS      |
  |  Order 3: margin check -> cache says 100,000 >= 20,000 -> PASS      |
  |  Order 4: margin check -> cache says 100,000 >= 20,000 -> PASS      |
  |  Order 5: margin check -> cache says 100,000 >= 20,000 -> PASS      |
  |                                                                      |
  |  All 5 pass! Total committed: 100,000.                               |
  |  But the user only has 100,000 total.                                |
  |  If all 5 fill: user owes 100,000 but only had 100,000.             |
  |  Margin should have been 100K -> 80K -> 60K -> 40K -> 20K -> 0K.    |
  |                                                                      |
  |  Even worse: in volatile market, prices gap. Orders fill at worse    |
  |  prices. User's loss exceeds margin. Broker absorbs difference.     |
  |                                                                      |
  |  SOLUTION:                                                           |
  |  - Margin check ALWAYS reads from PostgreSQL                         |
  |  - Block margin ATOMICALLY within the same DB transaction as order   |
  |  - SELECT FOR UPDATE on account row (pessimistic locking)            |
  |  - Or use CHECK constraint: available_margin >= 0                    |
  +----------------------------------------------------------------------+
```

---

## Pre-Market Cache Warming

### What Happens Before Market Opens (9:00 AM - 9:15 AM)

```
  +----------------------------------------------------------------------+
  |  PRE-MARKET CACHE WARMING SEQUENCE                                    |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Time      | Action                                                  |
  |  ----------+----------------------------------------------------------+
  |  8:30 AM   | (1) Load ALL stock master data from PostgreSQL          |
  |            |     -> HSET stock:{symbol} for each of ~5000 stocks     |
  |            |     -> Populate L1 local cache                          |
  |            |     Why: First risk check must not wait for DB read     |
  |  ----------+----------------------------------------------------------+
  |  8:35 AM   | (2) Load previous close prices                          |
  |            |     -> SET prevclose:{symbol} for each stock            |
  |            |     Why: Needed for % change calculation from first tick|
  |  ----------+----------------------------------------------------------+
  |  8:40 AM   | (3) Load circuit limits from exchange file              |
  |            |     -> HSET stock:{symbol} upper_circuit lower_circuit  |
  |            |     Why: CircuitBreakerStrategy needs limits for the    |
  |            |     very first order at 9:15:00                         |
  |  ----------+----------------------------------------------------------+
  |  8:45 AM   | (4) Rebuild order books from pending overnight orders   |
  |            |     -> GTT (Good Till Triggered) orders                 |
  |            |     -> AMO (After Market Orders) placed previous night  |
  |            |     These go directly into the OrderBook (in-memory)    |
  |  ----------+----------------------------------------------------------+
  |  8:50 AM   | (5) Load account data for active users                  |
  |            |     -> Margin, positions for users with pending orders  |
  |            |     Why: First margin check must not wait for DB read   |
  |  ----------+----------------------------------------------------------+
  |  9:00 AM   | (6) Exchange pre-open session starts                    |
  |            |     -> Exchange sends indicative prices                 |
  |            |     -> Market data cache receives first ticks           |
  |  ----------+----------------------------------------------------------+
  |  9:08 AM   | (7) Exchange calculates opening price (call auction)    |
  |            |     -> Opening price = price that maximizes volume      |
  |  ----------+----------------------------------------------------------+
  |  9:15 AM   | (8) MARKET OPENS -- orders flow, matching begins        |
  |            |     All caches warm. First order served from cache.     |
  |  ----------+----------------------------------------------------------+
  |                                                                      |
  |  Total warming time: ~15 minutes (parallelize across services)       |
  |  If warming fails: delay market open for that service (critical)     |
  +----------------------------------------------------------------------+
```

### Numbered Call Chain -- Pre-Market Warming

```
1.  Scheduler triggers CacheWarmingService.warmAll() at 8:30 AM
2.  Thread 1: StockRepository.findAll() -> 5000 stocks from PostgreSQL
3.  Thread 1: For each stock: HSET stock:{symbol} with all fields
4.  Thread 1: Populate StockCache.localCache (L1) with all stocks
5.  Thread 2: Load previous close from trades table (last trade per symbol yesterday)
6.  Thread 2: SET prevclose:{symbol} for each stock
7.  Thread 3: Parse exchange circuit limit file (received via FTP at 8:00 AM)
8.  Thread 3: Update stock objects with day's circuit limits
9.  Thread 3: HSET stock:{symbol} upper_circuit, lower_circuit
10. Thread 4: OrderRepository.findPendingOvernight() -> GTT and AMO orders
11. Thread 4: For each pending order: MatchingEngine.getOrderBook(symbol).addOrder(order)
12. Thread 5: AccountRepository.findActiveUsers() -> load margin data
13. Thread 5: Validate all AMO orders have sufficient margin
14. All threads complete by 9:00 AM -> system ready for pre-open
15. 9:15 AM: First user order hits already-warm cache -> < 1ms response
```

---

## Post-Market -- Persist and Reconcile

### What Happens After Market Close (3:30 PM)

```
  +----------------------------------------------------------------------+
  |  POST-MARKET SEQUENCE                                                 |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Time      | Action                                                  |
  |  ----------+----------------------------------------------------------+
  |  3:30 PM   | (1) Market closes. No new orders accepted.              |
  |            |     In-flight orders in matching engine complete.        |
  |  ----------+----------------------------------------------------------+
  |  3:35 PM   | (2) Persist order book state                            |
  |            |     For each symbol's OrderBook:                        |
  |            |     - Save all resting orders to PostgreSQL              |
  |            |     - These become input for next day's pre-market      |
  |            |     - Status: OPEN or PARTIALLY_FILLED                  |
  |  ----------+----------------------------------------------------------+
  |  3:40 PM   | (3) Expire DAY orders                                   |
  |            |     All orders with TimeInForce=DAY:                     |
  |            |     - Remove from order book                            |
  |            |     - Update status to EXPIRED in PostgreSQL             |
  |            |     - Unfreeze blocked margin                           |
  |            |     - Notify users: "Your order expired unfilled"        |
  |  ----------+----------------------------------------------------------+
  |  3:45 PM   | (4) Reconciliation                                      |
  |            |     Compare broker records with exchange records:        |
  |            |     - Every trade in broker DB must exist in exchange DB |
  |            |     - Every trade in exchange DB must exist in broker DB |
  |            |     - Position net-off must match                       |
  |            |     - Margin utilized must match                        |
  |            |     Discrepancy -> alert compliance team                 |
  |  ----------+----------------------------------------------------------+
  |  4:00 PM   | (5) Generate end-of-day reports                         |
  |            |     - Trade file for exchange (regulatory)               |
  |            |     - P&L report per client                              |
  |            |     - Margin utilization report                          |
  |            |     - Brokerage revenue report                          |
  |  ----------+----------------------------------------------------------+
  |  4:30 PM   | (6) Clear volatile caches                               |
  |            |     - DEL market:* (market data no longer valid)        |
  |            |     - Portfolio cache kept (positions still valid)       |
  |            |     - Stock master cache kept (valid until next day)    |
  |  ----------+----------------------------------------------------------+
  |  5:00 PM   | (7) Backup and archive                                  |
  |            |     - PostgreSQL backup (daily)                          |
  |            |     - Kafka topics archived to S3 (retention policy)    |
  |            |     - Audit logs archived (5-year SEBI retention)       |
  |  ----------+----------------------------------------------------------+
  |                                                                      |
  +----------------------------------------------------------------------+
```

---

## Cache Failure Handling -- When Redis Dies

### What Happens When Redis is Unavailable

```
  +----------------------------------------------------------------------+
  |  CACHE FAILURE SCENARIOS                                              |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Scenario 1: Redis down -- market data cache unavailable             |
  |  ----------------------------------------------------------------   |
  |  Impact: Market data display is stale or unavailable                 |
  |  Action: Fall back to Kafka consumer pushing directly to WebSocket   |
  |  User sees: "Prices may be delayed" warning                         |
  |  Trading: CONTINUES (matching engine doesn't use Redis)              |
  |  Severity: MEDIUM (display degraded, trading unaffected)             |
  |                                                                      |
  |  Scenario 2: Redis down -- portfolio cache unavailable               |
  |  ----------------------------------------------------------------   |
  |  Impact: Portfolio reads go directly to PostgreSQL                   |
  |  Action: Every portfolio request hits DB (slower, but correct)       |
  |  User sees: Slightly slower portfolio page load (~5ms vs ~1ms)       |
  |  Trading: CONTINUES (portfolio cache is not in critical path)        |
  |  Severity: LOW (slower but functional)                               |
  |                                                                      |
  |  Scenario 3: Redis down -- idempotency cache unavailable             |
  |  ----------------------------------------------------------------   |
  |  Impact: Cannot dedup at cache layer                                 |
  |  Action: Fall back to PostgreSQL orderId primary key for dedup       |
  |  Risk: Slightly higher DB load, but correctness preserved            |
  |  Trading: CONTINUES with DB-level dedup                              |
  |  Severity: LOW (DB handles dedup, just slower)                       |
  |                                                                      |
  |  Scenario 4: Redis down -- stock master cache unavailable            |
  |  ----------------------------------------------------------------   |
  |  Impact: L1 local cache serves stock data                            |
  |  Action: If L1 also stale, read from PostgreSQL                     |
  |  Trading: CONTINUES (L1 is always populated during pre-market)       |
  |  Severity: LOW (L1 handles it, L3 as fallback)                      |
  |                                                                      |
  |  KEY INSIGHT:                                                        |
  |  Redis is NEVER in the critical path for order matching.             |
  |  The matching engine uses in-memory OrderBook, not Redis.            |
  |  Redis failure degrades display, not trading.                        |
  +----------------------------------------------------------------------+
```

### Redis High Availability Configuration

```
  +----------------------------------------------------------------------+
  |  REDIS HA FOR TRADING PLATFORM                                        |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Deployment: Redis Sentinel or Redis Cluster                         |
  |                                                                      |
  |  +-------------------+    +-------------------+                      |
  |  | Redis Primary     |    | Redis Replica 1   |                      |
  |  | (writes + reads)  |--->| (reads)           |                      |
  |  +-------------------+    +-------------------+                      |
  |         |                                                            |
  |         |                 +-------------------+                      |
  |         +--------------->| Redis Replica 2   |                      |
  |                           | (reads)           |                      |
  |                           +-------------------+                      |
  |                                                                      |
  |  +-------------------+                                               |
  |  | Sentinel 1        |  Monitors all Redis nodes.                    |
  |  | Sentinel 2        |  If primary fails: promote replica.           |
  |  | Sentinel 3        |  Failover time: ~5-15 seconds.               |
  |  +-------------------+                                               |
  |                                                                      |
  |  For market data (AP): read from ANY replica (stale OK)              |
  |  For idempotency (CP): read/write to PRIMARY only                   |
  |                                                                      |
  |  Configuration:                                                      |
  |  maxmemory: 16GB (per node)                                          |
  |  maxmemory-policy: volatile-ttl (evict keys with nearest expiry)    |
  |  Why volatile-ttl: market data has 500ms TTL (evict old ticks       |
  |  first). Stock master has 24h TTL (evicted last). No data loss.     |
  +----------------------------------------------------------------------+
```

---

## Multi-Level Caching Architecture

### Overview

```
  +----------------------------------------------------------------------+
  |  MULTI-LEVEL CACHE ARCHITECTURE                                       |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Level | Technology           | Latency    | Data Types              |
  |  ------+----------------------+------------+------------------------  |
  |  L0    | MatchingEngine       | 0 (inline) | OrderBook (THE source   |
  |        | (in-memory)          |            | of truth during trading) |
  |  ------+----------------------+------------+------------------------  |
  |  L1    | ConcurrentHashMap    | < 0.001ms  | Stock master, circuit   |
  |        | (in-process)         |            | limits, lot sizes       |
  |  ------+----------------------+------------+------------------------  |
  |  L2    | Redis                | 0.1-0.5ms  | Market data, portfolio, |
  |        | (network cache)      |            | stock master, dedup     |
  |  ------+----------------------+------------+------------------------  |
  |  L3    | PostgreSQL           | 2-10ms     | Orders, trades,         |
  |        | (source of truth)    |            | positions, accounts     |
  |  ------+----------------------+------------+------------------------  |
  |  L4    | TimescaleDB          | 5-50ms     | Historical OHLCV,       |
  |        | (time-series archive)|            | tick data               |
  |  ------+----------------------+------------+------------------------  |
  |                                                                      |
  |  Read path for market data:                                          |
  |  L2 (Redis) -> done. No fallback needed (data refreshed every tick). |
  |                                                                      |
  |  Read path for stock data:                                           |
  |  L1 (local) -> L2 (Redis) -> L3 (PostgreSQL)                        |
  |                                                                      |
  |  Read path for order matching:                                       |
  |  L0 (OrderBook) -> done. NEVER reads from Redis or DB for matching. |
  |                                                                      |
  |  Read path for portfolio:                                            |
  |  L2 (Redis) -> L3 (PostgreSQL) -> cache in L2 -> return             |
  |                                                                      |
  |  Read path for historical charts:                                    |
  |  L2 (Redis, recent candles) -> L4 (TimescaleDB) -> cache in L2      |
  +----------------------------------------------------------------------+
```

---

## Cache Sizing and Memory Budget

```
  +----------------------------------------------------------------------+
  |  CACHE MEMORY BUDGET                                                  |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Data Type              | Per Entry | Count    | Total Memory        |
  |  -----------------------+-----------+----------+--------------------  |
  |  Market Data (per       | ~200B     | 5000     | ~1 MB               |
  |  symbol HASH)           |           | symbols  |                     |
  |  -----------------------+-----------+----------+--------------------  |
  |  Stock Master (per      | ~300B     | 5000     | ~1.5 MB             |
  |  symbol HASH)           |           | symbols  |                     |
  |  -----------------------+-----------+----------+--------------------  |
  |  Portfolio (per user    | ~500B     | 1 crore  | ~5 GB               |
  |  HASH, avg 5 holdings)  |           | users    | (not all active)    |
  |  -----------------------+-----------+----------+--------------------  |
  |  Idempotency Keys       | ~100B     | 50 lakh  | ~500 MB             |
  |  (per order, 24h TTL)   |           | /day     | (peak intraday)     |
  |  -----------------------+-----------+----------+--------------------  |
  |  Previous Close          | ~50B     | 5000     | ~250 KB             |
  |  -----------------------+-----------+----------+--------------------  |
  |  TOTAL REDIS MEMORY     |           |          | ~7 GB peak          |
  |                                                                      |
  |  Recommendation: 16 GB Redis instance with volatile-ttl eviction    |
  |  (2x headroom for peak trading hours like budget day)               |
  |                                                                      |
  |  Order Book (L0, in-process):                                        |
  |  - ~2 GB for 5000 symbols (see Order Book section above)             |
  |  - Lives in MatchingEngine JVM heap                                  |
  |  - JVM recommendation: 8-16 GB heap with G1GC or ZGC                |
  +----------------------------------------------------------------------+
```

---

## Interview Q&A -- Caching Questions

### Q: "What caching strategy do you use for market data?"

> "Redis HASH per symbol with 500ms TTL. The TTL is a safety net -- primary
> freshness comes from the Kafka consumer updating Redis on every tick from
> the exchange (~100ms intervals). If the feed stops, data auto-expires
> in 500ms instead of becoming silently stale. Reads use HGETALL with
> pipelining -- 20 symbols in 0.5ms."

### Q: "Do you cache the order book?"

> "The order book is NOT a cache -- it's the primary data structure during
> trading hours. It's a TreeMap in-memory in the MatchingEngine. Matching
> NEVER reads from Redis or PostgreSQL. The DB is a backup via write-ahead
> log. On crash, we replay the WAL to rebuild the book. After market close,
> we persist resting orders to PostgreSQL for the next trading day."

### Q: "What happens if Redis goes down during trading?"

> "Trading continues unaffected. Redis is never in the critical path for
> order matching -- that uses the in-memory OrderBook. Redis failure degrades
> display (market data, portfolio) but not execution. For market data,
> we fall back to pushing directly from Kafka to WebSocket. For portfolio,
> we fall back to PostgreSQL reads. For stock master, L1 local cache
> serves requests."

### Q: "Why not cache margin?"

> "Stale margin is a financial risk to the broker. If user has 100K margin
> and places 5 orders worth 20K each, a cached margin of 100K would
> approve all 5 even though available margin decreases with each order.
> We always read margin from PostgreSQL with SELECT FOR UPDATE to
> atomically check and block the margin in one transaction."

### Q: "How do you handle cache warming?"

> "Pre-market warming starts at 8:30 AM, 45 minutes before market open.
> Five parallel threads: (1) stock master data from PostgreSQL to Redis
> and L1, (2) previous close prices, (3) circuit limits from exchange file,
> (4) overnight pending orders into order books, (5) active user margin data.
> By 9:15 AM when market opens, every cache is warm and the first order
> hits already-loaded data."

### Q: "What's the cache invalidation strategy for portfolio?"

> "Event-driven invalidation. When a trade executes, the settlement service
> publishes to Kafka. A CacheInvalidationConsumer reads the event and DELs
> portfolio:{userId} from Redis. On next portfolio read, it's rebuilt from
> PostgreSQL, enriched with current market prices from Redis, and cached.
> No TTL -- only invalidated on trade events. For unrealized P&L, clients
> compute it locally from cached positions + live LTP via WebSocket."
