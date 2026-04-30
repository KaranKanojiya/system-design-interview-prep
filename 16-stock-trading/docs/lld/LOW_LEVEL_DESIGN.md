# Low-Level Design: Stock Trading Platform (Zerodha/Upstox)

> **Difficulty**: HARD | **Target**: Senior Java Developer (7+ years) | **Focus**: Order Book Data Structure, Price-Time Priority Matching, Margin/Risk Checks, Position Management, P&L Calculation, Concurrency
> This is the real-time systems interview question. It tests your understanding of order book
> mechanics (bid/ask TreeMaps), matching algorithms (price-time priority), order lifecycle
> state machines, margin/risk validation chains, position tracking, P&L strategies (FIFO vs
> average cost), settlement workflows, and concurrent access to shared order books -- all
> with lock-free or fine-grained locking design.

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
| **Model** | `model/` | Domain entities: Stock (symbol, name, exchange, lotSize, tickSize, circuitLimits), Order (Builder, orderId, userId, symbol, side, type, price, qty, filledQty, status, timestamps), OrderSide (enum: BUY, SELL), OrderType (enum: MARKET, LIMIT, STOP_LOSS, STOP_LIMIT), OrderStatus (enum: PENDING, OPEN, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED), Trade (tradeId, buyOrderId, sellOrderId, symbol, price, quantity, timestamp), Position (userId, symbol, quantity, avgBuyPrice, currentPrice, unrealizedPnL, realizedPnL), Account (userId, balance, marginUsed, marginAvailable, holdings), MarketData (symbol, ltp, bidPrice, askPrice, open, high, low, close, volume, timestamp), OrderBookEntry (price, quantity, orderCount, timestamp -- one price level), Watchlist (userId, name, symbols), User (userId, name, email, accountType RETAIL/INSTITUTIONAL). |
| **Engine** | `engine/` | THE CORE: MatchingEngine (order book + matching logic per symbol), OrderBook (bids TreeMap descending, asks TreeMap ascending, each price maps to Queue of Orders), PriceLevel (price, orders LinkedList, totalQuantity). This is what Zerodha/NSE actually runs -- price-time priority matching with O(log N) insert and O(1) best-price access. |
| **Strategy (Order)** | `strategy/order/` | Pluggable order execution: OrderExecutionStrategy interface with MarketOrderStrategy (match against best available price, walk the book if needed) and LimitOrderStrategy (match if price crosses spread, else rest in book as passive order). Strategy pattern -- swap execution logic without touching the engine. |
| **Strategy (Risk)** | `strategy/risk/` | Pluggable risk validation: RiskCheckStrategy interface with MarginCheckStrategy (sufficient funds/margin for the order?), PositionLimitStrategy (within per-symbol and total position limits?), CircuitBreakerStrategy (price within upper/lower circuit limits?). Chain multiple strategies for defense-in-depth pre-trade risk. |
| **Strategy (Pricing)** | `strategy/pricing/` | Pluggable P&L calculation: PnLStrategy interface with FIFOPnLStrategy (first-in-first-out cost basis -- matches tax lot rules) and AvgCostPnLStrategy (weighted average cost basis -- simpler, Zerodha default). |
| **Service** | `service/` | Business logic: TradingService (Facade -- place order, risk, match, settle), OrderService (order lifecycle, validation), MatchingService (wraps MatchingEngine, per-symbol routing), RiskService (chains risk checks), MarketDataService (real-time prices, candles, depth), PortfolioService (positions, P&L, holdings), AccountService (balance, margin, fund transfers), SettlementService (T+1 settlement simulation), NotificationService (order fills, price alerts). |
| **Repository** | `repository/` | Data access layer: OrderRepository, TradeRepository, PositionRepository, AccountRepository, StockRepository, MarketDataRepository interfaces with InMemory implementations. ConcurrentHashMap-backed stores. |
| **Controller** | `controller/` | REST-like entry point: TradingController maps requests to TradingService calls. |
| **Config** | `config/` | Factory wiring: AppConfig creates all objects and injects dependencies. No framework -- pure constructor injection. |
| **Display** | `display/` | TradingStatsDisplay: order counts by status, trade volume, portfolio P&L, order book depth. |
| **Exception** | `exception/` | Domain exceptions: TradingException (base), InsufficientMarginException, OrderRejectedException, InvalidOrderException, CircuitBreakerException. |

### Why Stock Trading Platform Is a Top-Tier Interview Question

```
Interviewer's checklist when evaluating your answer:

  1. Do you model the order book correctly (bids descending, asks ascending)?  --> Data Structure
  2. Is your matching algorithm price-time priority?                           --> Matching Engine
  3. Do you handle partial fills (order matched against multiple resting)?     --> Order Lifecycle
  4. Is the Order a proper state machine with guarded transitions?             --> State Machine
  5. Are risk checks pluggable (margin, position limit, circuit breaker)?      --> Strategy Pattern
  6. Is P&L calculation pluggable (FIFO vs average cost)?                      --> Strategy Pattern
  7. Are order book operations thread-safe (concurrent BUY/SELL)?              --> Concurrency
  8. Is TradingService a clean Facade over sub-services?                       --> Facade Pattern
  9. Can you add STOP_LOSS orders without changing the matching engine?         --> Open-Closed
  10. Do you separate matching from settlement?                                --> Separation of Concerns
```

---

## 2. Package Structure

```
com.systemdesign.trading
|
+-- model/
|   +-- Stock.java               -- symbol, name, exchange, lotSize, tickSize, circuitLimits
|   +-- Order.java               -- Builder, orderId, userId, symbol, side, type, price, qty, filledQty, status, timestamps
|   +-- OrderSide.java           -- enum: BUY, SELL
|   +-- OrderType.java           -- enum: MARKET, LIMIT, STOP_LOSS, STOP_LIMIT
|   +-- OrderStatus.java         -- enum: PENDING, OPEN, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED
|   +-- Trade.java               -- tradeId, buyOrderId, sellOrderId, symbol, price, quantity, timestamp
|   +-- Position.java            -- userId, symbol, quantity, avgBuyPrice, currentPrice, unrealizedPnL, realizedPnL
|   +-- Account.java             -- userId, balance, marginUsed, marginAvailable, holdings
|   +-- MarketData.java          -- symbol, ltp, bidPrice, askPrice, open, high, low, close, volume, timestamp
|   +-- OrderBookEntry.java      -- price, quantity, orderCount, timestamp (one price level)
|   +-- Watchlist.java           -- userId, name, symbols
|   +-- User.java                -- userId, name, email, accountType (RETAIL/INSTITUTIONAL)
|
+-- engine/
|   +-- MatchingEngine.java      -- THE CORE: order book + matching logic per symbol
|   +-- OrderBook.java           -- bids (TreeMap desc), asks (TreeMap asc), each price -> Queue<Order>
|   +-- PriceLevel.java          -- price, orders (LinkedList<Order>), totalQuantity
|
+-- strategy/
|   +-- order/
|   |   +-- OrderExecutionStrategy.java  -- interface: execute(Order, OrderBook) -> List<Trade>
|   |   +-- MarketOrderStrategy.java     -- match against best available, walk the book
|   |   +-- LimitOrderStrategy.java      -- match if price crosses, else rest in book
|   |
|   +-- risk/
|   |   +-- RiskCheckStrategy.java       -- interface: check(Order, Account, MarketData) -> RiskResult
|   |   +-- MarginCheckStrategy.java     -- sufficient funds/margin?
|   |   +-- PositionLimitStrategy.java   -- within position limits?
|   |   +-- CircuitBreakerStrategy.java  -- price within circuit limits?
|   |
|   +-- pricing/
|       +-- PnLStrategy.java             -- interface: calculate P&L
|       +-- FIFOPnLStrategy.java         -- first-in-first-out cost basis
|       +-- AvgCostPnLStrategy.java      -- weighted average cost basis
|
+-- service/
|   +-- TradingService.java      -- FACADE: place order -> risk -> match -> settle
|   +-- OrderService.java        -- order lifecycle, validation
|   +-- MatchingService.java     -- wraps MatchingEngine, per-symbol routing
|   +-- RiskService.java         -- chains risk checks
|   +-- MarketDataService.java   -- real-time prices, candles, depth
|   +-- PortfolioService.java    -- positions, P&L, holdings
|   +-- AccountService.java      -- balance, margin, fund transfers
|   +-- SettlementService.java   -- T+1 settlement simulation
|   +-- NotificationService.java -- order fills, price alerts
|
+-- repository/
|   +-- OrderRepository.java, InMemoryOrderRepository.java
|   +-- TradeRepository.java, InMemoryTradeRepository.java
|   +-- PositionRepository.java, InMemoryPositionRepository.java
|   +-- AccountRepository.java, InMemoryAccountRepository.java
|   +-- StockRepository.java, InMemoryStockRepository.java
|   +-- MarketDataRepository.java, InMemoryMarketDataRepository.java
|
+-- controller/
|   +-- TradingController.java
|
+-- config/
|   +-- AppConfig.java
|
+-- display/
|   +-- TradingStatsDisplay.java
|
+-- exception/
|   +-- TradingException.java
|   +-- InsufficientMarginException.java
|   +-- OrderRejectedException.java
|   +-- InvalidOrderException.java
|   +-- CircuitBreakerException.java
|
+-- StockTradingApp.java  -- Main demo: wires everything, runs trading scenarios
```

---

## 3. Class Diagram

```
+=====================================================================+
|         THE CORE PROBLEM: ORDER BOOK AND PRICE-TIME MATCHING         |
+=====================================================================+

  Imagine you're building the NSE/BSE matching engine. Every BUY and SELL
  order for a stock goes into an ORDER BOOK. The engine's job: match buyers
  and sellers at the best possible price, in the order they arrived.


  ORDER BOOK FOR "RELIANCE" (what the engine maintains):
  ┌─────────────────────────────────────────────────────────────┐
  │                      ORDER BOOK                              │
  │                                                              │
  │   BIDS (buyers, sorted HIGH→LOW)  ASKS (sellers, LOW→HIGH)  │
  │   ─────────────────────────────   ─────────────────────────  │
  │   Price     Qty    Orders         Price     Qty    Orders    │
  │   ─────     ───    ──────         ─────     ───    ──────    │
  │   2450.00   500    [O1, O5]       2451.00   300    [O2]      │
  │   2449.50   200    [O3]           2452.00   800    [O7, O9]  │
  │   2449.00   1000   [O8, O10]      2453.50   150    [O4]      │
  │   2448.00   350    [O6]           2455.00   600    [O11]     │
  │                                                              │
  │   Best Bid: 2450.00              Best Ask: 2451.00           │
  │   Spread: 2451.00 - 2450.00 = 1.00                          │
  └─────────────────────────────────────────────────────────────┘

  When a new BUY MARKET order arrives for 200 qty:
    → Match against Best Ask (2451.00)
    → O2 has 300 qty at 2451.00, fill 200 from it
    → Trade: 200 @ 2451.00
    → O2 now has 100 remaining at 2451.00


  DATA STRUCTURE CHOICE (the critical interview answer):
  ┌─────────────────────────────────────────────────────────────┐
  │                                                              │
  │   Bids: TreeMap<Double, PriceLevel> (DESCENDING comparator)  │
  │         firstEntry() = best bid (highest price)              │
  │                                                              │
  │   Asks: TreeMap<Double, PriceLevel> (ASCENDING / natural)    │
  │         firstEntry() = best ask (lowest price)               │
  │                                                              │
  │   PriceLevel: price + LinkedList<Order> (FIFO for time       │
  │               priority) + totalQuantity                      │
  │                                                              │
  │   INSERT: O(log N) where N = number of distinct price levels │
  │   BEST PRICE: O(1) via firstEntry()                          │
  │   MATCH AT PRICE: O(1) — dequeue from head of LinkedList     │
  │                                                              │
  └─────────────────────────────────────────────────────────────┘


╔═══════════════════════════════════════════════════════════════════════════════════╗
║              ORDER LIFECYCLE STATE MACHINE (The Core Concept)                      ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    ┌─────────┐  validate()   ┌────────┐  match()    ┌──────────────────┐
    │ PENDING │──────────────→│  OPEN  │────────────→│ PARTIALLY_FILLED │
    └─────────┘               └────────┘             └──────────────────┘
         │                        │                          │
         │ reject()               │ match(full)              │ match(remaining)
         │                        │                          │
         ▼                        ▼                          ▼
    ┌──────────┐              ┌────────┐              ┌────────┐
    │ REJECTED │              │ FILLED │              │ FILLED │
    └──────────┘              └────────┘              └────────┘
                                  │
                                  │ cancel()
                                  │ (only OPEN/PARTIAL)
                                  ▼
                             ┌───────────┐
                             │ CANCELLED │
                             └───────────┘

    Valid transitions (enforced by Order.transitionTo()):
      PENDING            → OPEN, REJECTED
      OPEN               → PARTIALLY_FILLED, FILLED, CANCELLED
      PARTIALLY_FILLED   → FILLED, CANCELLED
      FILLED             → (terminal state)
      CANCELLED          → (terminal state)
      REJECTED           → (terminal state)


╔═══════════════════════════════════════════════════════════════════════════════════╗
║              ORDER BOOK INTERNALS (The Data Structure That Matters)                ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    OrderBook
    ┌─────────────────────────────────────────────────────────────────┐
    │  - symbol: String                                                │
    │  - bids: TreeMap<Double, PriceLevel> (DESCENDING)                │
    │  - asks: TreeMap<Double, PriceLevel> (ASCENDING)                 │
    │  - orderIndex: Map<String, Order> (orderId → Order for cancel)   │
    ├─────────────────────────────────────────────────────────────────┤
    │  + addOrder(order): void                                         │
    │  + removeOrder(orderId): Order                                   │
    │  + getBestBid(): PriceLevel                                      │
    │  + getBestAsk(): PriceLevel                                      │
    │  + getBidLevels(depth): List<PriceLevel>                         │
    │  + getAskLevels(depth): List<PriceLevel>                         │
    │  + getSpread(): double                                           │
    │  + getTotalBidVolume(): long                                     │
    │  + getTotalAskVolume(): long                                     │
    └─────────────────────────────────────────────────────────────────┘
            │                                      │
            ▼                                      ▼
    PriceLevel (2450.00)                  PriceLevel (2451.00)
    ┌─────────────────────┐               ┌─────────────────────┐
    │ price: 2450.00      │               │ price: 2451.00      │
    │ orders: [O1]→[O5]   │               │ orders: [O2]        │
    │ totalQty: 500       │               │ totalQty: 300       │
    └─────────────────────┘               └─────────────────────┘


╔═══════════════════════════════════════════════════════════════════════════════════╗
║              ORDER EXECUTION STRATEGY HIERARCHY (Strategy Pattern)                 ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    +-----------------------------------------------------------+
    |          <<interface>>  OrderExecutionStrategy             |
    |-----------------------------------------------------------|
    | + execute(order: Order, book: OrderBook): List<Trade>      |
    | + getStrategyName(): String                                |
    | + supports(orderType: OrderType): boolean                  |
    +-----------------------------------------------------------+
          ^                           ^
          |                           |
     implements                  implements
          |                           |
    +-----+-----------+   +-----------+----------+
    | MarketOrder     |   | LimitOrder           |
    |  Strategy       |   |  Strategy            |
    |-----------------|   |----------------------|
    | Match against   |   | If price crosses:    |
    | best available  |   |   match immediately  |
    | price. Walk the |   | Else:                |
    | book until qty  |   |   add to book as     |
    | is filled or    |   |   resting order,     |
    | book exhausted. |   |   wait for counter-  |
    | No resting.     |   |   party to arrive.   |
    +-----------------+   +----------------------+


╔═══════════════════════════════════════════════════════════════════════════════════╗
║              RISK CHECK STRATEGY HIERARCHY (Strategy Pattern)                     ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    +-----------------------------------------------------------+
    |          <<interface>>  RiskCheckStrategy                  |
    |-----------------------------------------------------------|
    | + check(order: Order, acct: Account,                      |
    |         mktData: MarketData): RiskResult                  |
    | + getCheckName(): String                                  |
    +-----------------------------------------------------------+
          ^                   ^                    ^
          |                   |                    |
     implements          implements           implements
          |                   |                    |
    +-----+--------+   +-----+--------+   +-------+--------+
    | MarginCheck  |   | PositionLimit|   | CircuitBreaker |
    |  Strategy    |   |  Strategy    |   |  Strategy      |
    |--------------|   |--------------|   |----------------|
    | BUY: check   |   | Per-symbol   |   | Stock has      |
    |  balance >=  |   |  limit:      |   |  upper/lower   |
    |  price * qty |   |  max 10000   |   |  circuit limit |
    |  + margin    |   |  shares      |   |  (e.g., +/-20%)|
    |              |   | Total:       |   | Reject if      |
    | SELL: check  |   |  max 50000   |   |  order price   |
    |  holdings >= |   |  shares      |   |  outside range |
    |  qty         |   |  across all  |   |                |
    +--------------+   +--------------+   +----------------+


╔═══════════════════════════════════════════════════════════════════════════════════╗
║              P&L STRATEGY HIERARCHY (Strategy Pattern)                             ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    +-----------------------------------------------------------+
    |          <<interface>>  PnLStrategy                        |
    |-----------------------------------------------------------|
    | + calculateRealizedPnL(trades, position): double          |
    | + calculateUnrealizedPnL(position, currentPrice): double  |
    | + getStrategyName(): String                                |
    +-----------------------------------------------------------+
          ^                           ^
          |                           |
     implements                  implements
          |                           |
    +-----+-----------+   +-----------+----------+
    | FIFOPnL        |   | AvgCostPnL           |
    |  Strategy       |   |  Strategy            |
    |-----------------|   |----------------------|
    | Match sells     |   | avgBuyPrice =        |
    | against earliest|   |  totalCost /         |
    | buys first.     |   |  totalQty            |
    | Tax-friendly in |   |                      |
    | many countries. |   | PnL per share =      |
    |                 |   |  sellPrice -          |
    | Tracks a queue  |   |  avgBuyPrice          |
    | of purchase     |   |                      |
    | lots.           |   | Simpler, used by     |
    |                 |   | Zerodha by default.  |
    +-----------------+   +----------------------+


╔═══════════════════════════════════════════════════════════════════════════════════╗
║                          SERVICE LAYER (Facade Pattern)                            ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    ┌─────────────────────────────────────────────────────────────────────┐
    │                    TradingController                                  │
    │  placeOrder() | cancelOrder() | getPortfolio() | getMarketData()    │
    └────────┬────────────────┬──────────────┬──────────────┬─────────────┘
             │                │              │              │
             ▼                ▼              ▼              ▼
    ┌─────────────────────────────────────────────────────────────────────┐
    │                    TradingService (FACADE)                           │
    │  Orchestrates: validate → risk → match → settle → notify            │
    └──┬──────────┬───────────┬──────────┬──────────┬──────────┬────────┘
       │          │           │          │          │          │
       ▼          ▼           ▼          ▼          ▼          ▼
    Order      Risk       Matching   Portfolio  Settlement Notification
    Service    Service    Service    Service    Service    Service
       │          │           │          │          │          │
       ▼          ▼           ▼          ▼          ▼          ▼
    Order      Risk       Matching   Position   Account    Notification
    Repository Strategy   Engine     Repository Repository (alerts)
               (pluggable) (per-symbol)

    ┌─────────────────────────────────────────────────────────────────────┐
    │                SUPPORTING SERVICES                                   │
    │                                                                      │
    │  MarketDataService       -- real-time prices, order book depth       │
    │  AccountService          -- balance, margin, fund transfers          │
    │  TradingStatsDisplay     -- formatted stats for monitoring           │
    └─────────────────────────────────────────────────────────────────────┘
```

---

## 4. Entity Design

### 4.1 OrderSide (Enum)

```java
/**
 * Represents the direction of an order: buying or selling.
 *
 * WHY AN ENUM:
 *   - Only two valid values. String "BUY"/"SELL" invites typos.
 *   - The matching engine uses this to decide which side of the
 *     order book to place the order on.
 *
 * Used by:
 *   - Order: every order has a side
 *   - MatchingEngine: BUY orders match against asks, SELL against bids
 *   - MarginCheckStrategy: BUY checks balance, SELL checks holdings
 */
public enum OrderSide {
    BUY,    // buyer wants to purchase shares
    SELL    // seller wants to sell shares
}
```

### 4.2 OrderType (Enum)

```java
/**
 * Supported order types. Each type has different matching behavior.
 *
 * MARKET:     Execute immediately at best available price. No resting.
 * LIMIT:      Execute at specified price or better. May rest in book.
 * STOP_LOSS:  Becomes MARKET order when price hits trigger price.
 * STOP_LIMIT: Becomes LIMIT order when price hits trigger price.
 *
 * INTERVIEW NOTE:
 *   "What order types does your system support?" is a common follow-up.
 *   MARKET and LIMIT are the core two. STOP variants are extensions
 *   that demonstrate Open-Closed principle -- add new OrderType enum
 *   value + new strategy implementation, existing code untouched.
 *
 * Used by:
 *   - Order: determines which execution strategy is selected
 *   - MatchingService: routes to correct OrderExecutionStrategy
 */
public enum OrderType {
    MARKET,       // execute NOW at best available price
    LIMIT,        // execute at this price or better, else rest in book
    STOP_LOSS,    // trigger → becomes MARKET when LTP <= stopPrice (sell) or >= stopPrice (buy)
    STOP_LIMIT    // trigger → becomes LIMIT when LTP hits stopPrice
}
```

### 4.3 OrderStatus (Enum with Transition Validation)

```java
/**
 * Order lifecycle states with valid transition rules.
 *
 * STATE MACHINE DESIGN:
 *   Each enum value knows which states it can transition TO.
 *   This prevents illegal transitions like FILLED → OPEN or REJECTED → CANCELLED.
 *   The Order.transitionTo() method enforces this.
 *
 * WHY IN THE ENUM (not in Order class):
 *   - Transition rules are PROPERTIES of the state, not the order
 *   - Adding a new state (e.g., EXPIRED) only requires adding one enum value
 *     with its allowed transitions, not modifying Order class
 *   - Single source of truth for state machine rules
 *
 * Used by:
 *   - Order.transitionTo(): validates transition before applying
 *   - OrderService: manages order lifecycle
 *   - TradingController: filters orders by status
 */
public enum OrderStatus {
    PENDING(Set.of("OPEN", "REJECTED")),
    OPEN(Set.of("PARTIALLY_FILLED", "FILLED", "CANCELLED")),
    PARTIALLY_FILLED(Set.of("FILLED", "CANCELLED")),
    FILLED(Set.of()),           // terminal -- no further transitions
    CANCELLED(Set.of()),        // terminal
    REJECTED(Set.of());         // terminal

    private final Set<String> allowedTransitions;

    OrderStatus(Set<String> allowedTransitions) {
        this.allowedTransitions = allowedTransitions;
    }

    /**
     * Can this status transition to the given target?
     * Used by Order.transitionTo() to guard illegal transitions.
     */
    public boolean canTransitionTo(OrderStatus target) {
        return allowedTransitions.contains(target.name());
    }
}
```

### 4.4 Stock

```java
/**
 * Represents a tradable stock/security on an exchange.
 *
 * KEY FIELDS:
 *   - lotSize: minimum tradable quantity (e.g., 1 for equities, 50 for F&O)
 *   - tickSize: minimum price increment (e.g., 0.05 for NSE equities)
 *   - upperCircuitLimit / lowerCircuitLimit: max price swing allowed per day
 *     (e.g., RELIANCE at 2500, upper=3000, lower=2000 for +/-20%)
 *
 * CIRCUIT LIMITS (real exchange concept):
 *   NSE/BSE set daily price bands. If a stock hits the upper circuit,
 *   no more BUY orders above that price. If it hits lower circuit,
 *   no more SELL orders below it. Our CircuitBreakerStrategy checks this.
 *
 * Used by:
 *   - CircuitBreakerStrategy: validates order price within limits
 *   - OrderService: validates lotSize and tickSize compliance
 *   - MarketDataService: tracks per-stock market data
 */
public class Stock {
    private final String symbol;               // "RELIANCE", "TCS", "INFY"
    private final String name;                 // "Reliance Industries Limited"
    private final String exchange;             // "NSE", "BSE"
    private final int lotSize;                 // minimum tradable qty (1 for equity)
    private final double tickSize;             // minimum price increment (0.05)
    private final double upperCircuitLimit;    // max price for the day
    private final double lowerCircuitLimit;    // min price for the day

    public Stock(String symbol, String name, String exchange,
                 int lotSize, double tickSize,
                 double upperCircuitLimit, double lowerCircuitLimit) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol cannot be null or blank");
        }
        if (lotSize <= 0) {
            throw new IllegalArgumentException("lotSize must be positive");
        }
        if (tickSize <= 0) {
            throw new IllegalArgumentException("tickSize must be positive");
        }
        if (lowerCircuitLimit >= upperCircuitLimit) {
            throw new IllegalArgumentException("lowerCircuit must be < upperCircuit");
        }
        this.symbol = symbol;
        this.name = name;
        this.exchange = exchange;
        this.lotSize = lotSize;
        this.tickSize = tickSize;
        this.upperCircuitLimit = upperCircuitLimit;
        this.lowerCircuitLimit = lowerCircuitLimit;
    }

    // ─── Getters ────────────────────────────────────────────────────────

    public String getSymbol() { return symbol; }
    public String getName() { return name; }
    public String getExchange() { return exchange; }
    public int getLotSize() { return lotSize; }
    public double getTickSize() { return tickSize; }
    public double getUpperCircuitLimit() { return upperCircuitLimit; }
    public double getLowerCircuitLimit() { return lowerCircuitLimit; }

    /**
     * Is the given price a valid multiple of the tick size?
     * e.g., tickSize=0.05 → valid: 100.00, 100.05, 100.10
     *                       invalid: 100.03, 100.07
     */
    public boolean isValidPrice(double price) {
        double remainder = price % tickSize;
        return remainder < 0.0001 || (tickSize - remainder) < 0.0001;
    }

    /**
     * Is the given quantity a valid multiple of the lot size?
     * e.g., lotSize=50 → valid: 50, 100, 150. Invalid: 25, 75.
     */
    public boolean isValidQuantity(int quantity) {
        return quantity > 0 && quantity % lotSize == 0;
    }

    /** Is the given price within today's circuit limits? */
    public boolean isWithinCircuitLimits(double price) {
        return price >= lowerCircuitLimit && price <= upperCircuitLimit;
    }

    @Override
    public String toString() {
        return String.format("Stock{%s (%s) on %s, lot=%d, tick=%.2f, circuit=[%.2f, %.2f]}",
            symbol, name, exchange, lotSize, tickSize, lowerCircuitLimit, upperCircuitLimit);
    }
}
```

### 4.5 Order (Builder Pattern + State Machine)

```java
/**
 * Represents a trading order with full lifecycle management.
 *
 * BUILDER PATTERN:
 *   Order has many fields (12+). A constructor with 12 parameters is unreadable.
 *   The Builder makes construction clear and lets us set optional fields.
 *
 * STATE MACHINE:
 *   Order.transitionTo() enforces valid state transitions using OrderStatus rules.
 *   This prevents bugs like: filling an already cancelled order.
 *
 * PARTIAL FILLS:
 *   A 1000-qty order might be filled in three trades: 300 + 500 + 200.
 *   filledQty tracks cumulative fill. getRemainingQty() = qty - filledQty.
 *
 * CALL CHAIN:
 *   TradingService.placeOrder(...)
 *     → new Order.Builder(...).build()         // create with PENDING status
 *     → order.transitionTo(OPEN)               // after validation + risk
 *     → matchingEngine.match(order)            // may produce trades
 *     → order.fill(tradeQty)                   // update filledQty
 *     → order.transitionTo(FILLED/PARTIAL)     // based on remaining qty
 *
 * Used by:
 *   - OrderBook: stores resting orders at price levels
 *   - MatchingEngine: matches orders against each other
 *   - OrderService: lifecycle management
 *   - RiskService: validates before submission
 */
public class Order {

    private final String orderId;
    private final String userId;
    private final String symbol;
    private final OrderSide side;
    private final OrderType type;
    private final double price;       // 0.0 for MARKET orders (any price)
    private final int quantity;
    private int filledQty;
    private OrderStatus status;
    private final Instant createdAt;
    private Instant updatedAt;

    // ─── Private constructor — use Builder ──────────────────────────────

    private Order(Builder builder) {
        this.orderId = builder.orderId;
        this.userId = builder.userId;
        this.symbol = builder.symbol;
        this.side = builder.side;
        this.type = builder.type;
        this.price = builder.price;
        this.quantity = builder.quantity;
        this.filledQty = 0;
        this.status = OrderStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    // ─── Builder ────────────────────────────────────────────────────────

    /**
     * Builder for Order. Required fields: userId, symbol, side, type.
     * Price is required for LIMIT orders, ignored for MARKET orders.
     *
     * Usage:
     *   Order buyOrder = new Order.Builder("user-1", "RELIANCE", OrderSide.BUY, OrderType.LIMIT)
     *       .price(2450.00)
     *       .quantity(100)
     *       .build();
     */
    public static class Builder {
        private String orderId;
        private final String userId;
        private final String symbol;
        private final OrderSide side;
        private final OrderType type;
        private double price;
        private int quantity;

        public Builder(String userId, String symbol, OrderSide side, OrderType type) {
            this.orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
            this.userId = userId;
            this.symbol = symbol;
            this.side = side;
            this.type = type;
        }

        public Builder orderId(String orderId) { this.orderId = orderId; return this; }
        public Builder price(double price) { this.price = price; return this; }
        public Builder quantity(int quantity) { this.quantity = quantity; return this; }

        public Order build() {
            if (userId == null || userId.isBlank()) {
                throw new IllegalArgumentException("userId is required");
            }
            if (symbol == null || symbol.isBlank()) {
                throw new IllegalArgumentException("symbol is required");
            }
            if (quantity <= 0) {
                throw new IllegalArgumentException("quantity must be positive");
            }
            if (type == OrderType.LIMIT && price <= 0) {
                throw new IllegalArgumentException("LIMIT order requires positive price");
            }
            return new Order(this);
        }
    }

    // ─── State Machine ──────────────────────────────────────────────────

    /**
     * Transition to a new status. Throws if transition is illegal.
     *
     * GUARDED TRANSITION:
     *   OPEN → FILLED?   YES (order fully matched)
     *   FILLED → OPEN?   NO! (IllegalStateException — can't un-fill)
     *
     * This is the key interview point: state transitions are ENFORCED,
     * not just documented. Prevents entire classes of bugs.
     */
    public void transitionTo(OrderStatus newStatus) {
        if (!this.status.canTransitionTo(newStatus)) {
            throw new IllegalStateException(
                String.format("Invalid transition: %s → %s for order %s",
                    this.status, newStatus, this.orderId));
        }
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }

    /**
     * Record a partial or full fill.
     * Called by MatchingEngine when a trade is executed.
     *
     * @param tradeQty the quantity filled in this trade
     * @throws IllegalArgumentException if fill exceeds remaining quantity
     */
    public void fill(int tradeQty) {
        if (tradeQty <= 0) {
            throw new IllegalArgumentException("fill quantity must be positive");
        }
        if (tradeQty > getRemainingQty()) {
            throw new IllegalArgumentException(
                String.format("fill %d exceeds remaining %d for order %s",
                    tradeQty, getRemainingQty(), orderId));
        }
        this.filledQty += tradeQty;
        this.updatedAt = Instant.now();
    }

    /** How many shares still need to be filled? */
    public int getRemainingQty() {
        return quantity - filledQty;
    }

    /** Is this order completely filled? */
    public boolean isFullyFilled() {
        return filledQty >= quantity;
    }

    /** Is this order still active (can receive fills)? */
    public boolean isActive() {
        return status == OrderStatus.OPEN || status == OrderStatus.PARTIALLY_FILLED;
    }

    // ─── Getters ────────────────────────────────────────────────────────

    public String getOrderId() { return orderId; }
    public String getUserId() { return userId; }
    public String getSymbol() { return symbol; }
    public OrderSide getSide() { return side; }
    public OrderType getType() { return type; }
    public double getPrice() { return price; }
    public int getQuantity() { return quantity; }
    public int getFilledQty() { return filledQty; }
    public OrderStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public String toString() {
        return String.format("Order{%s %s %s %d@%.2f [%d/%d filled] %s}",
            orderId, side, symbol, quantity, price, filledQty, quantity, status);
    }
}
```

### 4.6 Trade

```java
/**
 * Represents a completed trade — the result of two orders matching.
 *
 * A Trade is IMMUTABLE. Once two orders match, the trade record is permanent.
 * This is the audit trail. In a real exchange, trades are reported to the
 * clearing house and cannot be altered.
 *
 * RELATIONSHIP:
 *   - Every trade has exactly ONE buy order and ONE sell order
 *   - One order can produce MULTIPLE trades (partial fills)
 *   - Trade price = the resting order's price (price-time priority rule)
 *
 * Used by:
 *   - MatchingEngine: creates trades when orders match
 *   - SettlementService: settles trades at T+1
 *   - PortfolioService: updates positions based on trades
 *   - PnLStrategy: calculates realized P&L from trade history
 */
public record Trade(
    String tradeId,          // unique trade identifier
    String buyOrderId,       // the buy order that was matched
    String sellOrderId,      // the sell order that was matched
    String symbol,           // stock symbol
    double price,            // execution price (resting order's price)
    int quantity,            // number of shares traded
    Instant timestamp        // when the trade was executed
) {
    public Trade {
        if (tradeId == null || tradeId.isBlank()) {
            throw new IllegalArgumentException("tradeId cannot be blank");
        }
        if (price <= 0) {
            throw new IllegalArgumentException("trade price must be positive");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("trade quantity must be positive");
        }
    }

    /** Total value of this trade (price * quantity). */
    public double getValue() {
        return price * quantity;
    }

    @Override
    public String toString() {
        return String.format("Trade{%s: %s %d@%.2f = %.2f at %s}",
            tradeId, symbol, quantity, price, getValue(), timestamp);
    }
}
```

### 4.7 Position

```java
/**
 * Tracks a user's holdings in a specific stock.
 *
 * POSITION MANAGEMENT:
 *   - quantity > 0: LONG position (user owns shares)
 *   - quantity = 0: FLAT (no exposure)
 *   - quantity < 0: SHORT position (user has sold shares they don't own — margin trading)
 *
 * P&L TRACKING:
 *   - unrealizedPnL: paper profit/loss based on current market price
 *     formula: (currentPrice - avgBuyPrice) * quantity
 *   - realizedPnL: actual profit/loss from closed trades
 *     accumulated as trades settle
 *
 * Used by:
 *   - PortfolioService: manages positions, calculates P&L
 *   - PnLStrategy: different P&L calculation methods (FIFO vs avg cost)
 *   - PositionLimitStrategy: checks if new order exceeds position limits
 *   - AccountService: margin calculation based on open positions
 */
public class Position {
    private final String userId;
    private final String symbol;
    private int quantity;              // positive=long, negative=short, zero=flat
    private double avgBuyPrice;        // weighted average purchase price
    private double currentPrice;       // latest market price
    private double realizedPnL;        // accumulated realized P&L

    public Position(String userId, String symbol) {
        this.userId = userId;
        this.symbol = symbol;
        this.quantity = 0;
        this.avgBuyPrice = 0.0;
        this.currentPrice = 0.0;
        this.realizedPnL = 0.0;
    }

    /**
     * Add shares to this position (from a BUY trade).
     * Updates avgBuyPrice using weighted average formula.
     *
     * WEIGHTED AVERAGE:
     *   newAvg = (oldQty * oldAvg + newQty * newPrice) / (oldQty + newQty)
     *
     *   Example: hold 100 @ 2400, buy 50 @ 2500
     *     newAvg = (100*2400 + 50*2500) / 150 = (240000+125000)/150 = 2433.33
     */
    public void addShares(int qty, double price) {
        if (qty <= 0 || price <= 0) {
            throw new IllegalArgumentException("qty and price must be positive");
        }
        double totalCost = (this.quantity * this.avgBuyPrice) + (qty * price);
        this.quantity += qty;
        this.avgBuyPrice = totalCost / this.quantity;
    }

    /**
     * Remove shares from this position (from a SELL trade).
     * Does NOT change avgBuyPrice (that's the cost basis).
     * Calculates realized P&L for the sold shares.
     *
     * @return realized P&L for this sale
     */
    public double removeShares(int qty, double sellPrice) {
        if (qty <= 0) {
            throw new IllegalArgumentException("qty must be positive");
        }
        if (qty > this.quantity) {
            throw new IllegalArgumentException(
                String.format("Cannot sell %d, only hold %d of %s", qty, this.quantity, symbol));
        }
        double pnl = (sellPrice - avgBuyPrice) * qty;
        this.realizedPnL += pnl;
        this.quantity -= qty;
        if (this.quantity == 0) {
            this.avgBuyPrice = 0.0;  // reset when flat
        }
        return pnl;
    }

    /** Update current market price and recalculate unrealized P&L. */
    public void updateMarketPrice(double newPrice) {
        this.currentPrice = newPrice;
    }

    /** Paper profit/loss: what you'd make if you sold everything right now. */
    public double getUnrealizedPnL() {
        if (quantity == 0) return 0.0;
        return (currentPrice - avgBuyPrice) * quantity;
    }

    /** Total P&L = realized + unrealized. */
    public double getTotalPnL() {
        return realizedPnL + getUnrealizedPnL();
    }

    // ─── Getters ────────────────────────────────────────────────────────

    public String getUserId() { return userId; }
    public String getSymbol() { return symbol; }
    public int getQuantity() { return quantity; }
    public double getAvgBuyPrice() { return avgBuyPrice; }
    public double getCurrentPrice() { return currentPrice; }
    public double getRealizedPnL() { return realizedPnL; }

    @Override
    public String toString() {
        return String.format("Position{%s %s: %d @ %.2f, current=%.2f, unrealized=%.2f, realized=%.2f}",
            userId, symbol, quantity, avgBuyPrice, currentPrice, getUnrealizedPnL(), realizedPnL);
    }
}
```

### 4.8 Account

```java
/**
 * Represents a user's trading account with balance and margin tracking.
 *
 * MARGIN CONCEPT:
 *   When you place a BUY order, the exchange doesn't wait for settlement.
 *   It "blocks" (reserves) margin from your available balance.
 *     balance = 100,000
 *     Order: BUY 100 RELIANCE @ 2500 → blocks 250,000... WAIT, that's more than balance!
 *     → InsufficientMarginException
 *
 *     Order: BUY 10 RELIANCE @ 2500 → blocks 25,000
 *     marginUsed = 25,000, marginAvailable = 75,000
 *
 *   When the order fills:
 *     balance -= 25,000 (money actually spent)
 *     marginUsed -= 25,000 (margin released)
 *     holdings += 10 RELIANCE
 *
 * Used by:
 *   - AccountService: manages balance, margin, fund transfers
 *   - MarginCheckStrategy: validates sufficient margin before order
 *   - SettlementService: deducts funds on trade settlement
 */
public class Account {
    private final String userId;
    private double balance;             // available cash
    private double marginUsed;          // cash reserved for open orders
    private final Map<String, Integer> holdings;  // symbol → quantity owned

    public Account(String userId, double initialBalance) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId cannot be blank");
        }
        if (initialBalance < 0) {
            throw new IllegalArgumentException("initial balance cannot be negative");
        }
        this.userId = userId;
        this.balance = initialBalance;
        this.marginUsed = 0.0;
        this.holdings = new ConcurrentHashMap<>();
    }

    /**
     * Block margin for a new order.
     * Reduces available balance, increases margin used.
     *
     * @throws InsufficientMarginException if insufficient funds
     */
    public void blockMargin(double amount) {
        if (amount > getMarginAvailable()) {
            throw new InsufficientMarginException(
                String.format("Need %.2f margin, only %.2f available for user %s",
                    amount, getMarginAvailable(), userId));
        }
        this.marginUsed += amount;
    }

    /** Release margin (order cancelled or filled). */
    public void releaseMargin(double amount) {
        this.marginUsed = Math.max(0, this.marginUsed - amount);
    }

    /** Debit balance (trade settlement). */
    public void debit(double amount) {
        if (amount > this.balance) {
            throw new InsufficientMarginException(
                String.format("Cannot debit %.2f, balance is %.2f for user %s",
                    amount, this.balance, userId));
        }
        this.balance -= amount;
    }

    /** Credit balance (sell settlement, fund deposit). */
    public void credit(double amount) {
        this.balance += amount;
    }

    /** Add shares to holdings (buy settlement). */
    public void addHolding(String symbol, int qty) {
        holdings.merge(symbol, qty, Integer::sum);
    }

    /** Remove shares from holdings (sell settlement). */
    public void removeHolding(String symbol, int qty) {
        int current = holdings.getOrDefault(symbol, 0);
        if (qty > current) {
            throw new IllegalArgumentException(
                String.format("Cannot remove %d %s, only hold %d", qty, symbol, current));
        }
        int remaining = current - qty;
        if (remaining == 0) {
            holdings.remove(symbol);
        } else {
            holdings.put(symbol, remaining);
        }
    }

    /** How much cash is available for new orders? */
    public double getMarginAvailable() {
        return balance - marginUsed;
    }

    /** How many shares of a given stock does this account hold? */
    public int getHolding(String symbol) {
        return holdings.getOrDefault(symbol, 0);
    }

    // ─── Getters ────────────────────────────────────────────────────────

    public String getUserId() { return userId; }
    public double getBalance() { return balance; }
    public double getMarginUsed() { return marginUsed; }
    public Map<String, Integer> getHoldings() { return Collections.unmodifiableMap(holdings); }

    @Override
    public String toString() {
        return String.format("Account{%s: balance=%.2f, marginUsed=%.2f, available=%.2f, holdings=%s}",
            userId, balance, marginUsed, getMarginAvailable(), holdings);
    }
}
```

### 4.9 MarketData

```java
/**
 * Real-time market data snapshot for a stock.
 *
 * OHLCV (Open-High-Low-Close-Volume):
 *   Standard market data format used by every exchange worldwide.
 *   - open: first trade price of the day
 *   - high: highest trade price of the day
 *   - low: lowest trade price of the day
 *   - close: last trade price (= LTP during market hours)
 *   - volume: total shares traded today
 *
 * BID/ASK:
 *   - bidPrice: best (highest) price someone is willing to buy at
 *   - askPrice: best (lowest) price someone is willing to sell at
 *   - spread = askPrice - bidPrice (tighter = more liquid stock)
 *
 * Used by:
 *   - MarketDataService: maintains and updates per-symbol data
 *   - CircuitBreakerStrategy: checks if order price within circuit limits
 *   - PortfolioService: uses LTP for unrealized P&L
 *   - NotificationService: price alert triggers
 */
public class MarketData {
    private final String symbol;
    private double ltp;          // last traded price
    private double bidPrice;     // best bid
    private double askPrice;     // best ask
    private double open;
    private double high;
    private double low;
    private double close;
    private long volume;
    private Instant timestamp;

    public MarketData(String symbol, double openPrice) {
        this.symbol = symbol;
        this.ltp = openPrice;
        this.open = openPrice;
        this.high = openPrice;
        this.low = openPrice;
        this.close = openPrice;
        this.bidPrice = 0.0;
        this.askPrice = 0.0;
        this.volume = 0;
        this.timestamp = Instant.now();
    }

    /**
     * Update market data after a trade executes.
     * Recalculates high, low, LTP, close, and volume.
     */
    public void onTrade(double tradePrice, int tradeQty) {
        this.ltp = tradePrice;
        this.close = tradePrice;
        this.high = Math.max(this.high, tradePrice);
        this.low = Math.min(this.low, tradePrice);
        this.volume += tradeQty;
        this.timestamp = Instant.now();
    }

    /** Update bid/ask from order book best prices. */
    public void updateBidAsk(double bid, double ask) {
        this.bidPrice = bid;
        this.askPrice = ask;
        this.timestamp = Instant.now();
    }

    /** Spread = askPrice - bidPrice. Tighter spread = more liquid. */
    public double getSpread() {
        if (bidPrice <= 0 || askPrice <= 0) return 0.0;
        return askPrice - bidPrice;
    }

    /** Day's price change from open. */
    public double getDayChange() {
        return ltp - open;
    }

    /** Day's percentage change from open. */
    public double getDayChangePercent() {
        if (open == 0) return 0.0;
        return ((ltp - open) / open) * 100.0;
    }

    // ─── Getters ────────────────────────────────────────────────────────

    public String getSymbol() { return symbol; }
    public double getLtp() { return ltp; }
    public double getBidPrice() { return bidPrice; }
    public double getAskPrice() { return askPrice; }
    public double getOpen() { return open; }
    public double getHigh() { return high; }
    public double getLow() { return low; }
    public double getClose() { return close; }
    public long getVolume() { return volume; }
    public Instant getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return String.format("MarketData{%s: LTP=%.2f, Bid=%.2f, Ask=%.2f, O=%.2f H=%.2f L=%.2f C=%.2f, Vol=%d}",
            symbol, ltp, bidPrice, askPrice, open, high, low, close, volume);
    }
}
```

### 4.10 OrderBookEntry

```java
/**
 * A snapshot of one price level in the order book (for display/API).
 *
 * This is what users see on Zerodha's "Market Depth" screen:
 *   BID: 2450.00  x 500 (3 orders)
 *   BID: 2449.50  x 200 (1 order)
 *   ASK: 2451.00  x 300 (2 orders)
 *   ASK: 2452.00  x 800 (4 orders)
 *
 * This is a READ-ONLY snapshot. The actual order book uses PriceLevel
 * internally (which has the mutable order queue).
 *
 * Used by:
 *   - MarketDataService: getOrderBookDepth() returns List<OrderBookEntry>
 *   - TradingStatsDisplay: renders order book depth table
 */
public record OrderBookEntry(
    double price,          // price level
    int quantity,          // total quantity at this price
    int orderCount,        // number of orders at this price
    Instant timestamp      // when this snapshot was taken
) {
    @Override
    public String toString() {
        return String.format("%.2f x %d (%d orders)", price, quantity, orderCount);
    }
}
```

### 4.11 Watchlist

```java
/**
 * A user's watchlist of tracked stock symbols.
 *
 * Zerodha allows users to create named watchlists (e.g., "Nifty 50",
 * "My Picks") and track real-time prices for those symbols.
 *
 * Used by:
 *   - MarketDataService: streams prices for watchlist symbols
 *   - TradingController: CRUD operations on watchlists
 */
public class Watchlist {
    private final String userId;
    private final String name;
    private final List<String> symbols;

    public Watchlist(String userId, String name) {
        this.userId = userId;
        this.name = name;
        this.symbols = new ArrayList<>();
    }

    public void addSymbol(String symbol) {
        if (!symbols.contains(symbol)) {
            symbols.add(symbol);
        }
    }

    public void removeSymbol(String symbol) {
        symbols.remove(symbol);
    }

    public String getUserId() { return userId; }
    public String getName() { return name; }
    public List<String> getSymbols() { return Collections.unmodifiableList(symbols); }

    @Override
    public String toString() {
        return String.format("Watchlist{%s: %s, symbols=%s}", userId, name, symbols);
    }
}
```

### 4.12 User

```java
/**
 * Represents a platform user (trader).
 *
 * Account types affect trading limits:
 *   RETAIL:        lower position limits, standard margin requirements
 *   INSTITUTIONAL: higher limits, potentially lower margin (prime brokerage)
 *
 * Used by:
 *   - PositionLimitStrategy: different limits per account type
 *   - AccountService: margin calculations differ by type
 */
public class User {
    private final String userId;
    private final String name;
    private final String email;
    private final AccountType accountType;

    public enum AccountType { RETAIL, INSTITUTIONAL }

    public User(String userId, String name, String email, AccountType accountType) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId cannot be blank");
        }
        this.userId = userId;
        this.name = name;
        this.email = email;
        this.accountType = accountType;
    }

    public String getUserId() { return userId; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public AccountType getAccountType() { return accountType; }

    @Override
    public String toString() {
        return String.format("User{%s, %s, %s}", userId, name, accountType);
    }
}
```

---

## 5. Interface Contracts

### 5.1 OrderExecutionStrategy (Strategy Interface)

```java
/**
 * Defines the contract for order execution strategies.
 *
 * STRATEGY PATTERN: Each order type (MARKET, LIMIT) has its own execution
 * logic. The MatchingEngine selects the correct strategy based on OrderType,
 * without knowing the internal details of how matching works for each type.
 *
 * CONTRACT GUARANTEES:
 *   - execute() returns a List<Trade> (possibly empty, never null)
 *   - If no match is possible, returns empty list (order rests in book or rejected)
 *   - The strategy MAY modify the OrderBook (add resting orders for LIMIT)
 *   - The strategy MUST update Order.filledQty for any fills
 *
 * CALL CHAIN:
 *   MatchingService.submitOrder(order)
 *     → matchingEngine.match(order)
 *       → strategies.get(order.getType())         // lookup by OrderType
 *         .execute(order, orderBook)               // delegate to strategy
 *         → List<Trade>                            // trades produced
 *
 * WHY NOT JUST IF-ELSE IN THE ENGINE:
 *   if (order.getType() == MARKET) { ... 50 lines ... }
 *   else if (order.getType() == LIMIT) { ... 60 lines ... }
 *   else if (order.getType() == STOP_LOSS) { ... 40 lines ... }
 *   → 150+ lines of interleaved logic, hard to test individually.
 *   With strategies, each is a focused, independently testable class.
 */
public interface OrderExecutionStrategy {

    /**
     * Execute an order against the given order book.
     *
     * @param order the incoming order to execute
     * @param book  the order book for this symbol
     * @return list of trades generated (empty if no match)
     */
    List<Trade> execute(Order order, OrderBook book);

    /** Human-readable strategy name for logging. */
    String getStrategyName();

    /** Does this strategy handle the given order type? */
    boolean supports(OrderType orderType);
}
```

### 5.2 RiskCheckStrategy (Strategy Interface)

```java
/**
 * Defines the contract for pre-trade risk checks.
 *
 * STRATEGY PATTERN: Each risk check (margin, position limit, circuit breaker)
 * is an independent, pluggable check. RiskService chains them all — if ANY
 * check fails, the order is rejected. This is defense-in-depth.
 *
 * CONTRACT GUARANTEES:
 *   - check() returns a RiskResult (never null, never throws for business failures)
 *   - RiskResult.isPassed() = true means this check approves the order
 *   - RiskResult.isPassed() = false means this check REJECTS the order
 *     with a human-readable reason (e.g., "Insufficient margin: need 250000, have 100000")
 *   - Each check is INDEPENDENT — one check's result does not affect another
 *
 * CALL CHAIN:
 *   TradingService.placeOrder(...)
 *     → riskService.validate(order, account, marketData)
 *       → for each RiskCheckStrategy in chain:
 *           strategy.check(order, account, marketData)
 *           → RiskResult{passed=true/false, reason="..."}
 *       → if ANY fails → order REJECTED
 */
public interface RiskCheckStrategy {

    /**
     * Check if the order passes this risk validation.
     *
     * @param order     the order to validate
     * @param account   the user's account (balance, margin, holdings)
     * @param marketData current market data for the stock
     * @return RiskResult with pass/fail and reason
     */
    RiskResult check(Order order, Account account, MarketData marketData);

    /** Human-readable name for this check (for logging/display). */
    String getCheckName();
}

/**
 * Result of a risk check. Immutable value object.
 *
 * Usage:
 *   RiskResult result = marginCheck.check(order, account, marketData);
 *   if (!result.isPassed()) {
 *       throw new OrderRejectedException(result.getReason());
 *   }
 */
public record RiskResult(boolean passed, String reason, String checkName) {

    public static RiskResult pass(String checkName) {
        return new RiskResult(true, "PASSED", checkName);
    }

    public static RiskResult fail(String checkName, String reason) {
        return new RiskResult(false, reason, checkName);
    }

    public boolean isPassed() { return passed; }
}
```

### 5.3 PnLStrategy (Strategy Interface)

```java
/**
 * Defines the contract for P&L calculation strategies.
 *
 * STRATEGY PATTERN: Different P&L methods produce different numbers
 * for the same trades. The user/admin selects which method to use.
 *
 *   EXAMPLE — same trades, different P&L:
 *     BUY 100 @ 2400 (lot 1)
 *     BUY 100 @ 2500 (lot 2)
 *     SELL 100 @ 2550
 *
 *     FIFO: sell matches lot 1 (earliest) → P&L = (2550-2400)*100 = +15,000
 *     AVG:  avgCost = (2400+2500)/2 = 2450 → P&L = (2550-2450)*100 = +10,000
 *
 *   Same trades, but FIFO shows higher profit because it matched against
 *   the cheaper lot. This matters for tax reporting.
 *
 * CALL CHAIN:
 *   PortfolioService.calculatePnL(userId, symbol)
 *     → pnlStrategy.calculateRealizedPnL(trades, position)
 *     → pnlStrategy.calculateUnrealizedPnL(position, currentPrice)
 */
public interface PnLStrategy {

    /**
     * Calculate realized P&L from completed trades.
     *
     * @param trades   list of trades for this user+symbol
     * @param position current position state
     * @return realized profit or loss (positive = profit)
     */
    double calculateRealizedPnL(List<Trade> trades, Position position);

    /**
     * Calculate unrealized P&L for open position.
     *
     * @param position     current position
     * @param currentPrice current market price
     * @return unrealized profit or loss
     */
    double calculateUnrealizedPnL(Position position, double currentPrice);

    /** Strategy name for display/selection. */
    String getStrategyName();
}
```

### 5.4 Repository Interfaces

```java
/**
 * Repository interfaces follow the same pattern across all entities.
 * Showing OrderRepository as the exemplar.
 *
 * WHY INTERFACES:
 *   - InMemoryOrderRepository for LLD/testing (ConcurrentHashMap)
 *   - JdbcOrderRepository for production (SQL database)
 *   - Services depend on the interface, not the implementation
 *   - Swap storage without touching business logic
 */
public interface OrderRepository {
    void save(Order order);
    Optional<Order> findById(String orderId);
    List<Order> findByUserId(String userId);
    List<Order> findBySymbol(String symbol);
    List<Order> findByStatus(OrderStatus status);
    List<Order> findAll();
}

public interface TradeRepository {
    void save(Trade trade);
    Optional<Trade> findById(String tradeId);
    List<Trade> findBySymbol(String symbol);
    List<Trade> findByOrderId(String orderId);
    List<Trade> findAll();
}

public interface PositionRepository {
    void save(Position position);
    Optional<Position> findByUserAndSymbol(String userId, String symbol);
    List<Position> findByUserId(String userId);
    List<Position> findAll();
}

public interface AccountRepository {
    void save(Account account);
    Optional<Account> findByUserId(String userId);
}

public interface StockRepository {
    void save(Stock stock);
    Optional<Stock> findBySymbol(String symbol);
    List<Stock> findAll();
}

public interface MarketDataRepository {
    void save(MarketData data);
    Optional<MarketData> findBySymbol(String symbol);
    List<MarketData> findAll();
}
```

---

## 6. Strategy Implementations

### 6.1 MarketOrderStrategy

```java
/**
 * Executes a MARKET order by matching against the best available prices.
 *
 * MARKET ORDER RULES:
 *   - BUY MARKET: match against best ask (lowest sell price), walk up the book
 *   - SELL MARKET: match against best bid (highest buy price), walk down the book
 *   - No resting: if the book is empty or insufficient, the unmatched portion
 *     is CANCELLED (market orders don't sit in the book)
 *   - Trade price = the resting order's price (NOT the market order's "price")
 *
 * ANTI-PATTERN — THE NAIVE APPROACH:
 * ─────────────────────────────────────────────────────────────────────
 *   // WRONG: only looks at one price level, ignores partial fills
 *   public List<Trade> execute(Order order, OrderBook book) {
 *       PriceLevel best = (order.getSide() == BUY) ? book.getBestAsk() : book.getBestBid();
 *       if (best == null) return List.of();
 *       Order resting = best.getOrders().peek();
 *       Trade trade = new Trade(order, resting, resting.getPrice(), order.getQuantity());
 *       return List.of(trade);   // BUG: what if resting has only 50 and we need 200?
 *   }
 *
 *   Problems:
 *     1. Does not handle partial fills (resting order has fewer shares)
 *     2. Does not walk the book (next price level if current exhausted)
 *     3. Does not update filledQty on either order
 *     4. Does not remove exhausted price levels
 *
 * CLEAN SOLUTION:
 * ─────────────────────────────────────────────────────────────────────
 */
public class MarketOrderStrategy implements OrderExecutionStrategy {

    @Override
    public List<Trade> execute(Order order, OrderBook book) {
        List<Trade> trades = new ArrayList<>();

        // ─── BUY MARKET: match against asks (sellers) ───────────────
        // ─── SELL MARKET: match against bids (buyers) ───────────────
        // The "opposite side" of the book is where counterparties live.

        while (order.getRemainingQty() > 0) {

            // Step 1: Get the best price level on the opposite side
            PriceLevel bestLevel = (order.getSide() == OrderSide.BUY)
                ? book.getBestAsk()    // buy → match against lowest ask
                : book.getBestBid();   // sell → match against highest bid

            // No more orders on opposite side → done (partial fill or no fill)
            if (bestLevel == null || bestLevel.isEmpty()) {
                break;
            }

            // Step 2: Match against orders at this price level (FIFO — time priority)
            while (order.getRemainingQty() > 0 && !bestLevel.isEmpty()) {

                Order restingOrder = bestLevel.peekFirstOrder();

                // Trade quantity = min of what we need and what they have
                int tradeQty = Math.min(order.getRemainingQty(),
                                        restingOrder.getRemainingQty());

                // Trade price = resting order's price (price-time priority rule)
                double tradePrice = restingOrder.getPrice();

                // Create the trade record
                Trade trade = createTrade(order, restingOrder, tradePrice, tradeQty);
                trades.add(trade);

                // Update both orders' fill quantities
                order.fill(tradeQty);
                restingOrder.fill(tradeQty);

                // If resting order is fully filled, remove it from the book
                if (restingOrder.isFullyFilled()) {
                    bestLevel.removeFirstOrder();
                    restingOrder.transitionTo(OrderStatus.FILLED);
                } else {
                    restingOrder.transitionTo(OrderStatus.PARTIALLY_FILLED);
                }
            }

            // Step 3: If this price level is now empty, remove it from the book
            if (bestLevel.isEmpty()) {
                book.removePriceLevel(order.getSide() == OrderSide.BUY
                    ? OrderSide.SELL   // remove from asks
                    : OrderSide.BUY);  // remove from bids
            }
        }

        // Market orders don't rest in the book. If not fully filled,
        // the remaining quantity is simply cancelled.
        return trades;
    }

    /**
     * Create a Trade, correctly assigning buyOrderId and sellOrderId
     * based on which order is the buyer and which is the seller.
     */
    private Trade createTrade(Order incoming, Order resting,
                              double price, int quantity) {
        String buyOrderId = (incoming.getSide() == OrderSide.BUY)
            ? incoming.getOrderId() : resting.getOrderId();
        String sellOrderId = (incoming.getSide() == OrderSide.SELL)
            ? incoming.getOrderId() : resting.getOrderId();

        return new Trade(
            "TRD-" + UUID.randomUUID().toString().substring(0, 8),
            buyOrderId,
            sellOrderId,
            incoming.getSymbol(),
            price,
            quantity,
            Instant.now()
        );
    }

    @Override
    public String getStrategyName() { return "MARKET_ORDER"; }

    @Override
    public boolean supports(OrderType orderType) {
        return orderType == OrderType.MARKET;
    }
}
```

### 6.2 LimitOrderStrategy

```java
/**
 * Executes a LIMIT order: match if price crosses, else rest in book.
 *
 * LIMIT ORDER RULES:
 *   - BUY LIMIT at 2450: match against asks <= 2450 (willing to pay UP TO 2450)
 *   - SELL LIMIT at 2450: match against bids >= 2450 (want AT LEAST 2450)
 *   - If no match (or partial match), the remaining quantity RESTS in the book
 *     as a passive order at the limit price
 *   - Trade price = resting order's price (same as market orders)
 *
 * ANTI-PATTERN — THE IF-ELSE MESS:
 * ─────────────────────────────────────────────────────────────────────
 *   // WRONG: mixing matching logic with book management in one giant method
 *   public List<Trade> execute(Order order, OrderBook book) {
 *       if (order.getSide() == BUY) {
 *           // 40 lines of buy matching logic
 *           if (book.getBestAsk() != null && book.getBestAsk().getPrice() <= order.getPrice()) {
 *               // match logic...
 *           }
 *           if (order.getRemainingQty() > 0) {
 *               // add to bids... but where? what about price level management?
 *               book.getBids().computeIfAbsent(order.getPrice(), k -> new PriceLevel(k));
 *               // forgot to handle SELL side...
 *           }
 *       } else {
 *           // 40 more lines of nearly-identical sell logic, copy-pasted
 *       }
 *   }
 *
 *   Problems:
 *     1. Duplicated BUY/SELL logic (copy-paste errors)
 *     2. Matching and book management interleaved
 *     3. Easy to forget edge cases (empty book, exact price match)
 *
 * CLEAN SOLUTION:
 * ─────────────────────────────────────────────────────────────────────
 *   Extract matching into a loop, parameterize by side.
 *   After matching, add remainder to book via OrderBook.addOrder().
 */
public class LimitOrderStrategy implements OrderExecutionStrategy {

    @Override
    public List<Trade> execute(Order order, OrderBook book) {
        List<Trade> trades = new ArrayList<>();

        // ─── Phase 1: AGGRESSIVE MATCHING ───────────────────────────
        // Try to match the limit order against existing orders on the
        // opposite side that have a "crossable" price.
        //
        //   BUY LIMIT 2450: cross if best ask <= 2450
        //   SELL LIMIT 2450: cross if best bid >= 2450

        while (order.getRemainingQty() > 0) {

            PriceLevel bestLevel = (order.getSide() == OrderSide.BUY)
                ? book.getBestAsk()
                : book.getBestBid();

            // No opposite side orders → stop matching, rest in book
            if (bestLevel == null || bestLevel.isEmpty()) {
                break;
            }

            // Price doesn't cross → stop matching, rest in book
            if (!pricesCross(order, bestLevel.getPrice())) {
                break;
            }

            // Match against orders at this price level
            while (order.getRemainingQty() > 0 && !bestLevel.isEmpty()) {

                Order restingOrder = bestLevel.peekFirstOrder();
                int tradeQty = Math.min(order.getRemainingQty(),
                                        restingOrder.getRemainingQty());
                double tradePrice = restingOrder.getPrice();

                Trade trade = createTrade(order, restingOrder, tradePrice, tradeQty);
                trades.add(trade);

                order.fill(tradeQty);
                restingOrder.fill(tradeQty);

                if (restingOrder.isFullyFilled()) {
                    bestLevel.removeFirstOrder();
                    restingOrder.transitionTo(OrderStatus.FILLED);
                } else {
                    restingOrder.transitionTo(OrderStatus.PARTIALLY_FILLED);
                }
            }

            if (bestLevel.isEmpty()) {
                book.removePriceLevel(order.getSide() == OrderSide.BUY
                    ? OrderSide.SELL : OrderSide.BUY);
            }
        }

        // ─── Phase 2: REST IN BOOK ─────────────────────────────────
        // If there's remaining quantity, add the order to the book
        // as a passive (resting) order at the limit price.

        if (order.getRemainingQty() > 0) {
            book.addOrder(order);
        }

        return trades;
    }

    /**
     * Do the order's limit price and the resting level's price "cross"?
     *   BUY @ 2450 crosses ask @ 2440 (willing to pay 2450, can get it for 2440)
     *   BUY @ 2450 does NOT cross ask @ 2460 (too expensive)
     *   SELL @ 2450 crosses bid @ 2460 (want 2450, someone will pay 2460)
     *   SELL @ 2450 does NOT cross bid @ 2440 (bid too low)
     */
    private boolean pricesCross(Order order, double restingPrice) {
        if (order.getSide() == OrderSide.BUY) {
            return restingPrice <= order.getPrice();   // ask <= my limit → I can afford it
        } else {
            return restingPrice >= order.getPrice();   // bid >= my limit → they pay enough
        }
    }

    private Trade createTrade(Order incoming, Order resting,
                              double price, int quantity) {
        String buyOrderId = (incoming.getSide() == OrderSide.BUY)
            ? incoming.getOrderId() : resting.getOrderId();
        String sellOrderId = (incoming.getSide() == OrderSide.SELL)
            ? incoming.getOrderId() : resting.getOrderId();

        return new Trade(
            "TRD-" + UUID.randomUUID().toString().substring(0, 8),
            buyOrderId, sellOrderId,
            incoming.getSymbol(), price, quantity, Instant.now()
        );
    }

    @Override
    public String getStrategyName() { return "LIMIT_ORDER"; }

    @Override
    public boolean supports(OrderType orderType) {
        return orderType == OrderType.LIMIT;
    }
}
```

### 6.3 MarginCheckStrategy

```java
/**
 * Risk check: does the user have sufficient margin/balance for this order?
 *
 * BUY ORDER: need balance >= price * quantity (or estimated cost for MARKET)
 * SELL ORDER: need holdings >= quantity (can't sell what you don't own)
 *
 * MARGIN CALCULATION:
 *   For LIMIT BUY: requiredMargin = limitPrice * quantity
 *   For MARKET BUY: requiredMargin = askPrice * quantity * 1.05
 *     (5% buffer because market price may slip during execution)
 *
 * CALL CHAIN:
 *   RiskService.validate(order, account, marketData)
 *     → marginCheck.check(order, account, marketData)
 *       → RiskResult{passed=true/false, reason="..."}
 */
public class MarginCheckStrategy implements RiskCheckStrategy {

    private static final double MARKET_ORDER_BUFFER = 1.05; // 5% slippage buffer

    @Override
    public RiskResult check(Order order, Account account, MarketData marketData) {

        if (order.getSide() == OrderSide.BUY) {
            // ─── BUY: check available margin ────────────────────────
            double requiredMargin;
            if (order.getType() == OrderType.MARKET) {
                // Market order: estimate cost from current ask + buffer
                double estimatedPrice = marketData.getAskPrice() > 0
                    ? marketData.getAskPrice()
                    : marketData.getLtp();
                requiredMargin = estimatedPrice * order.getQuantity() * MARKET_ORDER_BUFFER;
            } else {
                // Limit order: exact cost known
                requiredMargin = order.getPrice() * order.getQuantity();
            }

            if (account.getMarginAvailable() < requiredMargin) {
                return RiskResult.fail(getCheckName(),
                    String.format("Insufficient margin: need %.2f, available %.2f",
                        requiredMargin, account.getMarginAvailable()));
            }

        } else {
            // ─── SELL: check holdings ───────────────────────────────
            int holdings = account.getHolding(order.getSymbol());
            if (holdings < order.getQuantity()) {
                return RiskResult.fail(getCheckName(),
                    String.format("Insufficient holdings: need %d %s, hold %d",
                        order.getQuantity(), order.getSymbol(), holdings));
            }
        }

        return RiskResult.pass(getCheckName());
    }

    @Override
    public String getCheckName() { return "MARGIN_CHECK"; }
}
```

### 6.4 PositionLimitStrategy

```java
/**
 * Risk check: is the order within position limits?
 *
 * POSITION LIMITS:
 *   Exchanges impose limits on how many shares one entity can hold.
 *   This prevents market manipulation (cornering a stock).
 *
 *   - Per-symbol limit: max 10,000 shares of any single stock
 *   - Total limit: max 50,000 shares across all stocks
 *   - These limits can vary by account type (INSTITUTIONAL gets higher limits)
 */
public class PositionLimitStrategy implements RiskCheckStrategy {

    private final int perSymbolLimit;
    private final int totalLimit;
    private final PositionRepository positionRepository;

    public PositionLimitStrategy(int perSymbolLimit, int totalLimit,
                                 PositionRepository positionRepository) {
        this.perSymbolLimit = perSymbolLimit;
        this.totalLimit = totalLimit;
        this.positionRepository = positionRepository;
    }

    @Override
    public RiskResult check(Order order, Account account, MarketData marketData) {

        // Only check BUY orders (selling reduces position, always OK from limit perspective)
        if (order.getSide() == OrderSide.SELL) {
            return RiskResult.pass(getCheckName());
        }

        // ─── Per-symbol limit check ─────────────────────────────────
        int currentPosition = positionRepository
            .findByUserAndSymbol(order.getUserId(), order.getSymbol())
            .map(Position::getQuantity)
            .orElse(0);

        int projectedPosition = currentPosition + order.getQuantity();

        if (projectedPosition > perSymbolLimit) {
            return RiskResult.fail(getCheckName(),
                String.format("Position limit exceeded for %s: current=%d, order=%d, limit=%d",
                    order.getSymbol(), currentPosition, order.getQuantity(), perSymbolLimit));
        }

        // ─── Total position limit check ─────────────────────────────
        int totalPosition = positionRepository.findByUserId(order.getUserId())
            .stream()
            .mapToInt(Position::getQuantity)
            .sum();

        int projectedTotal = totalPosition + order.getQuantity();

        if (projectedTotal > totalLimit) {
            return RiskResult.fail(getCheckName(),
                String.format("Total position limit exceeded: current=%d, order=%d, limit=%d",
                    totalPosition, order.getQuantity(), totalLimit));
        }

        return RiskResult.pass(getCheckName());
    }

    @Override
    public String getCheckName() { return "POSITION_LIMIT"; }
}
```

### 6.5 CircuitBreakerStrategy

```java
/**
 * Risk check: is the order price within the stock's circuit limits?
 *
 * CIRCUIT LIMITS (real exchange mechanism):
 *   If RELIANCE closed at 2500 yesterday, and circuit limit is +/-20%:
 *     Upper circuit = 3000 (no BUY orders above this)
 *     Lower circuit = 2000 (no SELL orders below this)
 *
 *   If the stock hits circuit limit, trading is HALTED for that stock.
 *   This prevents flash crashes and manipulation.
 *
 *   MARKET orders bypass this check (they don't have a price).
 */
public class CircuitBreakerStrategy implements RiskCheckStrategy {

    private final StockRepository stockRepository;

    public CircuitBreakerStrategy(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    @Override
    public RiskResult check(Order order, Account account, MarketData marketData) {

        // Market orders don't have a price — can't check circuit limits
        if (order.getType() == OrderType.MARKET) {
            return RiskResult.pass(getCheckName());
        }

        Stock stock = stockRepository.findBySymbol(order.getSymbol())
            .orElse(null);

        if (stock == null) {
            return RiskResult.fail(getCheckName(),
                String.format("Unknown symbol: %s", order.getSymbol()));
        }

        if (!stock.isWithinCircuitLimits(order.getPrice())) {
            return RiskResult.fail(getCheckName(),
                String.format("Price %.2f outside circuit limits [%.2f, %.2f] for %s",
                    order.getPrice(),
                    stock.getLowerCircuitLimit(),
                    stock.getUpperCircuitLimit(),
                    order.getSymbol()));
        }

        return RiskResult.pass(getCheckName());
    }

    @Override
    public String getCheckName() { return "CIRCUIT_BREAKER"; }
}
```

### 6.6 FIFOPnLStrategy

```java
/**
 * FIFO (First-In-First-Out) P&L calculation.
 *
 * When you sell shares, match them against the EARLIEST buy lots first.
 *
 * EXAMPLE:
 *   BUY 100 @ 2400 (lot A — earliest)
 *   BUY 100 @ 2500 (lot B — later)
 *   SELL 100 @ 2550
 *
 *   FIFO: sell matches lot A (earliest buy)
 *   Realized P&L = (2550 - 2400) * 100 = +15,000
 *
 *   Remaining position: 100 shares at avg cost 2500 (lot B)
 *
 * WHY FIFO:
 *   - Tax-friendly in many jurisdictions (long-term capital gains)
 *   - Required by some accounting standards
 *   - More complex to implement (must track individual lots)
 */
public class FIFOPnLStrategy implements PnLStrategy {

    @Override
    public double calculateRealizedPnL(List<Trade> trades, Position position) {
        // Maintain a queue of buy lots: (qty, price) pairs
        Queue<double[]> buyLots = new LinkedList<>();
        double totalRealizedPnL = 0.0;

        for (Trade trade : trades) {
            // Determine if this user was the buyer or seller in this trade
            // For simplicity, we track based on the position's userId
            // In production, you'd check trade.buyOrderId/sellOrderId

            if (isBuyTrade(trade, position.getUserId())) {
                // Add to buy lot queue
                buyLots.offer(new double[]{trade.quantity(), trade.price()});
            } else {
                // SELL: match against earliest buy lots (FIFO)
                int remainingToSell = trade.quantity();

                while (remainingToSell > 0 && !buyLots.isEmpty()) {
                    double[] lot = buyLots.peek();
                    int lotQty = (int) lot[0];
                    double lotPrice = lot[1];

                    int matched = Math.min(remainingToSell, lotQty);
                    totalRealizedPnL += (trade.price() - lotPrice) * matched;
                    remainingToSell -= matched;

                    if (matched >= lotQty) {
                        buyLots.poll();   // lot fully consumed
                    } else {
                        lot[0] -= matched; // lot partially consumed
                    }
                }
            }
        }

        return totalRealizedPnL;
    }

    @Override
    public double calculateUnrealizedPnL(Position position, double currentPrice) {
        if (position.getQuantity() == 0) return 0.0;
        return (currentPrice - position.getAvgBuyPrice()) * position.getQuantity();
    }

    private boolean isBuyTrade(Trade trade, String userId) {
        // Simplified: in production, look up the order to check userId
        return trade.buyOrderId().contains(userId);
    }

    @Override
    public String getStrategyName() { return "FIFO"; }
}
```

### 6.7 AvgCostPnLStrategy

```java
/**
 * Average Cost P&L calculation (Zerodha default).
 *
 * All buy lots are averaged into a single cost basis.
 *
 * EXAMPLE (same trades as FIFO):
 *   BUY 100 @ 2400
 *   BUY 100 @ 2500
 *   → avgCost = (100*2400 + 100*2500) / 200 = 2450
 *
 *   SELL 100 @ 2550
 *   Realized P&L = (2550 - 2450) * 100 = +10,000
 *
 *   Compare with FIFO: +15,000 (because FIFO matched the cheaper lot)
 *
 * WHY AVERAGE COST:
 *   - Simpler to implement and understand
 *   - No need to track individual lots
 *   - Zerodha, most Indian brokers use this by default
 *   - Less memory usage (just one number, not a queue of lots)
 */
public class AvgCostPnLStrategy implements PnLStrategy {

    @Override
    public double calculateRealizedPnL(List<Trade> trades, Position position) {
        double totalQty = 0;
        double totalCost = 0;
        double realizedPnL = 0;

        for (Trade trade : trades) {
            if (isBuyTrade(trade, position.getUserId())) {
                // Accumulate into average
                totalCost += trade.price() * trade.quantity();
                totalQty += trade.quantity();
            } else {
                // Sell: P&L based on average cost at time of sale
                double avgCost = (totalQty > 0) ? totalCost / totalQty : 0;
                realizedPnL += (trade.price() - avgCost) * trade.quantity();

                // Reduce position
                totalCost -= avgCost * trade.quantity();
                totalQty -= trade.quantity();
            }
        }

        return realizedPnL;
    }

    @Override
    public double calculateUnrealizedPnL(Position position, double currentPrice) {
        if (position.getQuantity() == 0) return 0.0;
        return (currentPrice - position.getAvgBuyPrice()) * position.getQuantity();
    }

    private boolean isBuyTrade(Trade trade, String userId) {
        return trade.buyOrderId().contains(userId);
    }

    @Override
    public String getStrategyName() { return "AVERAGE_COST"; }
}
```

---

## 7. Service Layer Design

### 7.1 OrderBook (THE Critical Data Structure)

```java
/**
 * THE ORDER BOOK — the most important data structure in a trading system.
 *
 * An order book maintains all resting (passive) orders for ONE symbol,
 * organized by price and time priority.
 *
 * DATA STRUCTURE:
 *   bids: TreeMap<Double, PriceLevel> with DESCENDING comparator
 *         → firstEntry() = highest bid (best bid)
 *   asks: TreeMap<Double, PriceLevel> with ASCENDING (natural) comparator
 *         → firstEntry() = lowest ask (best ask)
 *
 * WHY TreeMap:
 *   - Sorted by price automatically
 *   - O(log N) insert/remove where N = number of distinct price levels
 *   - O(1) access to best bid/ask via firstEntry()
 *   - Descending comparator for bids gives us "best bid first"
 *
 * WHY NOT HashMap:
 *   HashMap gives O(1) lookup by exact price, but:
 *   - No ordering → can't get "best bid" without scanning all keys O(N)
 *   - Finding top-5 bid levels requires sorting all keys every time
 *   TreeMap gives us both: O(1) best price AND O(log N) insert.
 *
 * WHY NOT PriorityQueue:
 *   PriorityQueue gives O(1) peek at best, but:
 *   - O(N) to remove a specific order (cancel by orderId)
 *   - Can't iterate price levels efficiently for depth display
 *   TreeMap + orderId index gives O(log N) cancel.
 *
 * ANTI-PATTERN — USING ArrayList:
 * ─────────────────────────────────────────────────────────────────────
 *   // TERRIBLE: O(N) insert (must find correct position), O(N) cancel
 *   class NaiveOrderBook {
 *       List<Order> bids = new ArrayList<>();  // sorted by price desc
 *       List<Order> asks = new ArrayList<>();  // sorted by price asc
 *
 *       void addOrder(Order order) {
 *           List<Order> side = (order.getSide() == BUY) ? bids : asks;
 *           // Binary search for insert position: O(log N)
 *           int idx = Collections.binarySearch(side, order, priceComparator);
 *           side.add(idx, order);  // BUT: ArrayList.add(idx) is O(N) due to shift!
 *       }
 *
 *       void cancelOrder(String orderId) {
 *           // O(N) scan to find the order, O(N) to remove → O(N)
 *           bids.removeIf(o -> o.getOrderId().equals(orderId));
 *           asks.removeIf(o -> o.getOrderId().equals(orderId));
 *       }
 *   }
 *
 *   Performance:     ArrayList         TreeMap+LinkedList
 *   ──────────────   ─────────         ─────────────────
 *   Add order:       O(N) shift        O(log N) tree insert
 *   Best price:      O(1) get(0)       O(1) firstEntry()
 *   Cancel order:    O(N) scan         O(1) index lookup + O(1) list remove
 *   Price levels:    Must group first   Already grouped by PriceLevel
 *
 * CLEAN IMPLEMENTATION:
 * ─────────────────────────────────────────────────────────────────────
 */
public class OrderBook {

    private final String symbol;

    // Bids: highest price first (buyers want the highest price to match first)
    private final TreeMap<Double, PriceLevel> bids;

    // Asks: lowest price first (sellers want the lowest price to match first)
    private final TreeMap<Double, PriceLevel> asks;

    // Fast lookup for cancel: orderId → Order (avoids scanning all levels)
    private final Map<String, Order> orderIndex;

    public OrderBook(String symbol) {
        this.symbol = symbol;
        this.bids = new TreeMap<>(Comparator.reverseOrder()); // DESCENDING
        this.asks = new TreeMap<>();                           // ASCENDING (natural)
        this.orderIndex = new HashMap<>();
    }

    /**
     * Add a resting order to the book at its limit price.
     *
     * STEPS:
     *   1. Determine side (BUY → bids, SELL → asks)
     *   2. Get or create PriceLevel at order's price
     *   3. Append order to the level's queue (time priority — FIFO)
     *   4. Index the order for fast cancel
     *
     * Time: O(log N) for TreeMap lookup/insert, O(1) for queue append
     */
    public void addOrder(Order order) {
        TreeMap<Double, PriceLevel> side =
            (order.getSide() == OrderSide.BUY) ? bids : asks;

        side.computeIfAbsent(order.getPrice(), PriceLevel::new)
            .addOrder(order);

        orderIndex.put(order.getOrderId(), order);
    }

    /**
     * Remove an order from the book (cancel operation).
     *
     * STEPS:
     *   1. Look up order in index → O(1)
     *   2. Find its price level → O(log N) TreeMap lookup
     *   3. Remove from level's queue → O(N) in worst case for LinkedList
     *      (but typically small number of orders per price level)
     *   4. If price level is now empty, remove it from the tree
     *   5. Remove from index
     */
    public Order removeOrder(String orderId) {
        Order order = orderIndex.remove(orderId);
        if (order == null) return null;

        TreeMap<Double, PriceLevel> side =
            (order.getSide() == OrderSide.BUY) ? bids : asks;

        PriceLevel level = side.get(order.getPrice());
        if (level != null) {
            level.removeOrder(order);
            if (level.isEmpty()) {
                side.remove(order.getPrice());
            }
        }

        return order;
    }

    /** Best bid = highest price someone is willing to BUY at. */
    public PriceLevel getBestBid() {
        if (bids.isEmpty()) return null;
        return bids.firstEntry().getValue();   // O(1) — TreeMap.firstEntry()
    }

    /** Best ask = lowest price someone is willing to SELL at. */
    public PriceLevel getBestAsk() {
        if (asks.isEmpty()) return null;
        return asks.firstEntry().getValue();   // O(1) — TreeMap.firstEntry()
    }

    /** Remove the best price level after it's been fully exhausted. */
    public void removePriceLevel(OrderSide side) {
        TreeMap<Double, PriceLevel> tree =
            (side == OrderSide.BUY) ? bids : asks;
        if (!tree.isEmpty()) {
            PriceLevel removed = tree.pollFirstEntry().getValue();
            // Clean up index for any remaining orders at this level
            for (Order order : removed.getOrders()) {
                orderIndex.remove(order.getOrderId());
            }
        }
    }

    /** Spread = best ask - best bid. Tighter = more liquid. */
    public double getSpread() {
        PriceLevel bestBid = getBestBid();
        PriceLevel bestAsk = getBestAsk();
        if (bestBid == null || bestAsk == null) return 0.0;
        return bestAsk.getPrice() - bestBid.getPrice();
    }

    /** Top N bid levels (for market depth display). */
    public List<OrderBookEntry> getBidLevels(int depth) {
        return bids.values().stream()
            .limit(depth)
            .map(PriceLevel::toEntry)
            .toList();
    }

    /** Top N ask levels (for market depth display). */
    public List<OrderBookEntry> getAskLevels(int depth) {
        return asks.values().stream()
            .limit(depth)
            .map(PriceLevel::toEntry)
            .toList();
    }

    public long getTotalBidVolume() {
        return bids.values().stream().mapToLong(PriceLevel::getTotalQuantity).sum();
    }

    public long getTotalAskVolume() {
        return asks.values().stream().mapToLong(PriceLevel::getTotalQuantity).sum();
    }

    public String getSymbol() { return symbol; }
    public int getBidLevelCount() { return bids.size(); }
    public int getAskLevelCount() { return asks.size(); }

    @Override
    public String toString() {
        return String.format("OrderBook{%s: %d bid levels, %d ask levels, spread=%.2f}",
            symbol, bids.size(), asks.size(), getSpread());
    }
}
```

### 7.2 PriceLevel

```java
/**
 * Represents one price level in the order book.
 *
 * A price level holds ALL resting orders at a specific price,
 * in FIFO order (time priority). The first order added is the
 * first to be matched (price-time priority).
 *
 * STRUCTURE:
 *   PriceLevel(2450.00)
 *     orders: [Order-1 (arrived 09:15:30)] → [Order-5 (arrived 09:15:45)]
 *     totalQuantity: 500 (300 + 200)
 *
 * When a matching engine fills at this price, it takes from the HEAD
 * of the queue (Order-1 first, then Order-5 if Order-1 is fully consumed).
 *
 * WHY LinkedList (not ArrayList):
 *   - We only ever access/remove from the HEAD (FIFO queue semantics)
 *   - LinkedList.removeFirst() is O(1), ArrayList.remove(0) is O(N)
 *   - We never need random access by index
 */
public class PriceLevel {

    private final double price;
    private final LinkedList<Order> orders;
    private int totalQuantity;

    public PriceLevel(double price) {
        this.price = price;
        this.orders = new LinkedList<>();
        this.totalQuantity = 0;
    }

    /** Add an order to the END of the queue (time priority). */
    public void addOrder(Order order) {
        orders.addLast(order);
        totalQuantity += order.getRemainingQty();
    }

    /** Remove a specific order (for cancel). */
    public void removeOrder(Order order) {
        if (orders.remove(order)) {
            totalQuantity -= order.getRemainingQty();
        }
    }

    /** Peek at the first order (earliest arrival — time priority). */
    public Order peekFirstOrder() {
        return orders.peekFirst();
    }

    /** Remove the first order (after it's fully filled). */
    public void removeFirstOrder() {
        Order removed = orders.pollFirst();
        if (removed != null) {
            totalQuantity -= removed.getRemainingQty();
        }
    }

    public boolean isEmpty() { return orders.isEmpty(); }
    public double getPrice() { return price; }
    public int getTotalQuantity() { return totalQuantity; }
    public List<Order> getOrders() { return Collections.unmodifiableList(orders); }
    public int getOrderCount() { return orders.size(); }

    /** Convert to immutable snapshot for API/display. */
    public OrderBookEntry toEntry() {
        return new OrderBookEntry(price, totalQuantity, orders.size(), Instant.now());
    }

    @Override
    public String toString() {
        return String.format("PriceLevel{%.2f x %d (%d orders)}", price, totalQuantity, orders.size());
    }
}
```

### 7.3 MatchingEngine

```java
/**
 * THE MATCHING ENGINE — the heart of a stock exchange.
 *
 * One MatchingEngine instance handles ONE symbol. In production (NSE),
 * each symbol has its own engine running on a dedicated thread/core.
 *
 * MATCHING ALGORITHM: PRICE-TIME PRIORITY
 *   1. PRICE priority: best price always matches first
 *      (highest bid for sells, lowest ask for buys)
 *   2. TIME priority: at the same price, earliest order matches first
 *      (FIFO within a price level)
 *
 * CALL CHAIN:
 *   MatchingService.submitOrder(order)
 *     → engines.get(order.getSymbol())        // get engine for this symbol
 *       .match(order)                          // delegate to engine
 *       → strategy.execute(order, orderBook)   // delegate to order type strategy
 *       → List<Trade>                          // trades produced
 *
 * ANTI-PATTERN — GOD METHOD:
 * ─────────────────────────────────────────────────────────────────────
 *   // WRONG: 200-line match() method with all order types in one place
 *   public List<Trade> match(Order order) {
 *       if (order.getType() == MARKET) {
 *           if (order.getSide() == BUY) {
 *               // 40 lines of buy market matching...
 *           } else {
 *               // 40 lines of sell market matching (copy-pasted)...
 *           }
 *       } else if (order.getType() == LIMIT) {
 *           if (order.getSide() == BUY) {
 *               // 40 lines of buy limit matching...
 *           } else {
 *               // 40 lines of sell limit matching...
 *           }
 *       } else if (order.getType() == STOP_LOSS) {
 *           // another 40 lines...
 *       }
 *       // 200+ lines, untestable, unmaintainable
 *   }
 *
 * CLEAN SOLUTION: delegate to OrderExecutionStrategy per order type.
 * ─────────────────────────────────────────────────────────────────────
 */
public class MatchingEngine {

    private final String symbol;
    private final OrderBook orderBook;
    private final Map<OrderType, OrderExecutionStrategy> strategies;

    public MatchingEngine(String symbol,
                          Map<OrderType, OrderExecutionStrategy> strategies) {
        this.symbol = symbol;
        this.orderBook = new OrderBook(symbol);
        this.strategies = strategies;
    }

    /**
     * Match an incoming order against the order book.
     *
     * STEPS:
     *   1. Look up the execution strategy for this order type
     *   2. Delegate matching to the strategy
     *   3. Update order status based on fill result
     *   4. Return list of trades produced
     *
     * @param order the incoming order (already validated and risk-checked)
     * @return list of trades generated by matching
     */
    public List<Trade> match(Order order) {

        // Step 1: Find the right execution strategy
        OrderExecutionStrategy strategy = strategies.get(order.getType());
        if (strategy == null) {
            throw new InvalidOrderException(
                "No execution strategy for order type: " + order.getType());
        }

        // Step 2: Execute the strategy (matching + book management)
        List<Trade> trades = strategy.execute(order, orderBook);

        // Step 3: Update order status based on fill result
        if (order.isFullyFilled()) {
            order.transitionTo(OrderStatus.FILLED);
        } else if (order.getFilledQty() > 0) {
            order.transitionTo(OrderStatus.PARTIALLY_FILLED);
        }
        // If no fills and LIMIT order, it's now resting in the book (status stays OPEN)
        // If no fills and MARKET order, it's cancelled (handled by MarketOrderStrategy)

        return trades;
    }

    /**
     * Cancel a resting order in this engine's order book.
     *
     * @return the cancelled order, or null if not found
     */
    public Order cancelOrder(String orderId) {
        Order order = orderBook.removeOrder(orderId);
        if (order != null && order.isActive()) {
            order.transitionTo(OrderStatus.CANCELLED);
        }
        return order;
    }

    /** Get current order book snapshot for display. */
    public OrderBook getOrderBook() {
        return orderBook;
    }

    public String getSymbol() { return symbol; }

    @Override
    public String toString() {
        return String.format("MatchingEngine{%s, book=%s}", symbol, orderBook);
    }
}
```

### 7.4 TradingService (Facade)

```java
/**
 * FACADE: orchestrates the entire order lifecycle.
 *
 * This is the single entry point for all trading operations.
 * It coordinates: validation → risk → matching → settlement → notification.
 *
 * WHY FACADE:
 *   Without it, the controller would need to know about OrderService,
 *   RiskService, MatchingService, PortfolioService, SettlementService,
 *   NotificationService — and call them in the correct order.
 *   The Facade hides this complexity behind simple methods.
 *
 * CALL CHAIN:
 *   TradingController.placeOrder(userId, symbol, side, type, price, qty)
 *     → tradingService.placeOrder(userId, symbol, side, type, price, qty)
 *       → orderService.createOrder(...)              // build + validate
 *       → riskService.validate(order, account, data) // risk checks
 *       → account.blockMargin(requiredMargin)        // reserve funds
 *       → matchingService.submit(order)              // match in engine
 *       → portfolioService.updatePositions(trades)   // update positions
 *       → settlementService.queueSettlement(trades)  // queue T+1
 *       → notificationService.notifyFills(trades)    // notify user
 */
public class TradingService {

    private final OrderService orderService;
    private final RiskService riskService;
    private final MatchingService matchingService;
    private final PortfolioService portfolioService;
    private final AccountService accountService;
    private final SettlementService settlementService;
    private final NotificationService notificationService;
    private final MarketDataService marketDataService;

    public TradingService(OrderService orderService,
                          RiskService riskService,
                          MatchingService matchingService,
                          PortfolioService portfolioService,
                          AccountService accountService,
                          SettlementService settlementService,
                          NotificationService notificationService,
                          MarketDataService marketDataService) {
        this.orderService = orderService;
        this.riskService = riskService;
        this.matchingService = matchingService;
        this.portfolioService = portfolioService;
        this.accountService = accountService;
        this.settlementService = settlementService;
        this.notificationService = notificationService;
        this.marketDataService = marketDataService;
    }

    /**
     * Place a new order. This is the primary entry point.
     *
     * @return list of trades generated (empty if order is resting)
     * @throws InvalidOrderException if order fails validation
     * @throws OrderRejectedException if order fails risk checks
     * @throws InsufficientMarginException if insufficient funds/margin
     */
    public List<Trade> placeOrder(String userId, String symbol,
                                  OrderSide side, OrderType type,
                                  double price, int quantity) {

        // ─── Step 1: Create and validate order ──────────────────────
        Order order = orderService.createOrder(userId, symbol, side, type, price, quantity);

        // ─── Step 2: Risk checks ────────────────────────────────────
        Account account = accountService.getAccount(userId);
        MarketData marketData = marketDataService.getMarketData(symbol);
        riskService.validate(order, account, marketData);

        // ─── Step 3: Block margin ───────────────────────────────────
        if (side == OrderSide.BUY) {
            double marginRequired = (type == OrderType.MARKET)
                ? marketData.getAskPrice() * quantity * 1.05
                : price * quantity;
            account.blockMargin(marginRequired);
        }

        // ─── Step 4: Transition to OPEN and submit to matching ──────
        order.transitionTo(OrderStatus.OPEN);
        List<Trade> trades = matchingService.submit(order);

        // ─── Step 5: Update positions for each trade ────────────────
        for (Trade trade : trades) {
            portfolioService.onTrade(trade);
            marketDataService.onTrade(trade);
        }

        // ─── Step 6: Release margin for filled portion, settle ──────
        if (!trades.isEmpty()) {
            double filledValue = trades.stream()
                .mapToDouble(Trade::getValue)
                .sum();
            account.releaseMargin(filledValue);
            settlementService.queueForSettlement(trades);
        }

        // ─── Step 7: Notify user ────────────────────────────────────
        notificationService.onOrderUpdate(order);
        for (Trade trade : trades) {
            notificationService.onTradeExecuted(trade);
        }

        return trades;
    }

    /**
     * Cancel a resting order.
     */
    public Order cancelOrder(String userId, String orderId) {
        Order order = orderService.getOrder(orderId);
        if (!order.getUserId().equals(userId)) {
            throw new OrderRejectedException("Cannot cancel another user's order");
        }
        if (!order.isActive()) {
            throw new OrderRejectedException("Order is not active: " + order.getStatus());
        }

        // Remove from matching engine
        matchingService.cancel(order.getSymbol(), orderId);

        // Release blocked margin
        if (order.getSide() == OrderSide.BUY) {
            double marginToRelease = order.getPrice() * order.getRemainingQty();
            accountService.getAccount(userId).releaseMargin(marginToRelease);
        }

        notificationService.onOrderUpdate(order);
        return order;
    }
}
```

### 7.5 RiskService (Chain of Responsibility)

```java
/**
 * Chains multiple risk checks. ALL must pass for an order to proceed.
 *
 * This is a Chain of Responsibility pattern variant:
 *   - Each RiskCheckStrategy is independent
 *   - RiskService runs them ALL (not short-circuit)
 *   - Collects ALL failures (not just the first)
 *   - Order is rejected if ANY check fails
 *
 * WHY ALL CHECKS (not short-circuit):
 *   If we stop at the first failure, the user only sees one error.
 *   They fix it, resubmit, and see the NEXT error. Frustrating.
 *   Running all checks lets us return ALL reasons at once:
 *     "Insufficient margin AND exceeds position limit AND outside circuit"
 */
public class RiskService {

    private final List<RiskCheckStrategy> riskChecks;

    public RiskService(List<RiskCheckStrategy> riskChecks) {
        this.riskChecks = List.copyOf(riskChecks);  // defensive copy
    }

    /**
     * Validate an order against all risk checks.
     *
     * @throws OrderRejectedException if any check fails (with all failure reasons)
     */
    public void validate(Order order, Account account, MarketData marketData) {
        List<RiskResult> failures = new ArrayList<>();

        for (RiskCheckStrategy check : riskChecks) {
            RiskResult result = check.check(order, account, marketData);
            if (!result.isPassed()) {
                failures.add(result);
            }
        }

        if (!failures.isEmpty()) {
            String reasons = failures.stream()
                .map(r -> r.checkName() + ": " + r.reason())
                .collect(Collectors.joining("; "));
            throw new OrderRejectedException("Order rejected: " + reasons);
        }
    }
}
```

### 7.6 MatchingService

```java
/**
 * Routes orders to the correct MatchingEngine by symbol.
 *
 * One MatchingEngine per symbol. This service manages the engines map
 * and creates engines on-demand for new symbols.
 *
 * In production (NSE), each engine runs on a dedicated CPU core
 * for maximum throughput. Our in-memory version uses a ConcurrentHashMap
 * of engines, with per-engine synchronization.
 */
public class MatchingService {

    private final Map<String, MatchingEngine> engines;
    private final Map<OrderType, OrderExecutionStrategy> strategies;
    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;

    public MatchingService(Map<OrderType, OrderExecutionStrategy> strategies,
                           OrderRepository orderRepository,
                           TradeRepository tradeRepository) {
        this.engines = new ConcurrentHashMap<>();
        this.strategies = strategies;
        this.orderRepository = orderRepository;
        this.tradeRepository = tradeRepository;
    }

    /**
     * Submit an order for matching.
     *
     * @return trades generated (empty if order rests in book)
     */
    public List<Trade> submit(Order order) {
        // Get or create engine for this symbol
        MatchingEngine engine = engines.computeIfAbsent(
            order.getSymbol(),
            symbol -> new MatchingEngine(symbol, strategies)
        );

        // Match the order
        List<Trade> trades = engine.match(order);

        // Persist order and trades
        orderRepository.save(order);
        for (Trade trade : trades) {
            tradeRepository.save(trade);
        }

        return trades;
    }

    /**
     * Cancel a resting order.
     */
    public Order cancel(String symbol, String orderId) {
        MatchingEngine engine = engines.get(symbol);
        if (engine == null) {
            throw new InvalidOrderException("No engine for symbol: " + symbol);
        }
        Order cancelled = engine.cancelOrder(orderId);
        if (cancelled != null) {
            orderRepository.save(cancelled);  // persist updated status
        }
        return cancelled;
    }

    /** Get order book for a symbol (for market depth display). */
    public OrderBook getOrderBook(String symbol) {
        MatchingEngine engine = engines.get(symbol);
        return (engine != null) ? engine.getOrderBook() : null;
    }
}
```

### 7.7 AppConfig (Composition Root)

```java
/**
 * Composition root: creates and wires all objects with pure constructor injection.
 *
 * NO FRAMEWORK. No Spring, no Guice, no annotation magic.
 * Every dependency is explicitly created and injected here.
 *
 * This class is the ONLY place where 'new' is used to create service objects.
 * All services receive their dependencies through constructors.
 *
 * WHY THIS APPROACH:
 *   1. All wiring is visible in ONE place
 *   2. Compiler catches missing dependencies (no runtime DI failures)
 *   3. Easy to trace: "who created TradingService?" → AppConfig.
 *   4. Easy to test: create services with mock dependencies
 */
public class AppConfig {

    /**
     * Create the fully wired TradingService (the Facade).
     * This is the only public method. Everything else is internal wiring.
     */
    public static TradingService createTradingService() {

        // ─── Repositories (in-memory stores) ────────────────────────
        OrderRepository orderRepo = new InMemoryOrderRepository();
        TradeRepository tradeRepo = new InMemoryTradeRepository();
        PositionRepository positionRepo = new InMemoryPositionRepository();
        AccountRepository accountRepo = new InMemoryAccountRepository();
        StockRepository stockRepo = new InMemoryStockRepository();
        MarketDataRepository marketDataRepo = new InMemoryMarketDataRepository();

        // ─── Seed stock data ────────────────────────────────────────
        Stock reliance = new Stock("RELIANCE", "Reliance Industries", "NSE",
            1, 0.05, 3000.00, 2000.00);  // circuit: +/-20% from 2500
        Stock tcs = new Stock("TCS", "Tata Consultancy Services", "NSE",
            1, 0.05, 4200.00, 2800.00);  // circuit: +/-20% from 3500
        stockRepo.save(reliance);
        stockRepo.save(tcs);

        // ─── Seed market data ───────────────────────────────────────
        marketDataRepo.save(new MarketData("RELIANCE", 2500.00));
        marketDataRepo.save(new MarketData("TCS", 3500.00));

        // ─── Order execution strategies (Strategy pattern) ──────────
        Map<OrderType, OrderExecutionStrategy> executionStrategies = Map.of(
            OrderType.MARKET, new MarketOrderStrategy(),
            OrderType.LIMIT, new LimitOrderStrategy()
        );

        // ─── Risk check strategies (chain) ──────────────────────────
        RiskCheckStrategy marginCheck = new MarginCheckStrategy();
        RiskCheckStrategy positionLimit = new PositionLimitStrategy(
            10_000, 50_000, positionRepo);
        RiskCheckStrategy circuitBreaker = new CircuitBreakerStrategy(stockRepo);

        // ─── Services ───────────────────────────────────────────────
        OrderService orderService = new OrderService(orderRepo, stockRepo);
        RiskService riskService = new RiskService(
            List.of(marginCheck, positionLimit, circuitBreaker));
        MatchingService matchingService = new MatchingService(
            executionStrategies, orderRepo, tradeRepo);
        PnLStrategy pnlStrategy = new AvgCostPnLStrategy();
        PortfolioService portfolioService = new PortfolioService(
            positionRepo, tradeRepo, pnlStrategy);
        AccountService accountService = new AccountService(accountRepo);
        MarketDataService marketDataService = new MarketDataService(marketDataRepo);
        SettlementService settlementService = new SettlementService(
            accountRepo, positionRepo);
        NotificationService notificationService = new NotificationService();

        // ─── The Facade ─────────────────────────────────────────────
        return new TradingService(
            orderService,
            riskService,
            matchingService,
            portfolioService,
            accountService,
            settlementService,
            notificationService,
            marketDataService
        );
    }
}
```

---

## 8. Concurrency Considerations

### 8.1 The Order Book Race Condition

```
+=====================================================================+
|         THE ORDER BOOK RACE CONDITION (Critical Interview Topic)     |
+=====================================================================+

    Scenario: Two orders arrive simultaneously for the same stock.

    Thread A: BUY 200 RELIANCE @ MARKET
    Thread B: SELL 300 RELIANCE @ MARKET

    BOTH target the same OrderBook. Both call engine.match() concurrently.

    WITHOUT SYNCHRONIZATION:
    ──────────────────────────────────────────────────────────────────
    T0:  Thread A: bestAsk = book.getBestAsk() → PriceLevel(2451, qty=300)
    T1:  Thread B: bestBid = book.getBestBid() → PriceLevel(2450, qty=500)
    T2:  Thread A: fill 200 from ask@2451, restingOrder.fill(200)
    T3:  Thread B: fill 300 from bid@2450, restingOrder.fill(300)
    T4:  Thread A: restingOrder now has qty=100 remaining
    T5:  Thread B: ALSO fills from the SAME resting order → DOUBLE FILL!

    RESULT: A resting order with 500 qty gets filled for 200 + 300 = 500,
    but Thread A and Thread B each think they got their own fill independently.
    If the resting order only had 300, we'd fill 200 + 300 = 500 → OVERFILL!

    WITH PER-SYMBOL LOCK:
    ──────────────────────────────────────────────────────────────────
    T0:  Thread A: lock("RELIANCE") → acquired
    T1:  Thread B: lock("RELIANCE") → BLOCKED (waits for Thread A)
    T2:  Thread A: match BUY 200, fill, update book → done
    T3:  Thread A: unlock("RELIANCE")
    T4:  Thread B: lock("RELIANCE") → acquired
    T5:  Thread B: match SELL 300 (sees updated book from Thread A)
    T6:  Thread B: unlock("RELIANCE")

    RESULT: Correct matching. No double fills. Thread B sees Thread A's effects.

    KEY INSIGHT: Lock per SYMBOL, not a global lock.
    Orders for RELIANCE and TCS can execute concurrently (different books).
    Only orders for the SAME symbol need serialization.
+=====================================================================+
```

---

### 8.2 Margin Block Race Condition

```
+=====================================================================+
|         THE MARGIN DOUBLE-SPEND PROBLEM                              |
+=====================================================================+

    Scenario: User has balance = 500,000. Places two orders concurrently.

    Thread A: BUY 100 RELIANCE @ 2500 → needs 250,000 margin
    Thread B: BUY 100 TCS @ 3500     → needs 350,000 margin
    Total needed: 600,000 > 500,000 balance!

    WITHOUT SYNCHRONIZATION ON ACCOUNT:
    ──────────────────────────────────────────────────────────────────
    T0:  Thread A: check margin: 250,000 <= 500,000? YES
    T1:  Thread B: check margin: 350,000 <= 500,000? YES (stale read!)
    T2:  Thread A: blockMargin(250,000) → marginUsed=250,000
    T3:  Thread B: blockMargin(350,000) → marginUsed=600,000
    T4:  marginUsed (600,000) > balance (500,000) → OVERCOMMITTED!

    WITH SYNCHRONIZED ACCOUNT OPERATIONS:
    ──────────────────────────────────────────────────────────────────
    T0:  Thread A: synchronized(account) { check + block 250,000 }
    T1:  Thread B: synchronized(account) { check: 350,000 <= 250,000? NO }
         → InsufficientMarginException!

    RESULT: Only one order goes through. No overcommitment.

    IMPLEMENTATION:
      Account.blockMargin() is synchronized.
      Check-then-act is atomic — no window for a second thread to slip in.
+=====================================================================+
```

---

### 8.3 Concurrency Strategy Summary

```
+=====================================================================+
|         CONCURRENCY STRATEGY PER COMPONENT                           |
+=====================================================================+

    Component                  Strategy                    Why
    ──────────────────────    ───────────────────────     ────────────────────────
    MatchingEngine            ReentrantLock per engine    One engine per symbol;
    (per symbol)              (or synchronized block)     orders for same symbol
                                                          must be serialized to
                                                          prevent double-fill

    Account.blockMargin()    synchronized method          Check-and-act must be
    Account.debit()                                       atomic to prevent
    Account.credit()                                      margin overcommitment

    OrderBook                 Accessed only through       MatchingEngine lock
                              MatchingEngine (which        covers all book ops;
                              holds the lock)              no separate lock needed

    Position.addShares()     synchronized method          Multiple trades for
    Position.removeShares()                               same user+symbol can
                                                          arrive concurrently

    MatchingService          ConcurrentHashMap for        Engine creation is
    .engines map             engines map                  thread-safe;
                                                          engine access serialized
                                                          by engine's own lock

    Repositories             ConcurrentHashMap for        Thread-safe reads/writes
    (InMemory*)              all stores                   for all in-memory stores

    MarketData               volatile fields or           Multiple threads read
                              AtomicReference              LTP concurrently; one
                                                          thread updates after trade

    Execution Strategies     Stateless                    No shared mutable state;
    (Market, Limit)                                       safe for concurrent calls
+=====================================================================+
```

---

### 8.4 Per-Symbol Lock Implementation

```java
/**
 * Per-symbol locking for the matching engine.
 *
 * ANTI-PATTERN — GLOBAL LOCK:
 * ─────────────────────────────────────────────────────────────────────
 *   // WRONG: single lock for all symbols — destroys throughput
 *   class NaiveMatchingService {
 *       private final Object globalLock = new Object();
 *
 *       public List<Trade> submit(Order order) {
 *           synchronized (globalLock) {   // ALL symbols wait here
 *               return engines.get(order.getSymbol()).match(order);
 *           }
 *       }
 *   }
 *   // RELIANCE order blocks TCS order — unnecessary serialization!
 *   // With 2000 stocks, throughput drops to 1/2000th of potential.
 *
 * CLEAN SOLUTION — PER-SYMBOL LOCK:
 * ─────────────────────────────────────────────────────────────────────
 */
public class MatchingEngine {

    private final ReentrantLock engineLock = new ReentrantLock();

    /**
     * Thread-safe match: acquires per-symbol lock.
     * Orders for different symbols run concurrently.
     * Orders for the SAME symbol are serialized.
     */
    public List<Trade> match(Order order) {
        engineLock.lock();
        try {
            OrderExecutionStrategy strategy = strategies.get(order.getType());
            if (strategy == null) {
                throw new InvalidOrderException(
                    "No strategy for: " + order.getType());
            }

            List<Trade> trades = strategy.execute(order, orderBook);

            if (order.isFullyFilled()) {
                order.transitionTo(OrderStatus.FILLED);
            } else if (order.getFilledQty() > 0) {
                order.transitionTo(OrderStatus.PARTIALLY_FILLED);
            }

            return trades;
        } finally {
            engineLock.unlock();   // ALWAYS release in finally
        }
    }
}
```

---

## 9. SOLID Principles Applied

### 9.1 Single Responsibility

```
+=====================================================================+
|         SINGLE RESPONSIBILITY                                        |
+=====================================================================+

    Each class has ONE reason to change:

    Class                    Responsibility                Changes when...
    ──────────────────────  ────────────────────────      ────────────────────────
    TradingService          Orchestration flow            Flow steps change
    OrderService            Order validation              Validation rules change
    MatchingEngine          Order book + matching         Matching algorithm changes
    OrderBook               Price-level data structure    Data structure changes
    PriceLevel              FIFO queue at one price       Queue implementation changes
    MarketOrderStrategy     Market order matching         Market order rules change
    LimitOrderStrategy      Limit order matching          Limit order rules change
    MarginCheckStrategy     Margin calculation            Margin formula changes
    PositionLimitStrategy   Position limit enforcement    Position limits change
    CircuitBreakerStrategy  Circuit limit enforcement     Circuit rules change
    PortfolioService        Position + P&L tracking       P&L rules change
    SettlementService       T+1 settlement                Settlement process changes
    AccountService          Balance + margin management   Account rules change

    ANTI-PATTERN AVOIDED:
    Having MatchingEngine also do risk checks, position updates, and settlement
    would make it a 600-line God class with 6 reasons to change.
+=====================================================================+
```

---

### 9.2 Open-Closed Principle

```
+=====================================================================+
|         OPEN-CLOSED PRINCIPLE                                        |
+=====================================================================+

    OPEN for extension, CLOSED for modification.

    Adding a new order type (e.g., STOP_LOSS):
      1. Add to enum:     OrderType.STOP_LOSS
      2. Create strategy: StopLossOrderStrategy implements OrderExecutionStrategy
      3. Register:        AppConfig → strategies.put(STOP_LOSS, new StopLossOrderStrategy())
      4. DONE. MatchingEngine, TradingService → ZERO changes.

    Adding a new risk check (e.g., daily loss limit):
      1. Create strategy: DailyLossLimitStrategy implements RiskCheckStrategy
      2. Register:        AppConfig → riskChecks.add(new DailyLossLimitStrategy(...))
      3. DONE. RiskService iterates the chain. No changes to existing checks.

    Adding a new P&L method (e.g., LIFO):
      1. Create strategy: LIFOPnLStrategy implements PnLStrategy
      2. Configure:       AppConfig → pnlStrategy = new LIFOPnLStrategy()
      3. DONE. PortfolioService uses the interface. No changes.

    The key insight: new behavior = new class, not modified class.
+=====================================================================+
```

---

### 9.3 Liskov Substitution

```
+=====================================================================+
|         LISKOV SUBSTITUTION PRINCIPLE                                 |
+=====================================================================+

    Any OrderExecutionStrategy implementation can be swapped without
    breaking MatchingEngine behavior.

    MatchingEngine calls:
      strategy.execute(order, orderBook)  →  List<Trade>

    It does NOT care whether:
      - MarketOrderStrategy walks the entire book for immediate fills
      - LimitOrderStrategy checks price crossing and rests remainder
      - A future StopLossStrategy triggers on price threshold

    All return List<Trade>. That's the contract.

    Test: Replace MarketOrderStrategy with a TestStrategy that always
    returns an empty list. MatchingEngine works identically (no trades,
    order status not changed to FILLED). This IS Liskov substitution.

    VIOLATION EXAMPLE (avoided):
      If MarketOrderStrategy threw a different exception type than
      LimitOrderStrategy, callers would need type-specific handling.
      We avoid this: strategies return List<Trade> (never throw for
      business outcomes like "no match available").
+=====================================================================+
```

---

### 9.4 Interface Segregation

```
+=====================================================================+
|         INTERFACE SEGREGATION                                        |
+=====================================================================+

    Interfaces are SMALL and FOCUSED:

    OrderExecutionStrategy:
      execute(order, book): List<Trade>
      getStrategyName(): String
      supports(orderType): boolean

    RiskCheckStrategy:
      check(order, account, marketData): RiskResult
      getCheckName(): String

    PnLStrategy:
      calculateRealizedPnL(trades, position): double
      calculateUnrealizedPnL(position, currentPrice): double
      getStrategyName(): String

    OrderRepository:
      save(), findById(), findByUserId(), findBySymbol(), findByStatus(), findAll()

    NO GOD INTERFACE like:
      interface TradingOperations {
          matchOrder();        // matching concern
          checkRisk();         // risk concern
          calculatePnL();      // portfolio concern
          settleTradeT1();     // settlement concern
          sendNotification();  // notification concern
      }

    Each client depends ONLY on the methods it uses.
+=====================================================================+
```

---

### 9.5 Dependency Inversion

```
+=====================================================================+
|         DEPENDENCY INVERSION                                         |
+=====================================================================+

    High-level modules depend on ABSTRACTIONS, not concrete classes.

    MatchingEngine depends on:
      - OrderExecutionStrategy (interface)    NOT MarketOrderStrategy (class)

    RiskService depends on:
      - RiskCheckStrategy (interface)         NOT MarginCheckStrategy (class)

    PortfolioService depends on:
      - PnLStrategy (interface)              NOT AvgCostPnLStrategy (class)
      - PositionRepository (interface)       NOT InMemoryPositionRepository (class)

    TradingService depends on:
      - OrderService, RiskService, MatchingService (concrete, but could be interfaces)

    DEPENDENCY GRAPH:

      TradingService (Facade)
          |
          +---> OrderService
          +---> RiskService
          |         |
          |         +---> RiskCheckStrategy (interface)
          |                   ^
          |                   +-- MarginCheckStrategy
          |                   +-- PositionLimitStrategy
          |                   +-- CircuitBreakerStrategy
          |
          +---> MatchingService
          |         |
          |         +---> MatchingEngine
          |                   |
          |                   +---> OrderExecutionStrategy (interface)
          |                              ^
          |                              +-- MarketOrderStrategy
          |                              +-- LimitOrderStrategy
          |
          +---> PortfolioService
          |         |
          |         +---> PnLStrategy (interface)
          |                   ^
          |                   +-- FIFOPnLStrategy
          |                   +-- AvgCostPnLStrategy
          |
          +---> AccountService
          +---> SettlementService
          +---> NotificationService

    AppConfig (composition root) is the ONLY class that knows
    about concrete implementations. All wiring happens there.
+=====================================================================+
```

---

## 10. Sample Workflows

### 10.1 Happy Path: Limit Order Matches Immediately

```
+=====================================================================+
|  WORKFLOW 1: Happy Path — BUY LIMIT Matches Resting SELL            |
+=====================================================================+

    User "trader-1" places a BUY LIMIT for RELIANCE at 2451.
    There's a resting SELL at 2451 in the book.

    CURRENT ORDER BOOK (RELIANCE):
      BIDS                          ASKS
      2450.00 x 500 [O1, O5]       2451.00 x 300 [O2]
      2449.50 x 200 [O3]           2452.00 x 800 [O7]

    STEP-BY-STEP:

    1. Client sends order:
       TradingController.placeOrder(
           userId="trader-1", symbol="RELIANCE", side=BUY,
           type=LIMIT, price=2451.00, quantity=200)

    2. Create order:
       → OrderService.createOrder(...)
         → validate: symbol exists? YES. lotSize valid? YES (200 % 1 = 0).
           tickSize valid? YES (2451.00 % 0.05 = 0).
         → Order{ORD-a1b2c3d4, BUY RELIANCE 200@2451.00, PENDING}

    3. Risk checks:
       → RiskService.validate(order, account, marketData)
         → MarginCheckStrategy.check():
           requiredMargin = 2451.00 * 200 = 490,200
           available = 500,000 → PASS
         → PositionLimitStrategy.check():
           current RELIANCE position = 0, projected = 200 < 10,000 → PASS
         → CircuitBreakerStrategy.check():
           price 2451.00, circuit [2000, 3000] → PASS

    4. Block margin:
       → account.blockMargin(490,200)
         → marginUsed: 0 → 490,200
         → available: 500,000 → 9,800

    5. Transition to OPEN:
       → order.transitionTo(OPEN) ← PENDING→OPEN validated ✓

    6. Submit to matching engine:
       → MatchingService.submit(order)
         → engine = engines.get("RELIANCE")
         → engine.lock()
         → LimitOrderStrategy.execute(order, orderBook)

    7. Matching (inside LimitOrderStrategy):
       → bestAsk = book.getBestAsk() → PriceLevel(2451.00, [O2])
       → pricesCross? 2451.00 <= 2451.00? YES
       → tradeQty = min(200, 300) = 200
       → tradePrice = 2451.00 (resting order's price)
       → Trade{TRD-x1y2z3, buyOrder=ORD-a1b2c3d4, sellOrder=O2,
               RELIANCE, 200@2451.00}
       → order.fill(200) → filledQty=200, remaining=0
       → O2.fill(200) → filledQty=200, remaining=100
       → O2 not fully filled → O2.transitionTo(PARTIALLY_FILLED)

    8. Order fully filled:
       → order.isFullyFilled()? YES (200/200)
       → order.transitionTo(FILLED)

    9. Update order book (after match):
       BIDS                          ASKS
       2450.00 x 500 [O1, O5]       2451.00 x 100 [O2 (100 remaining)]
       2449.50 x 200 [O3]           2452.00 x 800 [O7]

    10. Update positions:
        → PortfolioService.onTrade(trade)
          → trader-1: Position{RELIANCE, qty=0→200, avg=2451.00}
          → seller-of-O2: Position{RELIANCE, qty reduced by 200}

    11. Release margin + settle:
        → account.releaseMargin(490,200)
        → settlementService.queue(trade) → T+1

    12. Notify:
        → NotificationService: "Order ORD-a1b2c3d4 FILLED: 200 RELIANCE @ 2451.00"

    FINAL STATE:
      Order:    FILLED, 200/200 @ 2451.00
      Trade:    TRD-x1y2z3, 200 RELIANCE @ 2451.00
      Position: trader-1 holds 200 RELIANCE @ avg 2451.00
      Account:  balance unchanged (settlement at T+1), margin released
+=====================================================================+
```

---

### 10.2 Partial Fill: Market Order Walks the Book

```
+=====================================================================+
|  WORKFLOW 2: Partial Fill — MARKET BUY Walks Multiple Price Levels   |
+=====================================================================+

    User "trader-2" places a BUY MARKET for 500 shares of RELIANCE.
    The ask side has: 300 @ 2451 and 800 @ 2452.

    CURRENT ORDER BOOK:
      ASKS
      2451.00 x 300 [O2]
      2452.00 x 800 [O7, O9]

    STEP-BY-STEP (starting from matching):

    1. MarketOrderStrategy.execute(order, book):

       Iteration 1:
         bestAsk = PriceLevel(2451.00, [O2, qty=300])
         tradeQty = min(500, 300) = 300
         Trade-1: 300 @ 2451.00
         order.fill(300) → remaining=200
         O2.fill(300) → fully filled → remove from level
         Level 2451.00 now empty → remove from asks

       Iteration 2:
         bestAsk = PriceLevel(2452.00, [O7, O9])
         O7 has 500 remaining
         tradeQty = min(200, 500) = 200
         Trade-2: 200 @ 2452.00
         order.fill(200) → remaining=0 → FULLY FILLED
         O7.fill(200) → remaining=300 → PARTIALLY_FILLED

    2. Results:
       Trades: [Trade-1: 300@2451, Trade-2: 200@2452]
       Average execution price: (300*2451 + 200*2452) / 500 = 2451.40
       Total cost: 1,225,700

    3. Updated order book:
       ASKS
       2452.00 x 600 [O7 (300 remaining), O9]

       Note: level 2451.00 was fully consumed and removed.

    INTERVIEW NOTE: "Walking the book" is the key phrase.
    Market orders consume liquidity at increasing worse prices.
    This is why LIMIT orders exist — to cap the worst-case price.
+=====================================================================+
```

---

### 10.3 Rejection: Circuit Breaker Triggers

```
+=====================================================================+
|  WORKFLOW 3: Circuit Breaker Rejection                               |
+=====================================================================+

    User tries to place a BUY LIMIT at 3100 for RELIANCE.
    RELIANCE upper circuit = 3000.

    1. Create order:
       → Order{ORD-abc123, BUY RELIANCE 100@3100.00, PENDING}

    2. Risk checks:
       → MarginCheckStrategy: 3100 * 100 = 310,000 <= 500,000 → PASS
       → PositionLimitStrategy: 0 + 100 < 10,000 → PASS
       → CircuitBreakerStrategy:
         stock.isWithinCircuitLimits(3100.00)?
         3100.00 > upperCircuitLimit(3000.00) → FAIL!
         → RiskResult.fail("CIRCUIT_BREAKER",
             "Price 3100.00 outside circuit limits [2000.00, 3000.00] for RELIANCE")

    3. RiskService collects failure:
       → throw OrderRejectedException(
           "Order rejected: CIRCUIT_BREAKER: Price 3100.00 outside circuit limits...")

    4. Order status:
       → order.transitionTo(REJECTED)
       → No margin blocked. No matching attempted. No position change.

    INTERVIEW NOTE: Circuit breakers prevent flash crashes.
    Real exchanges (NSE) halt trading entirely if a stock hits circuit.
+=====================================================================+
```

---

### 10.4 Order Cancellation

```
+=====================================================================+
|  WORKFLOW 4: Cancel a Resting LIMIT Order                           |
+=====================================================================+

    User "trader-1" placed a BUY LIMIT 100@2440 earlier.
    It's resting in the book (no match found at that price).
    Now they want to cancel it.

    1. TradingService.cancelOrder("trader-1", "ORD-resting-1")

    2. Validate ownership:
       → order.getUserId() == "trader-1"? YES

    3. Check active:
       → order.isActive()? status=OPEN → YES

    4. Remove from matching engine:
       → MatchingService.cancel("RELIANCE", "ORD-resting-1")
         → engine.lock()
         → orderBook.removeOrder("ORD-resting-1")
           → orderIndex.remove("ORD-resting-1") → Order found
           → bids.get(2440.00) → PriceLevel
           → level.removeOrder(order)
           → level still has other orders → keep level
         → order.transitionTo(CANCELLED)
         → engine.unlock()

    5. Release margin:
       → marginToRelease = 2440.00 * 100 = 244,000
       → account.releaseMargin(244,000)

    6. Notify:
       → "Order ORD-resting-1 CANCELLED"

    FINAL: Order status=CANCELLED, margin released, book updated.
+=====================================================================+
```

---

## 11. Design Patterns Used

```
+=====================================================================+
|         DESIGN PATTERNS SUMMARY                                      |
+=====================================================================+

    Pattern                Where                         Why
    ───────────────────   ───────────────────────────   ─────────────────────────
    Strategy              OrderExecutionStrategy        Different matching logic
                          (Market, Limit)               per order type. Add new
                                                        types without modifying
                                                        MatchingEngine.

    Strategy              RiskCheckStrategy             Different risk checks
                          (Margin, Position, Circuit)   are pluggable. Add new
                                                        checks without modifying
                                                        RiskService.

    Strategy              PnLStrategy                   Different P&L calculation
                          (FIFO, AvgCost)               methods for the same
                                                        trades. User/admin selects.

    Builder               Order.Builder                 12+ fields. Constructor
                                                        with 12 params is
                                                        unreadable. Builder makes
                                                        construction clear.

    Facade                TradingService                Hides the complexity of
                                                        OrderService + RiskService
                                                        + MatchingService +
                                                        PortfolioService + etc.
                                                        behind simple placeOrder().

    State Machine         OrderStatus transitions       Enforces valid lifecycle
                          (PENDING→OPEN→FILLED)         transitions. Prevents
                                                        illegal state changes.

    Repository            OrderRepository,              Abstracts data access.
                          TradeRepository, etc.         Swap InMemory for JDBC
                                                        without touching services.

    Chain of              RiskService chains             Multiple risk checks
    Responsibility        RiskCheckStrategy              run sequentially. All
                          instances                      must pass. Collects all
                                                        failures, not just first.

    Factory Method        AppConfig                     Centralized object creation.
                          .createTradingService()       Pure constructor injection.
                                                        No framework needed.

    Value Object          Trade (record)                Immutable after creation.
                          OrderBookEntry (record)       Trade records are permanent
                          RiskResult (record)           audit trail, never modified.
+=====================================================================+
```

---

## 12. Extensibility Points

```
+=====================================================================+
|         EXTENSIBILITY POINTS                                         |
+=====================================================================+

    Change                       Steps Required                 Classes Modified
    ─────────────────────────   ──────────────────────────     ─────────────────
    Add STOP_LOSS order type    1. Add OrderType.STOP_LOSS     OrderType (enum)
                                2. StopLossStrategy            NEW class
                                   implements                  AppConfig
                                   OrderExecutionStrategy      (registration)
                                3. Register in AppConfig

    Add daily loss limit        1. DailyLossLimitStrategy      NEW class
    risk check                     implements                  AppConfig
                                   RiskCheckStrategy           (registration)
                                2. Register in AppConfig

    Add LIFO P&L method        1. LIFOPnLStrategy             NEW class
                                   implements PnLStrategy      AppConfig (swap)
                                2. Use in AppConfig

    Add bracket/cover order     1. BracketOrderStrategy        NEW class
                                   implements                  OrderType (enum)
                                   OrderExecutionStrategy      AppConfig
                                2. Register in AppConfig

    Switch to SQL storage       1. JdbcOrderRepository         NEW class
                                   implements                  AppConfig
                                   OrderRepository             (swap in wiring)
                                2. Swap in AppConfig

    Add WebSocket price feed    1. WebSocketPriceFeed          NEW class
                                   wraps                       AppConfig
                                   MarketDataService
                                2. Wire in AppConfig

    Add auction mode (pre-      1. AuctionMatchingEngine       NEW class
    market/post-market)            extends MatchingEngine      AppConfig
                                2. Swap during auction hours

    Add multi-exchange          1. ExchangeRouter wraps        NEW class
    routing (NSE + BSE)            MatchingService             AppConfig
                                2. Route by exchange field


    ─────────────────────────────────────────────────────────────
    PATTERN: Every extension is a NEW CLASS + AppConfig registration.
    Existing classes are NEVER modified.
    This is the Open-Closed Principle in action.
    ─────────────────────────────────────────────────────────────


    EXTENSIBILITY ARCHITECTURE:

    ┌──────────────────────────────────────────────────────────────┐
    │                     AppConfig (Composition Root)               │
    │                                                                │
    │  Swap implementations here. Services don't know or care.      │
    │                                                                │
    │  strategies.put(STOP_LOSS, new StopLossStrategy());  ← ADD    │
    │  riskChecks.add(new DailyLossLimitStrategy());       ← ADD    │
    │  pnlStrategy = new LIFOPnLStrategy();                ← SWAP   │
    │  orderRepo = new JdbcOrderRepository(dataSource);    ← SWAP   │
    └──────────────────────────────────────────────────────────────┘
                    │
                    ▼
    ┌──────────────────────────────────────────────────────────────┐
    │              Services (unchanged, depend on interfaces)        │
    │                                                                │
    │  MatchingEngine → OrderExecutionStrategy (interface)           │
    │  RiskService → List<RiskCheckStrategy> (interface)             │
    │  PortfolioService → PnLStrategy (interface)                    │
    │  OrderService → OrderRepository (interface)                    │
    └──────────────────────────────────────────────────────────────┘
                    │
                    ▼
    ┌──────────────────────────────────────────────────────────────┐
    │              Implementations (pluggable, many options)         │
    │                                                                │
    │  MarketOrderStrategy | LimitOrderStrategy | StopLossStrategy  │
    │  MarginCheck | PositionLimit | CircuitBreaker | DailyLoss     │
    │  FIFOPnL | AvgCostPnL | LIFOPnL                              │
    │  InMemoryRepo | JdbcRepo | MongoRepo                          │
    └──────────────────────────────────────────────────────────────┘
```

---

### Matching Algorithm Complexity Summary

```
+=====================================================================+
|         ORDER BOOK OPERATIONS — TIME COMPLEXITY                      |
+=====================================================================+

    Operation                    TreeMap+LinkedList       Notes
    ──────────────────────      ──────────────────       ─────────────────────
    Add order to book            O(log P)                P = price levels
    Get best bid/ask             O(1)                    firstEntry()
    Match at best price          O(1)                    dequeue from head
    Walk book (K levels)         O(K)                    iterate TreeMap entries
    Cancel order by ID           O(1) + O(log P)         index lookup + tree remove
    Get top-N depth              O(N)                    stream().limit(N)
    Total bid/ask volume         O(P)                    sum all levels

    Space: O(N) where N = total resting orders
           O(P) for the TreeMap structure (P = distinct price levels)
           O(N) for the orderId index

    In practice:
      - P is small (hundreds of price levels for liquid stocks)
      - N can be large (thousands of resting orders)
      - O(log P) ~ O(log 200) ~ 8 comparisons — effectively constant

    NSE benchmark: ~10,000 orders per second per symbol
    Our design handles this easily with per-symbol locking.
+=====================================================================+
```
