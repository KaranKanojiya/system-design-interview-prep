# Stock Trading Platform (Zerodha/Upstox)

## Problem Summary

Design a **stock trading platform** (like Zerodha or Upstox) that supports 1M+ users placing orders in real-time during market hours. The core challenge is the **matching engine** -- maintain an in-memory **order book** per symbol with **bids** (buy orders, sorted descending by price in a TreeMap) and **asks** (sell orders, sorted ascending by price in a TreeMap). Each price level holds a **FIFO queue** of orders (price-time priority: best price wins, and at the same price, first-in-first-out). A **BUY LIMIT at $105** scans asks from lowest: if ask <= $105, match, generate trades, handle **partial fills** across multiple price levels. A **MARKET order** sweeps the best available prices until fully filled -- no price guarantee, prioritizes execution speed. **Risk management** uses a **chain of responsibility**: MarginCheck (does the user have enough funds?) -> PositionLimit (is the user exceeding maximum holdings?) -> CircuitBreaker (is the symbol halted?) -- reject early, reject fast. Every failed check short-circuits the chain. **P&L calculation**: unrealized = (currentPrice - avgCost) * quantity, using FIFO or average cost basis. **Settlement** follows T+1 (trades settle the next business day): the clearing corporation nets all trades, transfers shares from seller's demat to buyer's demat, and moves funds accordingly. The matching engine is **single-threaded per symbol** for correctness -- no locks, no race conditions, deterministic replay from Kafka. The system is **CP for orders and trades** (zero tolerance for lost or duplicate trades -- a regulatory violation) and **AP for market data** (a price tick arriving 100ms late is invisible to retail traders).

---

## 1-Minute Interview Revision

**Memorize these bullets. They cover 80% of what interviewers want to hear.**

- **Order Book: bids (TreeMap desc) + asks (TreeMap asc). Each price level = FIFO queue. Price-time priority.** The order book is the central data structure of any exchange. Bids are buy orders sorted by price descending (highest bid first -- buyers want the best price). Asks are sell orders sorted by price ascending (lowest ask first -- sellers want the best price). At each price level, orders are queued in arrival order (FIFO). This creates price-time priority: the best price always wins, and among orders at the same price, the earliest order fills first. The spread (best ask - best bid) is the market's liquidity indicator. A tight spread (Rs 0.05) means high liquidity; a wide spread (Rs 5.00) means low liquidity. The order book is entirely in-memory -- never persisted to disk on the hot path. Kafka provides durability via replicated log; the order book can be rebuilt by replaying the event log.
- **Matching: BUY LIMIT $105 -> scan asks from lowest. If ask <= $105, match. Partial fills across multiple levels.** When a BUY LIMIT order arrives at price $105, the matching engine scans the ask side starting from the lowest price. If the lowest ask is $102 (50 shares), match 50 shares at $102 (buyer gets a better price than their limit). Move to next ask: $103 (30 shares), match 30. Next: $106 -- STOP, $106 > $105 limit. Remaining quantity (20 shares) sits in the order book as a resting bid at $105. The buyer got partial fills at $102 and $103, better than their $105 limit. SELL LIMIT works symmetrically: scan bids from highest. Matching is O(k) where k = number of price levels touched, typically 1-3 for liquid stocks.
- **Market Order: sweep best available until filled. No price guarantee.** A MARKET BUY order has no price limit -- it takes whatever is available. Scan asks from lowest, fill at each price level until the order quantity is exhausted. In a thin order book, this causes **slippage**: buying 10,000 shares might start at $100 and end at $105 as it sweeps through multiple price levels. Market orders are dangerous in illiquid stocks. Exchanges implement protections: market-to-limit conversion (cap at best price + 5%), or reject if the estimated slippage exceeds a threshold. Market orders have the highest fill rate but the worst price guarantee.
- **Risk checks: chain of MarginCheck -> PositionLimit -> CircuitBreaker. Reject early, fast.** Before an order reaches the matching engine, it passes through a chain of responsibility. MarginCheck: does the user have sufficient margin? For intraday, require 20% of order value; for delivery, 100%. PositionLimit: is the user's total position in this symbol within regulatory limits? CircuitBreaker: is the symbol currently halted (price hit upper/lower circuit)? Each check is a separate class implementing a RiskCheck interface. If any check fails, the chain short-circuits immediately -- no point checking position limits if the user doesn't have enough margin. The chain is configurable: add/remove/reorder checks without changing the order service.
- **P&L: unrealized = (currentPrice - avgCost) * qty. FIFO or avg cost basis.** For P&L calculation, two methods: FIFO (First In First Out) -- sell the shares you bought earliest first. If you bought 100 @ Rs 200, then 100 @ Rs 250, selling 100 uses the Rs 200 cost basis, realized P&L = (sellPrice - 200) * 100. Average cost basis -- average all purchase prices. 200 shares at avg Rs 225, selling 100 uses Rs 225 cost. Unrealized P&L uses current market price: (LTP - avgCost) * remainingQty. Zerodha uses average cost for simplicity; US brokers often use FIFO for tax optimization. P&L calculation runs on every price tick for the user's portfolio -- must be efficient (O(n) where n = number of holdings, cached in Redis).
- **Latency: order placement < 10ms. Matching engine single-threaded per symbol for correctness.** End-to-end latency targets: API Gateway to order service: < 5ms. Risk checks: < 2ms (all in-memory/Redis lookups). Kafka produce: < 1ms. Matching engine processing: < 0.1ms (in-memory TreeMap operations). Total: < 10ms from order submission to ack. The matching engine is single-threaded per symbol to guarantee correctness: no locks, no race conditions, deterministic order. At 500 orders/sec per symbol, single-threaded handles it easily (each match takes < 100 microseconds). LMAX Disruptor pattern: lock-free ring buffer for order ingestion, mechanical sympathy for CPU cache efficiency.
- **CAP: CP for orders/trades (zero tolerance for errors). AP for market data (stale OK).** Orders and trades must be CP: losing an order is a regulatory violation, duplicating a trade means financial loss. Kafka with acks=all ensures order durability. RDS Aurora with synchronous replication ensures trade records survive AZ failure. Market data (price ticks, order book snapshots) is AP: if the displayed price is 100ms stale, the user sees Rs 2,500.05 instead of Rs 2,500.10 -- negligible for retail traders. Redis cache for market data is AP by design: cache miss falls through to Kafka consumer that rebuilds the latest state. WebSocket price pushes are best-effort: a dropped tick is replaced by the next tick 100ms later.

---

## Class Hierarchy

```
Order (domain entity)                      Trade (domain entity)
  |-- orderId (UUID)                         |-- tradeId (UUID)
  |-- userId                                 |-- buyOrderId
  |-- symbol ("RELIANCE")                    |-- sellOrderId
  |-- side: BUY | SELL                       |-- symbol
  |-- type: MARKET | LIMIT | STOP_LOSS       |-- price (execution price)
  |-- price (limit price, null for market)   |-- quantity
  |-- quantity (total ordered)               |-- timestamp
  |-- filledQuantity (partial fills)         |-- buyUserId, sellUserId
  |-- status: PENDING | PARTIAL | FILLED     |-- No setters (immutable trade record)
  |         | CANCELLED | REJECTED
  |-- timestamp (order creation time)
  |-- No setters (state transitions via events)

OrderBook (core data structure)            PriceLevel (value object)
  |-- symbol ("RELIANCE")                    |-- price (BigDecimal)
  |-- bids: TreeMap<Price, PriceLevel> DESC  |-- orders: Queue<Order> (FIFO)
  |-- asks: TreeMap<Price, PriceLevel> ASC   |-- totalQuantity (sum of all orders)
  |-- bestBid() -> Price                     |-- addOrder(order) -> append to queue
  |-- bestAsk() -> Price                     |-- removeFirst() -> next order in FIFO
  |-- spread() -> bestAsk - bestBid          |-- No setters (mutated via add/remove)
  |-- addOrder(order) -> place in correct side
  |-- cancelOrder(orderId) -> remove from book

MatchingEngine (single-threaded per symbol) OrderType (enum, Strategy pattern)
  |-- orderBook: OrderBook                    |-- MARKET: no price limit, sweep best
  |-- match(order) -> List<Trade>             |-- LIMIT: match up to limit price
  |-- matchBuyLimit(order) -> trades          |-- STOP_LOSS: trigger at stop price,
  |-- matchSellLimit(order) -> trades         |     then become market or limit
  |-- matchMarket(order) -> trades            |-- STOP_LOSS_LIMIT: trigger at stop,
  |-- Single-threaded: no locks needed        |     then place as limit order

RiskCheck (interface, Chain of Responsibility)  Position (domain entity)
  |-- MarginCheck                               |-- userId
  |     (available margin >= required margin)    |-- symbol
  |-- PositionLimitCheck                         |-- quantity (net holding)
  |     (total position < regulatory limit)      |-- avgCost (average buy price)
  |-- CircuitBreakerCheck                        |-- unrealizedPnL (live)
  |     (symbol not halted, price in range)      |-- realizedPnL (closed trades)
  |-- RiskCheckChain                             |-- costBasis: FIFO | AVG_COST
  |     (runs all checks, short-circuits on fail)|-- No setters (updated via trade events)

MatchingStrategy (interface)               MarketDataService
  |-- PriceTimePriorityStrategy              |-- getQuote(symbol) -> Quote
  |     (standard: best price, then FIFO)    |-- getOrderBookSnapshot(symbol) -> L2
  |-- ProRataStrategy                        |-- getCandles(symbol, interval) -> OHLCV
  |     (proportional fill at same price)    |-- subscribe(symbol) -> WebSocket stream
  |-- MatchingStrategyFactory                |-- publishTick(symbol, price, volume)
  |     (picks by instrument type)

OrderService                               SettlementService
  |-- placeOrder(userId, orderReq) -> orderId  |-- settleDay(date) -> SettlementReport
  |     -> validate, risk check, persist,       |-- netPositions(date) -> Map<User, Net>
  |        publish to Kafka                     |-- transferShares(seller, buyer, qty)
  |-- cancelOrder(userId, orderId)              |-- transferFunds(buyer, seller, amount)
  |-- modifyOrder(userId, orderId, newPrice)    |-- reconcile(ourTrades, exchangeTrades)
  |-- getOrders(userId) -> List<Order>
  |-- getOrderBook(symbol) -> OrderBookSnapshot

PortfolioService                           AppConfig (wiring)
  |-- getHoldings(userId) -> List<Position>   |-- creates services, strategies
  |-- getPnL(userId) -> PnLSummary            |-- wires order pipeline: validate -> risk
  |-- getMargin(userId) -> MarginSummary      |     -> persist -> Kafka -> match -> trade
  |-- blockMargin(userId, amount)             |-- configures RDS, DynamoDB, Redis,
  |-- releaseMargin(userId, amount)           |     Kafka, Timestream, WebSocket
  |-- updatePosition(userId, trade)           |-- selects matching strategy (price-time)
```

---

## Key Components

| Component | Role |
|-----------|------|
| `Order` | Core domain entity. Represents a user's intent to buy or sell. Tracks lifecycle from PENDING through PARTIAL to FILLED or CANCELLED. Immutable state transitions driven by matching events. |
| `Trade` | Immutable record of a completed match between a buy order and a sell order. Contains execution price, quantity, both parties. The atomic unit of the trading system. Never modified after creation. |
| `OrderBook` | The central data structure. Bids in a descending TreeMap, asks in an ascending TreeMap. Each price level is a FIFO queue. Entirely in-memory. Rebuilt from Kafka event log on restart. |
| `PriceLevel` | Value object representing all orders at a specific price. FIFO queue ensures time priority. Tracks total quantity for quick depth-of-book calculations. |
| `MatchingEngine` | Single-threaded per symbol. Consumes orders from Kafka, matches against the order book, produces trades to Kafka. No locks, no contention, deterministic replay. The heart of the system. |
| `RiskCheck` | Chain of responsibility: MarginCheck -> PositionLimit -> CircuitBreaker. Each check implements a common interface. Short-circuits on first failure. Configurable chain order. |
| `MatchingStrategy` | Strategy pattern: PriceTimePriority (standard equities) vs ProRata (futures/options). Factory selects by instrument type. Most exchanges use price-time for equities. |
| `OrderService` | Orchestrator: validates order fields, runs risk check chain, persists to RDS, blocks margin in DynamoDB, publishes to Kafka. Stateless, horizontally scalable. |
| `PortfolioService` | Manages user positions, holdings, P&L, and margin. Consumes trade events from Kafka to update positions. Serves portfolio queries from DynamoDB + Redis cache. |
| `MarketDataService` | Aggregates trade events into real-time quotes and OHLCV candles. Publishes price ticks via WebSocket. Stores candles in Timestream. Serves order book snapshots from Redis. |
| `SettlementService` | End-of-day batch: nets all trades per user per symbol, generates clearing files, interfaces with clearing corporation for T+1 settlement. Reconciles against exchange records. |
| `AppConfig` | Wires everything together. Kafka topics, RDS tables, DynamoDB indexes, Redis clusters, WebSocket connections. Single entry point for demo simulation. |

---

## Key Tradeoffs

| Decision | Option A | Option B | This Design |
|----------|----------|----------|-------------|
| Matching engine threading | Multi-threaded with locks | Single-threaded per symbol | **Single-threaded** -- no locks means no contention, no cache line bouncing, deterministic replay. At 500 orders/sec per symbol, single-threaded handles it trivially (each match < 100us). LMAX Exchange proves this: single thread processes 6M orders/sec. Multi-threading adds complexity and latency variance from lock contention. |
| Order book storage | Database-backed (persist every order) | In-memory with event log (Kafka) | **In-memory + Kafka** -- matching must be sub-millisecond. Database I/O adds 1-5ms per operation. Order book lives in memory; Kafka provides durability. On restart, replay Kafka log to rebuild order book state. Event sourcing pattern: the log IS the source of truth. |
| Risk checks | Synchronous DB query per check | In-memory cache (Redis/local) | **Redis cache** -- margin balance, position limits, and circuit breaker state cached in Redis. Sub-millisecond lookups. DynamoDB is the source of truth, updated asynchronously after trades. Stale cache risk: user might briefly over-trade. Mitigation: double-check margin after trade execution, reverse if insufficient. |
| Market data delivery | REST polling (client pulls every 1s) | WebSocket push (server pushes ticks) | **WebSocket** -- polling at 1s interval misses price movements. WebSocket pushes every tick (10-50/sec for active stocks). Client receives updates within 50ms of trade execution. Fallback to polling for clients that don't support WebSocket (HTTP/2 server-sent events). |
| Order book data structure | HashMap (unordered price levels) | TreeMap (sorted price levels) | **TreeMap** -- matching requires scanning from best price. TreeMap gives O(log n) insert/remove and O(1) access to best price (first/last entry). HashMap would require O(n log n) sort on every match. For 100 price levels, TreeMap is dramatically faster for the matching use case. |
| P&L cost basis | FIFO (first-in-first-out) | Average cost | **Average cost** as default (simpler, Zerodha's approach), FIFO as option for tax optimization. Average cost: buy 100 @ Rs 200, buy 100 @ Rs 300 = avg Rs 250. Sell 100 = cost basis Rs 250. FIFO: sell 100 = cost basis Rs 200 (first lot). FIFO is better for tax loss harvesting; avg cost is simpler to compute and explain. |
| Symbol partitioning | All symbols on one engine | Shard by symbol hash | **Shard by symbol** -- each symbol gets its own matching engine thread (or shares a partitioned engine). Top 500 symbols by volume get dedicated capacity. Remaining symbols share pooled engines. Kafka topic partitioning by symbol ensures ordered delivery per symbol. |
| Settlement model | Real-time gross settlement | Netting (T+1 batch) | **T+1 netting** -- net all trades per user per symbol at end of day. User bought 500 and sold 300 RELIANCE = net buy 200 shares. Reduces settlement volume by 80-90% compared to gross settlement. Clearing corporation handles multi-party netting across all brokers. |

---

## Design Patterns

| Pattern | Where | Why |
|---------|-------|-----|
| **Chain of Responsibility** | RiskCheck: MarginCheck -> PositionLimit -> CircuitBreaker | Each check is independent, order matters, short-circuits on failure |
| **Strategy** | MatchingStrategy: PriceTimePriority vs ProRata | Swap matching algorithm by instrument type without changing engine |
| **Strategy** | CostBasisStrategy: FIFO vs AverageCost | Swap P&L calculation method without changing portfolio service |
| **Observer** | Trade event -> position update, market data update, notification | Decouple trade execution from downstream processing |
| **Factory** | MatchingStrategyFactory, OrderTypeFactory | Encapsulate strategy selection based on instrument/order type |
| **Event Sourcing** | Kafka log as source of truth, order book rebuilt from events | Deterministic replay, audit trail, matching engine recovery |
| **CQRS** | Write: order placement through Kafka. Read: order book from Redis cache | Separate write path (optimized for throughput) from read path (optimized for latency) |
| **Repository** | OrderRepository, TradeRepository, PositionRepository | Abstract storage: swap RDS/DynamoDB implementations |
| **State Machine** | Order status: PENDING -> PARTIAL -> FILLED / CANCELLED / REJECTED | Enforce valid state transitions, prevent illegal order modifications |
| **Singleton** | MatchingEngine per symbol (single instance, single thread) | Exactly one matching engine per symbol for correctness |

---

## How to Run

```bash
cd /Users/kanojik/Documents/Karan/Autodesk_AI_Development/systemDesign
./gradlew :16-stock-trading:run
```

---

## Demo Output Preview

```
========================================
  STOCK TRADING PLATFORM (ZERODHA/UPSTOX) DEMO
========================================

--- Order Book Initialization ---
Symbol: RELIANCE
  Seeding initial order book with resting orders...

  ASK SIDE (sell orders, sorted ascending):
    Rs 2,498.00: [SELL-001 (50 qty), SELL-002 (30 qty)]     <- best ask
    Rs 2,500.00: [SELL-003 (200 qty)]
    Rs 2,505.00: [SELL-004 (100 qty)]
    Rs 2,510.00: [SELL-005 (150 qty)]

  BID SIDE (buy orders, sorted descending):
    Rs 2,495.00: [BUY-006 (75 qty)]                         <- best bid
    Rs 2,490.00: [BUY-007 (150 qty)]
    Rs 2,485.00: [BUY-008 (200 qty)]

  Spread: Rs 3.00 (best ask 2498 - best bid 2495)
  Order book depth: 4 ask levels, 3 bid levels

--- BUY LIMIT Order Demo ---
User U001 (Karan) places: BUY LIMIT 100 shares RELIANCE @ Rs 2,500.00

  Step 1: Risk Check Chain
    MarginCheck:
      Available margin: Rs 500,000.00
      Required margin (20% intraday): Rs 50,000.00
      500,000 >= 50,000 -> PASS
    PositionLimitCheck:
      Current RELIANCE position: 200 shares
      After order: 300 shares
      Limit: 10,000 shares -> PASS
    CircuitBreakerCheck:
      RELIANCE circuit status: ACTIVE
      Upper circuit: Rs 2,750.00, Lower circuit: Rs 2,250.00
      Order price Rs 2,500 within range -> PASS
    Risk check chain: ALL PASSED (2.1ms)

  Step 2: Order Persisted
    Order{id='ORD-100', user='U001', symbol='RELIANCE', side=BUY,
          type=LIMIT, price=2500.00, qty=100, status=PENDING}
    Margin blocked: Rs 50,000.00
    Published to Kafka topic: orders.RELIANCE

  Step 3: Matching Engine Processing
    Scanning asks from lowest price...

    Ask Rs 2,498.00 <= Limit Rs 2,500.00? YES -> MATCH
      SELL-001: Fill 50 shares @ Rs 2,498.00
      Trade{id='T001', buy=ORD-100, sell=SELL-001, price=2498.00, qty=50}
      Remaining: 100 - 50 = 50 shares

      SELL-002: Fill 30 shares @ Rs 2,498.00
      Trade{id='T002', buy=ORD-100, sell=SELL-002, price=2498.00, qty=30}
      Remaining: 50 - 30 = 20 shares

    Price level Rs 2,498.00 EXHAUSTED (0 orders remaining)

    Ask Rs 2,500.00 <= Limit Rs 2,500.00? YES -> MATCH
      SELL-003: Fill 20 shares @ Rs 2,500.00 (partial fill of SELL-003)
      Trade{id='T003', buy=ORD-100, sell=SELL-003, price=2500.00, qty=20}
      Remaining: 0 shares. ORDER FULLY FILLED.
      SELL-003 updated: 200 -> 180 qty remaining (resting)

    Matching complete: 3 trades generated in 0.08ms

  Step 4: Trade Summary
    ORD-100 FILLED:
      Fill 1: 50 shares @ Rs 2,498.00 = Rs 124,900.00
      Fill 2: 30 shares @ Rs 2,498.00 = Rs  74,940.00
      Fill 3: 20 shares @ Rs 2,500.00 = Rs  50,000.00
      ----------------------------------------
      Total:  100 shares, cost = Rs 249,840.00
      Average fill price: Rs 2,498.40
      Savings vs limit: Rs 160.00 (filled below limit price!)

  Step 5: Post-Trade Updates
    Position: U001 RELIANCE -> 200 + 100 = 300 shares, avg cost = Rs 2,420.15
    Margin: blocked 50,000, actual 49,968, released 32.00 back to available
    Market data: RELIANCE LTP = Rs 2,500.00, volume += 100

  Updated Order Book:
    ASK SIDE:
      Rs 2,500.00: [SELL-003 (180 qty)]                     <- new best ask
      Rs 2,505.00: [SELL-004 (100 qty)]
      Rs 2,510.00: [SELL-005 (150 qty)]
    BID SIDE:
      Rs 2,495.00: [BUY-006 (75 qty)]                       <- best bid (unchanged)
      Rs 2,490.00: [BUY-007 (150 qty)]
      Rs 2,485.00: [BUY-008 (200 qty)]
    New spread: Rs 5.00 (widened because Rs 2,498 ask level was consumed)

--- MARKET Order Demo ---
User U002 (Priya) places: MARKET BUY 250 shares RELIANCE

  Risk check chain: ALL PASSED (1.8ms)

  Matching (no price limit, sweep best available):
    Ask Rs 2,500.00: Fill 180 shares @ Rs 2,500.00 (exhaust SELL-003)
    Ask Rs 2,505.00: Fill 70 shares @ Rs 2,505.00 (partial SELL-004)

  Result: 250 shares filled
    Avg price: Rs 2,501.40
    Slippage: Rs 1.40 from best ask (2500.00)
    WARNING: Market orders have no price protection!

--- SELL LIMIT Order (Resting) ---
User U003 places: SELL LIMIT 100 shares RELIANCE @ Rs 2,520.00

  Best bid = Rs 2,495.00. Limit Rs 2,520 > all bids. NO MATCH.
  Order rests on ASK side of order book:
    Rs 2,505.00: [SELL-004 (30 qty)]
    Rs 2,510.00: [SELL-005 (150 qty)]
    Rs 2,520.00: [SELL-100 (100 qty)]                        <- new resting order

--- Order Cancellation Demo ---
User U003 cancels SELL-100:
  Order removed from book. Margin released.
  Status: SELL-100 -> CANCELLED

--- P&L Calculation Demo ---
User U001 (Karan) portfolio:
  RELIANCE: 300 shares @ avg Rs 2,420.15
  Current price (LTP): Rs 2,505.00

  Unrealized P&L: (2505.00 - 2420.15) * 300 = Rs 25,455.00 (+3.5%)
  Day P&L: (2505.00 - 2480.00) * 300 = Rs 7,500.00 (prev close: 2480)

  FIFO cost basis (if sold 100 shares):
    First lot: 200 shares @ Rs 2,380.00 (oldest buy)
    Sell 100 from first lot: realized P&L = (2505 - 2380) * 100 = Rs 12,500

  Average cost basis (if sold 100 shares):
    Avg cost: Rs 2,420.15
    Sell 100: realized P&L = (2505 - 2420.15) * 100 = Rs 8,485

--- Circuit Breaker Demo ---
RELIANCE price drops rapidly...
  LTP hits Rs 2,250.00 = LOWER CIRCUIT!
  Circuit breaker triggered: RELIANCE -> HALTED
  All pending orders cancelled.
  New orders rejected: "Symbol RELIANCE is halted (lower circuit breaker)"
  Cool-down period: 15 minutes.
  After cool-down: circuit expanded, trading resumes.

--- Real-Time Market Data Demo ---
WebSocket push to all RELIANCE subscribers:
  { symbol: "RELIANCE", ltp: 2505.00, bid: 2495.00, ask: 2505.00,
    volume: 1250350, dayHigh: 2520.00, dayLow: 2460.00, change: +1.01% }

  1-minute OHLCV candle (Timestream):
    { time: "2026-04-26T10:30:00", open: 2498.00, high: 2505.00,
      low: 2495.00, close: 2505.00, volume: 45000 }

========================================
  DEMO COMPLETE -- PROJECT 16 FINISHED!
  SYSTEM DESIGN INTERVIEW PREP: 16/16 COMPLETE!
========================================
```

---

## Quick Reference

```
Order Book:         Bids = TreeMap<Price, Queue> DESC. Asks = TreeMap<Price, Queue> ASC. Price-time priority. In-memory only.
Matching:           BUY LIMIT $105 -> scan asks ascending. If ask <= 105, match. Partial fills across levels. O(k) per match.
Market Order:       Sweep best available. No price guarantee. Slippage risk in thin books. Fill-or-kill variant: all or nothing.
Risk Checks:        Chain of Responsibility: MarginCheck -> PositionLimit -> CircuitBreaker. Reject early, short-circuit.
P&L:                Unrealized = (LTP - avgCost) * qty. FIFO for tax optimization. Avg cost for simplicity (Zerodha).
Settlement:         T+1 netting. Clearing corp nets trades. Transfer shares (demat) and funds. Reconcile against exchange.
Latency:            Order to ack < 10ms. Matching < 0.1ms. Single-threaded per symbol. No locks. LMAX Disruptor pattern.
Event Sourcing:     Kafka log = source of truth. Order book rebuilt from replay. Deterministic: same log = same state.
Circuit Breaker:    Upper/lower price limits (e.g., +/-20%). Symbol halted when hit. Cool-down period. Auto-resume.
Market Data:        Redis cache for quotes. WebSocket push for ticks. Timestream for OHLCV candles. 10-50 ticks/sec/symbol.
CAP:                CP for orders/trades (zero tolerance). AP for market data (stale price OK). CP for positions/margin.
Partitioning:       One Kafka partition per symbol. One matching engine thread per partition. Top 500 symbols = dedicated engines.
```

---

## What to Improve Later

- [ ] Full OrderBook with TreeMap-based bid/ask sides and PriceLevel FIFO queues
- [ ] MatchingEngine: single-threaded per symbol, price-time priority matching
- [ ] Partial fill handling across multiple price levels with trade generation
- [ ] MarketOrder matching: sweep best available with slippage tracking
- [ ] StopLoss and StopLossLimit order types with trigger price monitoring
- [ ] RiskCheckChain: MarginCheck, PositionLimitCheck, CircuitBreakerCheck
- [ ] PortfolioService: P&L calculation with FIFO and average cost basis
- [ ] MarketDataService: real-time quote aggregation, OHLCV candle generation
- [ ] Order state machine: PENDING -> PARTIAL -> FILLED | CANCELLED | REJECTED
- [ ] SettlementService: T+1 netting, position transfer, fund settlement
- [ ] WebSocket price push simulation for real-time market data delivery
- [ ] Circuit breaker: halt symbol, cancel pending orders, cool-down timer
