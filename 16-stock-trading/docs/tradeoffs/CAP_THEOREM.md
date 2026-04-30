# CAP Theorem & Distributed Tradeoffs in the Stock Trading Platform (Zerodha/Upstox)

> Interview-ready reference for a Senior Java developer.
> A stock trading platform has a SPLIT CAP requirement: CP for orders and trades
> (correctness is NON-NEGOTIABLE -- you cannot lose an order or double-fill),
> AP for market data (a 50ms stale price is acceptable for display).
> This split is THE key insight interviewers look for.

---

## Table of Contents

| Section | Key Point |
|---------|-----------|
| CP for Orders & Trades | Correctness is non-negotiable -- no lost orders, no double fills |
| AP for Market Data | 50ms stale price is acceptable for display, not for matching |
| ACID for Order Matching | Single-threaded per symbol ensures serial consistency |
| Exactly-Once Execution | Order deduplication + idempotent matching |
| Exchange Connectivity Loss | Queue orders, reject new ones, reconcile on reconnect |
| Industry Comparison | Zerodha (Kite), Upstox, NSE/BSE architecture |
| Regulatory Requirements | Audit trail, trade reporting, SEBI compliance |
| PACELC Analysis | When no partition: latency vs consistency choices |
| Interview Q&A | Ready-to-use answers |

---

## CP for Orders & Trades -- Correctness is NON-NEGOTIABLE

### The Core Argument

```
  +----------------------------------------------------------------------+
  |  THE KEY INSIGHT: A trading platform has a SPLIT CAP requirement.     |
  |  Orders/Trades = CP. Market Data = AP. Know which is which.          |
  +----------------------------------------------------------------------+
  |                                                                      |
  |         Consistency (C)                                              |
  |            /\                                                        |
  |           /  \                                                       |
  |          / CP \                                                      |
  |         /      \     <--- Order Matching (no double-fill)            |
  |        / ORDERS \    <--- Trade Settlement (money must balance)      |
  |       / POSITION \   <--- Position Tracking (can't show wrong qty)   |
  |      / MARGIN     \  <--- Margin Checks (must be real-time)          |
  |     /______________\                                                 |
  |  Availability (A) --- Partition Tolerance (P)                        |
  |                                                                      |
  |          AP                                                          |
  |         /  \                                                         |
  |        /    \    <--- Market Data Feed (LTP, bid/ask, volume)        |
  |       / DATA \   <--- Stock Watchlist (slightly stale = fine)        |
  |      /________\  <--- Historical Charts (seconds delay OK)           |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Why CP for Orders/Trades

```
  +----------------------------------------------------------------------+
  |  THE COST OF GETTING IT WRONG -- REAL SCENARIOS                       |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Scenario 1: Double Fill                                             |
  |  ----------------------------------------------------------------   |
  |  User places Buy 100 RELIANCE @ 2500. Due to network partition,      |
  |  two MatchingEngine replicas BOTH fill the order.                     |
  |  Result: User owns 200 shares, paid 500,000 instead of 250,000.      |
  |  User's margin is -250,000. Broker absorbs the loss.                  |
  |  SEBI investigates. Broker's license at risk.                         |
  |                                                                      |
  |  Scenario 2: Lost Order                                               |
  |  ----------------------------------------------------------------   |
  |  User places Sell 500 TCS @ 3900 (stop-loss to limit downside).      |
  |  Order acknowledged but lost during partition.                        |
  |  TCS drops to 3500. User loses 500 * 400 = 200,000.                  |
  |  User sues. Broker pays damages + regulatory fine.                    |
  |                                                                      |
  |  Scenario 3: Inconsistent Position                                    |
  |  ----------------------------------------------------------------   |
  |  Trade executes but position update goes to stale replica.            |
  |  User sees 0 shares (stale) instead of 100 shares.                   |
  |  User places another buy -- now double-exposed.                       |
  |  Or user can't sell because system shows no holdings.                 |
  |                                                                      |
  |  Scenario 4: Stale Margin                                            |
  |  ----------------------------------------------------------------   |
  |  Margin read from stale replica shows 500,000 (actual = 50,000).     |
  |  User places 10 large orders. All execute.                            |
  |  User owes 4,500,000 they don't have. Broker's risk.                  |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### CP Component Breakdown

```
  +----------------------------------------------------------------------+
  |  COMPONENT-LEVEL CAP DECISIONS                                        |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Component              | CAP  | Why                                 |
  |  -----------------------+------+-------------------------------------+
  |  Order Placement         | CP   | Lost order = financial loss for    |
  |                          |      | user. Must persist before ACK.     |
  |  -----------------------+------+-------------------------------------+
  |  Order Matching          | CP   | Double-fill = broker takes loss.   |
  |  (MatchingEngine)        |      | Single-threaded per symbol.        |
  |  -----------------------+------+-------------------------------------+
  |  Trade Settlement        | CP   | Money transfer must be atomic.     |
  |                          |      | Partial settle = books don't       |
  |                          |      | balance. Regulatory violation.     |
  |  -----------------------+------+-------------------------------------+
  |  Position Tracking       | CP   | Wrong position = wrong risk calc.  |
  |                          |      | User can over-trade or can't sell. |
  |  -----------------------+------+-------------------------------------+
  |  Margin/Balance          | CP   | Stale margin = user can trade      |
  |                          |      | beyond their means. Broker's risk. |
  |  -----------------------+------+-------------------------------------+
  |  Market Data (LTP)       | AP   | 50ms stale price is fine for       |
  |                          |      | display. Users see "indicative".   |
  |  -----------------------+------+-------------------------------------+
  |  Market Data (Bid/Ask)   | AP   | Stale bid/ask for watchlist OK.    |
  |                          |      | Matching uses REAL order book.     |
  |  -----------------------+------+-------------------------------------+
  |  Watchlist/Charts        | AP   | Historical/display data. Seconds   |
  |                          |      | delay acceptable.                  |
  |  -----------------------+------+-------------------------------------+
  |  Stock Master Data       | AP   | Symbol list, lot sizes change      |
  |                          |      | rarely. Stale for minutes is fine. |
  |  -----------------------+------+-------------------------------------+
  |  Audit Trail             | CP   | Regulatory requirement. Every      |
  |                          |      | order and trade must be logged     |
  |                          |      | immutably. Cannot lose records.    |
  |  -----------------------+------+-------------------------------------+
  |                                                                      |
  +----------------------------------------------------------------------+
```

---

## AP for Market Data -- Slightly Stale is Acceptable

### Why AP Works for Market Data

```
  +----------------------------------------------------------------------+
  |  MARKET DATA: AP IS ACCEPTABLE                                        |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  What users see on their screen:                                     |
  |                                                                      |
  |  Symbol     LTP      Change    Bid      Ask      Volume              |
  |  RELIANCE   2502.50  +1.2%     2502.00  2503.00  12,45,000           |
  |  TCS        3890.00  -0.5%     3889.50  3890.50   8,30,000           |
  |  INFY       1650.00  +0.8%     1649.50  1650.50   5,20,000           |
  |                                                                      |
  |  This data is DISPLAY-ONLY. When the user clicks "Buy":              |
  |  - The price they see is NOT the price they get                      |
  |  - The actual price comes from the MATCHING ENGINE (CP)              |
  |  - Market data is "indicative" -- a snapshot, not a guarantee        |
  |                                                                      |
  |  Delay tolerance:                                                    |
  |  +-----------------------------+-----------------------------------+ |
  |  | Delay        | Acceptable?  | Why                               | |
  |  +-----------------------------+-----------------------------------+ |
  |  | < 50ms       | YES          | Imperceptible to human users      | |
  |  | 50-200ms     | Marginal     | Noticeable in fast markets        | |
  |  | 200ms-1s     | Poor         | Users complain about stale prices | |
  |  | > 1s         | NO           | Users make bad decisions           | |
  |  +-----------------------------+-----------------------------------+ |
  |                                                                      |
  |  Why AP not CP:                                                      |
  |  - 50 lakh concurrent users watching prices                          |
  |  - Blocking reads for strong consistency = unacceptable latency      |
  |  - A 50ms stale LTP never causes financial loss (matching is CP)     |
  |  - Zerodha serves market data via WebSocket + Redis pub/sub (AP)     |
  +----------------------------------------------------------------------+
```

### Market Data Delivery Architecture

```
  +----------------------------------------------------------------------+
  |  MARKET DATA FLOW -- AP with LOW LATENCY                              |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Exchange (NSE/BSE)                                                  |
  |       |                                                              |
  |       | (1) Multicast UDP (production) / TCP feed (our simulation)   |
  |       |     5000-10000 ticks per second per symbol                   |
  |       v                                                              |
  |  +-------------------+                                               |
  |  | Feed Handler      |  (2) Decode FIX/binary protocol               |
  |  | (co-located at    |      Parse symbol, price, qty, timestamp      |
  |  |  exchange DC)     |                                               |
  |  +-------------------+                                               |
  |       |                                                              |
  |       | (3) Publish to Kafka topic: market-data-{symbol}             |
  |       v                                                              |
  |  +-------------------+                                               |
  |  | Market Data       |  (4) Consume from Kafka                       |
  |  | Service           |      Update Redis cache (LTP, bid/ask, vol)   |
  |  |                   |      TTL: 500ms (auto-expire stale data)      |
  |  +-------------------+                                               |
  |       |                                                              |
  |       | (5) Push to WebSocket connections                            |
  |       v                                                              |
  |  +-------------------+                                               |
  |  | WebSocket Gateway |  (6) Fan-out to 50 lakh connected clients     |
  |  | (multiple nodes)  |      Each client subscribes to symbols        |
  |  +-------------------+                                               |
  |       |                                                              |
  |       v                                                              |
  |  User's Kite/Upstox app shows updated prices                        |
  |                                                                      |
  |  Total latency budget: < 50ms end-to-end (exchange to screen)        |
  |  (Production Zerodha: ~30-50ms from exchange tick to user screen)     |
  +----------------------------------------------------------------------+
```

---

## ACID for Order Matching -- Single-Threaded Per Symbol

### The Key Design Decision

```
  +----------------------------------------------------------------------+
  |  MATCHING ENGINE: SINGLE-THREADED PER SYMBOL                          |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  WHY single-threaded?                                                |
  |  - An order book is a shared mutable data structure                  |
  |  - Multiple threads matching the same book = race conditions         |
  |  - Locks kill latency (contention under high load)                   |
  |  - Single thread = serial consistency = NO locks needed              |
  |                                                                      |
  |  HOW it works:                                                       |
  |                                                                      |
  |  +------------------+    +------------------+                        |
  |  | RELIANCE orders  |--->| Thread-RELIANCE  |---> Trades             |
  |  | (single queue)   |    | (single thread)  |                        |
  |  +------------------+    +------------------+                        |
  |                                                                      |
  |  +------------------+    +------------------+                        |
  |  | TCS orders       |--->| Thread-TCS       |---> Trades             |
  |  | (single queue)   |    | (single thread)  |                        |
  |  +------------------+    +------------------+                        |
  |                                                                      |
  |  +------------------+    +------------------+                        |
  |  | INFY orders      |--->| Thread-INFY      |---> Trades             |
  |  | (single queue)   |    | (single thread)  |                        |
  |  +------------------+    +------------------+                        |
  |                                                                      |
  |  Properties:                                                         |
  |  - Each symbol's order book is modified by exactly ONE thread        |
  |  - No locks, no CAS, no contention                                  |
  |  - Orders for RELIANCE and TCS are matched in parallel              |
  |  - Orders for the SAME symbol are matched sequentially              |
  |  - This is how NSE, NASDAQ, and every real exchange works            |
  |                                                                      |
  |  ACID mapping:                                                       |
  |  +-------------------+--------------------------------------------+ |
  |  | Property          | How Achieved                               | |
  |  +-------------------+--------------------------------------------+ |
  |  | Atomicity         | Match + fill + trade = single operation    | |
  |  |                   | in single thread. No partial state.        | |
  |  +-------------------+--------------------------------------------+ |
  |  | Consistency       | Risk checks BEFORE matching. Order book    | |
  |  |                   | always in valid state.                     | |
  |  +-------------------+--------------------------------------------+ |
  |  | Isolation          | Single thread per symbol = serializable.  | |
  |  |                   | No concurrent access to same book.         | |
  |  +-------------------+--------------------------------------------+ |
  |  | Durability         | Trade persisted to DB after matching.     | |
  |  |                   | Write-ahead log for crash recovery.        | |
  |  +-------------------+--------------------------------------------+ |
  +----------------------------------------------------------------------+
```

### Numbered Call Chain -- ACID Order Matching

```
1.  User places Buy 100 RELIANCE @ 2500 Limit
2.  Order arrives at RELIANCE queue (MPSC ring buffer in production)
3.  RELIANCE matching thread picks up the order (single consumer)
4.  Thread holds NO locks -- it's the only writer for this order book
5.  Risk checks pass (margin, position limit, circuit breaker)
6.  LimitOrderStrategy.execute(order, orderBook)
7.  TreeMap asks.headMap(2500, true) -> [2490: 60 shares, 2500: 50 shares]
8.  Match 60 @ 2490 (Trade T-1), Match 40 @ 2500 (Trade T-2)
9.  Atomically:
      - Remove 60-share ask from level 2490 (PriceLevel emptied, removed)
      - Reduce 50-share ask at 2500 to 10 shares
      - Create Trade T-1 and Trade T-2
      - Mark incoming order as FILLED (100/100)
      - Mark resting ask at 2490 as FILLED
      - Mark resting ask at 2500 as PARTIALLY_FILLED
10. All state changes happen in ONE thread, NO interleaving possible
11. Trades written to WAL (write-ahead log) for durability
12. Trades published to Kafka for async persistence to PostgreSQL
13. TradeEvents published to observers (notification, market data)
```

### Why Not Multi-Threaded Matching?

```
  +----------------------------------------------------------------------+
  |  MULTI-THREADED MATCHING = DISASTER                                   |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Thread A: Buy 100 @ 2500     Thread B: Buy 200 @ 2500              |
  |       |                              |                               |
  |       | Read best ask: 2490 (150)    | Read best ask: 2490 (150)     |
  |       |                              |                               |
  |       | Match 100 from 150           | Match 150 from 150            |
  |       |                              |                               |
  |       | Write: 150 - 100 = 50 left   | Write: 150 - 150 = 0 left    |
  |       v                              v                               |
  |  Result depends on write order:                                      |
  |  - If A wins: 50 left, but B matched 150 (seller sold 250 total!)   |
  |  - If B wins: 0 left, but A matched 100 (from where?)               |
  |  - DOUBLE-FILL: seller had 150 shares, system sold 250              |
  |                                                                      |
  |  Fix: mutex? Kills latency. 10,000 orders/sec becomes 1,000.        |
  |  Fix: CAS? Complex, still has contention under load.                 |
  |  Fix: single thread? Zero contention, maximum throughput for one     |
  |       symbol, parallel across symbols. This is what production uses. |
  +----------------------------------------------------------------------+
```

---

## Exactly-Once Trade Execution

### Order Deduplication

```
  +----------------------------------------------------------------------+
  |  EXACTLY-ONCE: DEDUP + IDEMPOTENT MATCHING                           |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Problem: Network retry can cause duplicate order submission          |
  |                                                                      |
  |  Client                    API Gateway             MatchingEngine    |
  |    |                          |                        |             |
  |    | (1) Place order          |                        |             |
  |    |   orderId: "ORD-001"     |                        |             |
  |    |------------------------->|                        |             |
  |    |                          | (2) Forward to         |             |
  |    |                          |     matching engine     |             |
  |    |                          |----------------------->|             |
  |    |                          |                        |             |
  |    |    <--- network timeout  |                        |             |
  |    |    (ACK lost)            |                        | (3) Order   |
  |    |                          |                        |     matched |
  |    | (4) RETRY same order     |                        |             |
  |    |   orderId: "ORD-001"     |                        |             |
  |    |------------------------->|                        |             |
  |    |                          |                        |             |
  |    |                          | (5) Check dedup:       |             |
  |    |                          |   "ORD-001" already    |             |
  |    |                          |   processed?           |             |
  |    |                          |   YES -> return        |             |
  |    |                          |   existing result      |             |
  |    |                          |                        |             |
  |    | (6) Return existing      |                        |             |
  |    |     order result         |                        |             |
  |    |<-------------------------|                        |             |
  |                                                                      |
  |  Deduplication mechanism:                                            |
  |  +------------------------------------------------------------------+|
  |  | Layer          | How                                             ||
  |  +----------------+------------------------------------------+------+|
  |  | Client         | Generate UUID orderId before sending.    |      ||
  |  |                | Retry sends SAME orderId.                |      ||
  |  +----------------+------------------------------------------+------+|
  |  | API Gateway    | Redis SET NX orderId TTL 24h.            |      ||
  |  |                | If key exists -> return cached result.   |      ||
  |  +----------------+------------------------------------------+------+|
  |  | MatchingEngine | OrderBook.addOrder() checks if orderId   |      ||
  |  |                | already in book -> reject duplicate.     |      ||
  |  +----------------+------------------------------------------+------+|
  |  | DB             | orders table: orderId is PRIMARY KEY.    |      ||
  |  |                | INSERT fails on duplicate -> idempotent. |      ||
  |  +----------------+------------------------------------------+------+|
  +----------------------------------------------------------------------+
```

### Idempotent Matching

```
  +----------------------------------------------------------------------+
  |  IDEMPOTENT MATCHING -- SAME INPUT, SAME OUTPUT                       |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Matching is deterministic:                                          |
  |  - Same order book state + same incoming order = same trades         |
  |  - Single-threaded = no non-determinism from thread scheduling       |
  |                                                                      |
  |  Replay safety:                                                      |
  |  - If matching engine crashes mid-match:                             |
  |    1. On restart, replay orders from WAL (write-ahead log)           |
  |    2. Each order has orderId -- skip already-matched orders          |
  |    3. Order book rebuilt from persisted state + replayed orders       |
  |    4. Result is identical to pre-crash state                         |
  |                                                                      |
  |  Trade deduplication:                                                |
  |  - Each trade has tradeId = f(buyOrderId, sellOrderId, sequence)     |
  |  - Replaying the same match generates the same tradeId               |
  |  - Downstream consumers (settlement, notification) dedup by tradeId  |
  +----------------------------------------------------------------------+
```

---

## Exchange Connectivity Loss -- Handling Partitions

### What Happens When the Exchange Link Goes Down

```
  +----------------------------------------------------------------------+
  |  EXCHANGE CONNECTIVITY LOSS SCENARIOS                                 |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Scenario 1: Broker -> Exchange link down                            |
  |  ----------------------------------------------------------------   |
  |                                                                      |
  |  Broker System                         Exchange (NSE)                |
  |       |                                     |                        |
  |       |  Orders queued locally               |                        |
  |       |  New orders: REJECT with             |                        |
  |       |  "Exchange unreachable"              |                        |
  |       |                                     |                        |
  |       |  <<< link restored >>>              |                        |
  |       |                                     |                        |
  |       |  (1) Reconcile: request order        |                        |
  |       |      status for all pending          |                        |
  |       |      orders from exchange            |                        |
  |       |  (2) Replay queued orders            |                        |
  |       |      (with dedup by orderId)         |                        |
  |       |  (3) Update local state to           |                        |
  |       |      match exchange state            |                        |
  |                                                                      |
  |  Scenario 2: Internal broker partition (DB unreachable)              |
  |  ----------------------------------------------------------------   |
  |                                                                      |
  |  Action: STOP ACCEPTING NEW ORDERS                                   |
  |  Why: Cannot verify margin, cannot persist order                     |
  |  User sees: "System temporarily unavailable. Please retry."          |
  |  Recovery: Resume when DB is accessible, reconcile pending orders    |
  |                                                                      |
  |  Scenario 3: Market data feed lost                                   |
  |  ----------------------------------------------------------------   |
  |                                                                      |
  |  Action: Continue serving LAST KNOWN prices (marked as stale)        |
  |  Why: Market data is AP -- stale display is tolerable                |
  |  User sees: "Prices may be delayed" warning on screen               |
  |  Note: Matching engine uses ORDER BOOK, not market data feed         |
  |         So matching continues even if display feed is stale          |
  +----------------------------------------------------------------------+
```

### Numbered Call Chain -- Partition Recovery

```
1.  Exchange link restored after 30-second outage
2.  Broker sends "Order Status Request" for all OPEN/PENDING orders
3.  Exchange responds with current status of each order
4.  For each order:
      - If exchange says FILLED but broker says OPEN:
        -> Fetch trade details, update local state, settle
      - If exchange says OPEN but broker says CANCELLED:
        -> Send cancel request to exchange
      - If exchange says REJECTED but broker says PENDING:
        -> Update local state to REJECTED, notify user
5.  Process queued orders (placed during outage) with dedup
6.  Resume normal order flow
7.  Generate reconciliation report for compliance team
```

---

## Industry Comparison -- Zerodha, Upstox, NSE

### Architecture Comparison

```
  +----------------------------------------------------------------------+
  |  INDUSTRY ARCHITECTURE COMPARISON                                     |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Component      | Zerodha (Kite)  | Upstox        | NSE/BSE          |
  |  ---------------+-----------------+---------------+-----------------  |
  |  Language        | Go (Kite),      | Java, Node.js | C++ (NEAT,       |
  |                  | Python          |               | colocation)      |
  |  ---------------+-----------------+---------------+-----------------  |
  |  Matching Engine | Sends to NSE/   | Sends to NSE/ | Internal         |
  |                  | BSE exchange    | BSE exchange   | (the source of   |
  |                  | (not self-match)| (not self-match)| truth)          |
  |  ---------------+-----------------+---------------+-----------------  |
  |  Market Data    | Kite Ticker     | WebSocket API | Multicast UDP    |
  |                  | (WebSocket)     |               | (co-located DCs) |
  |  ---------------+-----------------+---------------+-----------------  |
  |  Order Gateway  | Kite Connect    | Upstox API    | FIX protocol     |
  |                  | REST + WS       | REST + WS     | / OUCH protocol  |
  |  ---------------+-----------------+---------------+-----------------  |
  |  DB (Orders)    | PostgreSQL      | PostgreSQL    | In-memory + WAL  |
  |  ---------------+-----------------+---------------+-----------------  |
  |  Cache          | Redis           | Redis         | Custom L1/L2     |
  |  ---------------+-----------------+---------------+-----------------  |
  |  Message Queue  | Kafka           | RabbitMQ      | Custom ring      |
  |                  |                 |               | buffer           |
  |  ---------------+-----------------+---------------+-----------------  |
  |  Users          | 1.3 crore       | 1 crore       | All brokers      |
  |  ---------------+-----------------+---------------+-----------------  |
  |  Latency        | ~30ms UI        | ~50ms UI      | ~6 microseconds  |
  |  (order to ACK) | (to exchange)   | (to exchange) | (matching)       |
  |  ---------------+-----------------+---------------+-----------------  |
  |                                                                      |
  |  KEY INSIGHT for interviews:                                         |
  |  - Zerodha/Upstox are BROKERS, not exchanges                        |
  |  - They DON'T run their own matching engine for equities             |
  |  - They route orders to NSE/BSE, which does the actual matching      |
  |  - Our system design simulates BOTH broker + exchange logic          |
  |  - In interview, clarify: "Am I designing the broker or exchange?"   |
  +----------------------------------------------------------------------+
```

### Zerodha Kite Architecture Deep Dive

```
  +----------------------------------------------------------------------+
  |  ZERODHA KITE -- SIMPLIFIED ARCHITECTURE                              |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Mobile/Web App                                                      |
  |       |                                                              |
  |       | (1) REST API: POST /orders (place)                           |
  |       |     WebSocket: market data stream                            |
  |       v                                                              |
  |  +-------------------+                                               |
  |  | Kite API Gateway  |  Go-based, handles 1.3 crore users           |
  |  | (Go)              |  Rate limiting, auth, request validation      |
  |  +-------------------+                                               |
  |       |                                                              |
  |       | (2) Order validated, margin checked                          |
  |       v                                                              |
  |  +-------------------+                                               |
  |  | Order Management  |  PostgreSQL for order persistence             |
  |  | System (OMS)      |  Redis for margin cache                       |
  |  | (Go + Python)     |  Kafka for event streaming                    |
  |  +-------------------+                                               |
  |       |                                                              |
  |       | (3) FIX protocol message to exchange                         |
  |       v                                                              |
  |  +-------------------+                                               |
  |  | Exchange Gateway  |  Co-located servers at NSE/BSE datacenter     |
  |  | (co-located)      |  FIX 4.2 protocol                             |
  |  +-------------------+                                               |
  |       |                                                              |
  |       | (4) Order reaches exchange matching engine                   |
  |       v                                                              |
  |  +-------------------+                                               |
  |  | NSE/BSE Exchange  |  C++ matching engine, ~6 microsecond match    |
  |  | Matching Engine   |  Single-threaded per symbol (NEAT system)     |
  |  +-------------------+                                               |
  |       |                                                              |
  |       | (5) Trade confirmation back via FIX                          |
  |       v                                                              |
  |  Exchange Gateway -> OMS -> Kite API -> User notification            |
  |                                                                      |
  |  End-to-end: ~30-50ms from user click to exchange ACK                |
  |  (network round-trip is the bottleneck, not compute)                 |
  +----------------------------------------------------------------------+
```

### NSE Matching Engine Internals

```
  +----------------------------------------------------------------------+
  |  NSE MATCHING ENGINE -- THE GOLD STANDARD                             |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Performance numbers (as of 2024):                                   |
  |  - Matching latency: < 6 microseconds                                |
  |  - Orders per second: 100,000+ per symbol                            |
  |  - Total capacity: 10 crore orders per day                           |
  |  - Uptime: 99.999% (< 5 min downtime per year)                      |
  |                                                                      |
  |  Architecture:                                                       |
  |  - Language: C++ with custom memory allocator                        |
  |  - Order book: std::map (red-black tree, like Java TreeMap)          |
  |  - Network: kernel bypass (Solarflare NIC, DPDK)                    |
  |  - Threading: one thread per symbol, lock-free queues between        |
  |  - Persistence: WAL on local NVMe SSD, async replication             |
  |  - Redundancy: active-passive with < 1ms failover                   |
  |                                                                      |
  |  WHY so fast:                                                        |
  |  - No garbage collection (C++, not Java)                              |
  |  - No syscalls in hot path (kernel bypass for network I/O)           |
  |  - Pre-allocated memory pools (no malloc in hot path)                |
  |  - Cache-friendly data structures (contiguous memory)                |
  |  - Co-located: broker servers in same datacenter as exchange         |
  +----------------------------------------------------------------------+
```

---

## Regulatory Requirements -- Audit Trail & Compliance

### SEBI Compliance for Indian Markets

```
  +----------------------------------------------------------------------+
  |  REGULATORY REQUIREMENTS (SEBI, India)                                |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Requirement            | Implementation                             |
  |  -----------------------+--------------------------------------------+
  |  Order Audit Trail      | Every order (place/modify/cancel) logged   |
  |                         | with timestamp, user, IP, and outcome.     |
  |                         | Immutable log -- append-only, never delete.|
  |  -----------------------+--------------------------------------------+
  |  Trade Reporting        | All trades reported to exchange within     |
  |                         | seconds. Daily trade file submitted to     |
  |                         | NSE/BSE for reconciliation.                |
  |  -----------------------+--------------------------------------------+
  |  Risk Management        | Real-time margin monitoring. Auto-square   |
  |                         | off positions if margin falls below        |
  |                         | maintenance margin.                        |
  |  -----------------------+--------------------------------------------+
  |  Circuit Breakers       | Exchange-mandated price bands. Orders      |
  |                         | outside bands MUST be rejected.            |
  |  -----------------------+--------------------------------------------+
  |  Client Fund Segregation| Client funds in separate bank account.     |
  |                         | Broker cannot use client money.            |
  |  -----------------------+--------------------------------------------+
  |  Data Retention         | All order and trade data retained for      |
  |                         | minimum 5 years. Audit by SEBI anytime.    |
  |  -----------------------+--------------------------------------------+
  |  Best Execution         | Orders must be executed at best available  |
  |                         | price. Log evidence of best execution.     |
  |  -----------------------+--------------------------------------------+
  |  DMA (Direct Market     | Broker must implement risk checks BEFORE   |
  |  Access) Controls       | order reaches exchange. Cannot bypass.     |
  |  -----------------------+--------------------------------------------+
  |                                                                      |
  |  HOW WE ADDRESS THIS IN OUR DESIGN:                                  |
  |  - Chain of Responsibility (Pattern 8) for pre-trade risk checks     |
  |  - Observer (Pattern 6) for audit logging on every trade event       |
  |  - Repository (Pattern 4) for immutable order/trade persistence      |
  |  - State (Pattern 7) for full order lifecycle tracking               |
  |  - CircuitBreakerStrategy for exchange price band enforcement        |
  +----------------------------------------------------------------------+
```

---

## PACELC Analysis -- When No Partition

### Latency vs Consistency Trade-offs

```
  +----------------------------------------------------------------------+
  |  PACELC: What happens when there is NO partition?                     |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  PACELC = if Partition -> AP or CP; Else -> Latency or Consistency   |
  |                                                                      |
  |  Component              | If P  | Else (normal) | Classification     |
  |  -----------------------+-------+---------------+-------------------  |
  |  Order Matching         | CP    | C over L      | PC/EC              |
  |  (single-threaded,      |       | Consistency   | (always consistent |
  |   sequential per symbol)|       | always wins.  |  even if slower)   |
  |  -----------------------+-------+---------------+-------------------  |
  |  Trade Settlement       | CP    | C over L      | PC/EC              |
  |  (must be atomic,       |       | Wait for DB   | (ACID, wait for    |
  |   ACID transaction)     |       | ACK before    |  commit)           |
  |                         |       | confirming.   |                    |
  |  -----------------------+-------+---------------+-------------------  |
  |  Market Data Display    | AP    | L over C      | PA/EL              |
  |  (serve last known      |       | Serve from    | (fast, slightly    |
  |   price, don't block)   |       | cache. Stale  |  stale is OK)      |
  |                         |       | is acceptable.|                    |
  |  -----------------------+-------+---------------+-------------------  |
  |  Position Query         | CP    | L over C*     | PC/EL              |
  |  (must be correct for   |       | Read from     | (consistent under  |
  |   risk, but cached for  |       | Redis cache   |  partition, fast   |
  |   display)              |       | for display.  |  when normal)      |
  |                         |       | *Invalidated  |                    |
  |                         |       |  on trade.    |                    |
  |  -----------------------+-------+---------------+-------------------  |
  |                                                                      |
  |  KEY INSIGHT:                                                        |
  |  The matching engine is ALWAYS PC/EC -- it never sacrifices           |
  |  consistency. The latency cost is acceptable because matching         |
  |  is single-threaded and fast (microseconds).                         |
  |  Market data is PA/EL -- it always prioritizes speed.                |
  |  Positions are PC/EL -- consistent when it matters (risk check),     |
  |  fast when it doesn't (user display).                                |
  +----------------------------------------------------------------------+
```

---

## Interview Q&A -- Ready-to-Use Answers

### Q: "Is a trading platform CP or AP?"

> "It's a SPLIT. Orders and trades are strictly CP -- you cannot double-fill
> an order or lose a trade. The matching engine is single-threaded per symbol
> for serial consistency with zero contention. Market data display is AP --
> a 50ms stale LTP is acceptable for display because actual execution uses
> the order book, not the display feed."

### Q: "How do you ensure exactly-once trade execution?"

> "Three layers: (1) Client generates a UUID orderId and retries with the
> same ID. (2) API gateway uses Redis SET NX to dedup by orderId with 24h TTL.
> (3) MatchingEngine processes orders sequentially per symbol, so the same
> orderId is never matched twice. Trades have deterministic tradeIds derived
> from the matching, so downstream consumers can dedup too."

### Q: "What happens if the exchange goes down?"

> "We queue orders locally and reject new ones with 'exchange unreachable'.
> On reconnect, we reconcile: request status for all pending orders from the
> exchange, replay queued orders with dedup, and generate a reconciliation
> report for compliance."

### Q: "Why single-threaded matching? Isn't that slow?"

> "Single-threaded per SYMBOL, not per system. RELIANCE and TCS are matched
> in parallel. Within one symbol, single-threaded means zero locks, zero
> contention, zero context switches. NSE matches 100,000+ orders per second
> per symbol on a single thread with 6-microsecond latency. The bottleneck
> is never the matching algorithm -- it's network I/O."

### Q: "How does Zerodha handle 1.3 crore users?"

> "Zerodha is a BROKER, not an exchange. They route orders to NSE/BSE via
> FIX protocol from co-located servers. Their challenge is the API gateway
> (Go), real-time market data (Kite Ticker via WebSocket), and order management
> (PostgreSQL + Redis + Kafka). The actual matching happens at the exchange."

### Q: "How do you handle a market crash scenario (high volatility)?"

> "Three safeguards: (1) Circuit breakers at stock level -- exchange sets
> daily price bands, orders outside bands are rejected. (2) Index circuit
> breaker -- if NIFTY drops 10%, trading halts for 45 minutes. (3) Margin
> top-up calls -- if market moves against a position, broker demands
> additional margin in real-time. If not provided, position is auto-squared-off."

### Q: "CP means unavailable during partition. What does the user see?"

> "For order placement: 'System temporarily unavailable, please retry.'
> This is the CORRECT behavior -- we refuse to accept an order we can't
> guarantee will be processed correctly. For market data: prices continue
> to display (AP) with a 'prices may be delayed' warning. Users can see
> the market but not trade -- which is safer than trading on stale data."
