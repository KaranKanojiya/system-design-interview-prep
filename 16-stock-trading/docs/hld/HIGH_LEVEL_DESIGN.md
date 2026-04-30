# High-Level Design: Stock Trading Platform (Zerodha / Upstox / Robinhood)

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
10. [Order Matching Engine Deep Dive](#10-order-matching-engine-deep-dive)
11. [Order Types](#11-order-types)
12. [Risk Management](#12-risk-management)
13. [Market Data](#13-market-data)
14. [Concurrency](#14-concurrency)
15. [Scaling](#15-scaling)
16. [Database Choice](#16-database-choice)
17. [CAP Theorem](#17-cap-theorem)
18. [Cloud Services](#18-cloud-services)
19. [Tradeoffs Summary](#19-tradeoffs-summary)
20. [Interview Talking Points](#20-interview-talking-points)

---

## 1. Problem Statement

Design a **Stock Trading Platform** (like Zerodha, Upstox, or Robinhood) that allows users to buy and sell stocks in real time. The system must provide a low-latency order matching engine that executes trades using price-time priority, stream real-time market data (tickers, order book depth, OHLCV candles) to millions of concurrent users, enforce pre-trade risk checks (margin, position limits, circuit breakers), maintain an accurate portfolio with realized and unrealized P&L, and settle trades on a T+1 cycle. The matching engine is the beating heart of this system -- a single bug that matches at the wrong price or violates time priority can cause financial loss and regulatory penalties.

**Why is it needed?**

- Stock exchanges process billions of dollars in trades daily. Zerodha handles 15%+ of India's retail trading volume; Robinhood has 23M+ funded accounts.
- A matching engine bug that fills a buy order at $105 when there's a resting ask at $100 causes direct financial harm to the buyer and destroys platform trust.
- Market data must reach every connected client within milliseconds. A 100ms delay means a trader sees stale prices and places orders based on outdated information.
- Risk checks must execute BEFORE the order enters the matching engine. A missed margin check can expose the broker to unlimited loss if the trader defaults.
- During market open (9:15 AM) and volatile events, order rates can spike 10-50x. The system must absorb this without dropping orders.
- Regulatory requirements (SEBI, SEC, FINRA) mandate order audit trails, best-execution, and circuit breaker enforcement.

**Core Workflow:**

```
Trader places a Buy Limit Order for RELIANCE at Rs 2,500, qty=100

(1) Trader App --> API Gateway: POST /orders
     {symbol: "RELIANCE", side: BUY, type: LIMIT, price: 2500, qty: 100}
(2) API Gateway: authenticate JWT, rate limit (100 orders/sec per user)
(3) API Gateway --> Order Service: validate order fields, enrich with timestamps
(4) Order Service: persist order record (status=PENDING_RISK_CHECK)
(5) Order Service --> Risk Engine: pre-trade risk check
     - Does user have sufficient margin? (100 * 2500 = Rs 2,50,000 for delivery)
     - Is position within limits? (max 10% of free float)
     - Is price within circuit breaker band? (+-20% of previous close)
(6) Risk Engine --> Order Service: APPROVED, margin blocked Rs 2,50,000
(7) Order Service: update order (status=ACCEPTED), publish to Kafka "orders.accepted"
(8) Kafka --> Matching Engine (consumer for RELIANCE partition):
     receives buy limit order, price=2500, qty=100
(9) Matching Engine: check ask side of RELIANCE order book
     - Best ask: Rs 2,498 x 50 shares (from Seller A, placed at 09:15:01.234)
     - Next ask: Rs 2,499 x 80 shares (from Seller B, placed at 09:15:01.567)
     - Next ask: Rs 2,502 x 200 shares (above our limit, no match)
(10) Matching Engine: MATCH -- buy at 2500 crosses ask at 2498
      Trade 1: buyer=Trader, seller=A, price=2498, qty=50
(11) Matching Engine: MATCH -- buy at 2500 crosses ask at 2499
      Trade 2: buyer=Trader, seller=B, price=2499, qty=50
(12) Matching Engine: 100 filled (50+50), order fully filled
      Remaining buy qty=0, order status=FILLED
(13) Matching Engine --> Kafka "trades.executed":
      publish Trade 1 and Trade 2
(14) Trade Service (consuming Kafka): persist trades, update order status=FILLED
(15) Trade Service --> Portfolio Service: update positions
      Trader: +100 RELIANCE at avg price (2498*50 + 2499*50)/100 = 2498.50
      Seller A: -50 RELIANCE
      Seller B: -50 RELIANCE
(16) Trade Service --> Market Data Service: update RELIANCE LTP=2499, volume+=100
(17) Market Data Service --> WebSocket Hub --> all subscribers:
      {symbol: "RELIANCE", ltp: 2499, volume: 1234500, bid: 2497, ask: 2502}
(18) Notification Service --> Trader: "Order FILLED: Bought 100 RELIANCE @ avg 2498.50"
(19) Notification Service --> Seller A: "Order FILLED: Sold 50 RELIANCE @ 2498"
(20) Notification Service --> Seller B: "Order FILLED: Sold 50 RELIANCE @ 2499"

Settlement (T+1):
(21) Settlement Service: debit Rs 2,49,850 from Trader's account
(22) Settlement Service: credit Rs 1,24,900 to Seller A's account
(23) Settlement Service: credit Rs 1,24,950 to Seller B's account
(24) Settlement Service: transfer 100 shares of RELIANCE to Trader's demat
```

### Why This Is Asked in Interviews

This is a **tier-1 system design** interview question, rated **Hard**. It appears at Goldman Sachs, Morgan Stanley, Citadel, Amazon, Google, and every fintech company because it tests the deepest real-time systems concepts under financial correctness constraints:

| Skill Tested                     | What Interviewers Look For                                              |
|----------------------------------|-------------------------------------------------------------------------|
| **Order Matching Engine**        | Price-time priority algorithm, order book data structures (heaps/trees) |
| **Real-Time Market Data**        | Fan-out of price ticks to millions of subscribers via WebSocket         |
| **Low Latency**                  | p99 < 10ms for order placement; why single-threaded matching per symbol |
| **ACID Transactions**            | Order and trade records must never be lost or duplicated                |
| **Risk Management**              | Pre-trade checks: margin, circuit breakers, position limits            |
| **Event-Driven Architecture**    | Kafka for decoupling order flow from trade settlement and notifications |
| **Concurrency**                  | Why matching engine is single-threaded per symbol; lock-free data paths |
| **Data Structures**              | TreeMap for order book, PriorityQueue for price levels, Queue for FIFO  |
| **State Machine Design**         | Order lifecycle: PENDING -> ACCEPTED -> PARTIAL -> FILLED / CANCELLED  |
| **Partitioning Strategy**        | Partition by symbol for matching; by user for portfolio                 |

> **Interview tip**: Start by clarifying the market model -- is this a full exchange (matching engine) or a broker that routes to an exchange? Most interviews want the exchange model. Draw the order book first -- bids on the left sorted highest-to-lowest, asks on the right sorted lowest-to-highest. Walk through a matching example. The "aha moment" is explaining why the matching engine must be single-threaded per symbol (two threads matching the same order book can cause double-fills) and how you achieve horizontal scale by partitioning symbols across cores/machines.

---

## 2. Scope

### In Scope

| Feature                          | Description                                                              |
|----------------------------------|--------------------------------------------------------------------------|
| Order Placement                  | Market, limit, stop-loss, stop-limit orders with full lifecycle          |
| Order Cancel / Modify            | Cancel pending orders, modify price/quantity of resting limit orders     |
| Order Matching Engine            | Price-time priority matching with partial fills and trade generation     |
| Order Book                       | Real-time bid/ask book per symbol with depth levels                      |
| Portfolio / Holdings             | Current positions, average cost, realized + unrealized P&L               |
| Watchlist                        | User-defined watchlists with real-time price updates                     |
| Real-Time Market Data            | LTP tickers, bid/ask, OHLCV candles, trade stream via WebSocket         |
| Order History                    | Full audit trail of all orders with status transitions                   |
| P&L Tracking                     | Real-time unrealized P&L, end-of-day realized P&L                        |
| Risk / Margin Check              | Pre-trade validation: margin, position limits, circuit breakers          |
| Settlement                       | T+1 settlement of funds and securities                                   |
| Account Management               | Balance, margin, fund add/withdraw                                       |
| Notifications                    | Order fills, price alerts, margin calls                                  |

### Out of Scope

| Feature                          | Reason                                                                   |
|----------------------------------|--------------------------------------------------------------------------|
| Derivatives (F&O)               | Options/futures pricing adds Greeks, expiry -- separate deep dive        |
| IPO / OFS / Buyback             | Primary market operations, different workflow                            |
| Mutual Funds / SIP              | NAV-based, no real-time matching -- different system                     |
| Algo Trading / API Bots         | Co-location, FIX protocol -- extension of core platform                  |
| Regulatory Reporting             | SEBI/SEC filings are ops/compliance domain                               |
| Tax (STT, Capital Gains)        | Business logic layer, not core trading infrastructure                    |
| KYC / Account Opening           | Regulatory onboarding, separate identity verification system             |
| Cross-Exchange Arbitrage         | Multi-exchange routing is an advanced extension                          |
| Auction / Pre-Open Session      | Special matching rules for opening/closing auctions                      |

---

## 3. Assumptions

### Platform Scale

| Parameter                        | Value                                    |
|----------------------------------|------------------------------------------|
| Total registered users           | 10 million                               |
| Daily active users (DAU)         | 1 million                                |
| Concurrent users (market hours)  | 500,000                                  |
| Total orders per day (peak)      | 50 million                               |
| Orders per second (avg)          | 50M / 6.25hrs / 3600 = ~2,200 OPS       |
| Orders per second (peak)         | 10x avg = ~22,000 OPS                    |
| Orders per second (market open)  | 50x avg = ~110,000 OPS (burst)           |
| Trades per day                   | ~30 million (60% fill rate)              |
| Listed symbols                   | 5,000                                    |
| Market data tick rate            | 1,000 ticks/sec per symbol               |
| Total market data events/sec     | 5,000 symbols * 1,000 = 5M ticks/sec    |
| WebSocket connections            | 500,000 concurrent                       |
| Average order size               | ~Rs 50,000 (~$600)                       |
| Market hours                     | 9:15 AM - 3:30 PM (6.25 hours)          |

### Back-of-Envelope Calculations

```
Storage per day:
  Orders: 50M orders * 500 bytes = 25 GB/day
  Trades: 30M trades * 300 bytes = 9 GB/day
  Market data ticks: 5M/sec * 100 bytes * 22,500 sec = 11.25 TB/day (raw)
  OHLCV candles: 5,000 symbols * 375 (1-min candles) * 100 bytes = 187 MB/day

Bandwidth:
  Market data fan-out: 500K users * 100 bytes/tick * 10 ticks/sec = 500 GB/sec (raw)
  With smart subscription: avg user watches 20 symbols
    500K * 20 symbols * 100 bytes * 1 tick/sec = 1 GB/sec
    With batching (100ms windows): 100 MB/sec

Memory for order books:
  Per symbol: ~10,000 resting orders * 200 bytes = 2 MB
  All symbols: 5,000 * 2 MB = 10 GB (fits in RAM)
```

### Latency Targets

| Operation                        | Target                                   |
|----------------------------------|------------------------------------------|
| Order placement (API to ack)     | p99 < 10ms                               |
| Order matching (engine)          | p99 < 1ms                                |
| Trade confirmation to user       | p99 < 50ms                               |
| Market data tick to client       | p99 < 50ms                               |
| Order book update                | p99 < 5ms                                |
| Portfolio query                  | p99 < 100ms                              |

---

## 4. Functional Requirements

### FR-1: Place Order

```
POST /orders

User can place:
  - Market Order: execute immediately at best available price
  - Limit Order: execute at specified price or better
  - Stop-Loss Order: trigger market order when price hits threshold
  - Stop-Limit Order: trigger limit order when price hits threshold

Required fields: symbol, side (BUY/SELL), type, quantity
Conditional fields: price (for LIMIT), trigger_price (for STOP-LOSS)

Response: order_id, status=PENDING_RISK_CHECK, timestamp
```

### FR-2: Cancel / Modify Order

```
DELETE /orders/{order_id}
  - Cancel a resting (unmatched) order
  - Cannot cancel already filled or cancelled orders
  - Partial cancel: reduce quantity of partially filled order

PUT /orders/{order_id}
  - Modify price and/or quantity of a resting limit order
  - Modification = cancel old + place new (loses time priority)
```

### FR-3: Order Book (Bid/Ask)

```
GET /market-data/{symbol}/depth

Returns:
  bids: [{price: 2498, qty: 500, orders: 12}, ...]  -- top 5/20 levels
  asks: [{price: 2502, qty: 300, orders: 8}, ...]   -- top 5/20 levels
  ltp: 2499
  volume: 1,234,500
```

### FR-4: Portfolio / Holdings

```
GET /portfolio

Returns:
  holdings: [
    {symbol: "RELIANCE", qty: 100, avg_cost: 2498.50,
     ltp: 2510, unrealized_pnl: +1150, day_change: +0.46%},
    ...
  ]
  total_invested: 5,00,000
  current_value: 5,12,000
  total_pnl: +12,000 (+2.4%)
```

### FR-5: Watchlist

```
GET /watchlists
POST /watchlists  {name: "Tech Stocks", symbols: ["TCS", "INFY", "WIPRO"]}
PUT /watchlists/{id}  {symbols: [...]}

Real-time price updates via WebSocket for watchlist symbols.
```

### FR-6: Real-Time Market Data

```
WebSocket /ws/market-data?symbols=RELIANCE,TCS,INFY

Stream:
  {symbol: "RELIANCE", ltp: 2499, bid: 2498, ask: 2502,
   volume: 1234500, open: 2480, high: 2515, low: 2475,
   change: +19, change_pct: +0.76%, timestamp: 1714123456789}
```

### FR-7: Order History

```
GET /orders?status=ALL&from=2026-04-01&to=2026-04-26

Returns all orders with:
  order_id, symbol, side, type, price, qty, filled_qty,
  avg_fill_price, status, timestamps (placed, accepted, filled/cancelled)
```

### FR-8: P&L Tracking

```
GET /portfolio/pnl

Returns:
  realized_pnl: +45,000 (from closed positions today)
  unrealized_pnl: +12,000 (from open positions, mark-to-market)
  total_charges: 1,200 (brokerage + STT + stamp duty)
  net_pnl: +55,800
```

### FR-9: Margin / Risk Check

```
Pre-trade (synchronous, before matching):
  - Sufficient balance/margin for the order?
  - Position within regulatory limits?
  - Price within circuit breaker band?
  - Order rate within per-user throttle?

If any check fails: order REJECTED with reason.
```

---

## 5. Non-Functional Requirements

| Requirement          | Target                        | Rationale                                           |
|----------------------|-------------------------------|-----------------------------------------------------|
| Order Latency        | p99 < 10ms (API to ack)      | Traders expect sub-10ms; competitive differentiation |
| Matching Latency     | p99 < 1ms per match           | Engine must be fast enough for 22K+ OPS              |
| Market Data Latency  | p99 < 50ms (tick to client)   | Stale data = bad trades = lost users                 |
| Availability         | 99.999% during market hours   | 6.25 hrs/day; 99.999% = 2.3 sec downtime/day        |
| Durability           | Zero data loss for orders     | Every order must be persisted before ack              |
| Throughput           | 50M orders/day, 22K OPS peak  | Handle market-open bursts without dropping orders     |
| Consistency          | Strong for orders/trades      | No double-fills, no phantom trades                   |
| Market Data Fan-out  | 500K concurrent WebSockets    | Every connected user gets real-time ticks             |
| Audit Trail          | Immutable, complete           | Regulatory requirement for all order state changes    |
| Recoverability       | < 30 sec failover             | Matching engine failover must replay from WAL/Kafka   |

---

## 6. API Design

### 6.1 REST APIs

#### Place Order

```
POST /api/v1/orders
Authorization: Bearer <jwt_token>
Idempotency-Key: <uuid>

Request:
{
    "symbol": "RELIANCE",
    "side": "BUY",               // BUY | SELL
    "type": "LIMIT",             // MARKET | LIMIT | STOP_LOSS | STOP_LIMIT
    "price": 2500.00,            // required for LIMIT, STOP_LIMIT
    "trigger_price": null,       // required for STOP_LOSS, STOP_LIMIT
    "quantity": 100,
    "validity": "DAY",           // DAY | IOC | GTC | GTD
    "product": "DELIVERY",       // DELIVERY | INTRADAY
    "disclosed_qty": null        // iceberg order: show only this qty in book
}

Response (201 Created):
{
    "order_id": "ORD-20260426-000001",
    "status": "PENDING_RISK_CHECK",
    "symbol": "RELIANCE",
    "side": "BUY",
    "type": "LIMIT",
    "price": 2500.00,
    "quantity": 100,
    "filled_quantity": 0,
    "placed_at": "2026-04-26T09:15:01.234Z",
    "message": "Order received, pending risk check"
}

Error (400 Bad Request):
{
    "error": "INSUFFICIENT_MARGIN",
    "message": "Required margin: Rs 2,50,000. Available: Rs 1,80,000.",
    "available_margin": 180000,
    "required_margin": 250000
}
```

#### Cancel Order

```
DELETE /api/v1/orders/{order_id}
Authorization: Bearer <jwt_token>

Response (200 OK):
{
    "order_id": "ORD-20260426-000001",
    "status": "CANCEL_REQUESTED",
    "message": "Cancel request submitted"
}

Error (409 Conflict):
{
    "error": "ORDER_ALREADY_FILLED",
    "message": "Cannot cancel order ORD-20260426-000001: status is FILLED"
}
```

#### Get Orders

```
GET /api/v1/orders?status=OPEN&symbol=RELIANCE&from=2026-04-26&to=2026-04-26
Authorization: Bearer <jwt_token>

Response (200 OK):
{
    "orders": [
        {
            "order_id": "ORD-20260426-000001",
            "symbol": "RELIANCE",
            "side": "BUY",
            "type": "LIMIT",
            "price": 2500.00,
            "quantity": 100,
            "filled_quantity": 50,
            "avg_fill_price": 2498.00,
            "status": "PARTIALLY_FILLED",
            "placed_at": "2026-04-26T09:15:01.234Z",
            "updated_at": "2026-04-26T09:15:01.567Z"
        }
    ],
    "total": 1,
    "page": 1,
    "page_size": 50
}
```

#### Get Portfolio

```
GET /api/v1/portfolio
Authorization: Bearer <jwt_token>

Response (200 OK):
{
    "holdings": [
        {
            "symbol": "RELIANCE",
            "quantity": 100,
            "avg_cost": 2498.50,
            "ltp": 2510.00,
            "current_value": 251000.00,
            "invested_value": 249850.00,
            "unrealized_pnl": 1150.00,
            "unrealized_pnl_pct": 0.46,
            "day_change": 11.50,
            "day_change_pct": 0.46
        }
    ],
    "summary": {
        "total_invested": 500000.00,
        "current_value": 512000.00,
        "total_pnl": 12000.00,
        "total_pnl_pct": 2.40,
        "day_pnl": 3500.00
    }
}
```

#### Get Market Data

```
GET /api/v1/market-data/{symbol}
Authorization: Bearer <jwt_token>

Response (200 OK):
{
    "symbol": "RELIANCE",
    "ltp": 2499.00,
    "open": 2480.00,
    "high": 2515.00,
    "low": 2475.00,
    "close": 2480.00,
    "volume": 1234500,
    "bid": 2498.00,
    "ask": 2502.00,
    "bid_qty": 500,
    "ask_qty": 300,
    "upper_circuit": 2976.00,
    "lower_circuit": 1984.00,
    "timestamp": "2026-04-26T09:30:15.789Z"
}
```

#### Get Order Book Depth

```
GET /api/v1/market-data/{symbol}/depth
Authorization: Bearer <jwt_token>

Response (200 OK):
{
    "symbol": "RELIANCE",
    "bids": [
        {"price": 2498.00, "quantity": 500, "orders": 12},
        {"price": 2497.00, "quantity": 800, "orders": 18},
        {"price": 2496.00, "quantity": 1200, "orders": 25},
        {"price": 2495.00, "quantity": 600, "orders": 15},
        {"price": 2494.00, "quantity": 400, "orders": 10}
    ],
    "asks": [
        {"price": 2502.00, "quantity": 300, "orders": 8},
        {"price": 2503.00, "quantity": 450, "orders": 11},
        {"price": 2504.00, "quantity": 700, "orders": 16},
        {"price": 2505.00, "quantity": 350, "orders": 9},
        {"price": 2506.00, "quantity": 550, "orders": 14}
    ],
    "ltp": 2499.00,
    "total_buy_qty": 45000,
    "total_sell_qty": 38000,
    "timestamp": "2026-04-26T09:30:15.789Z"
}
```

### 6.2 WebSocket APIs

#### Market Data Stream

```
WebSocket /ws/v1/market-data
Authorization: Bearer <jwt_token> (sent in first message or query param)

Client -> Server (subscribe):
{
    "action": "subscribe",
    "symbols": ["RELIANCE", "TCS", "INFY"],
    "mode": "full"           // full | quote | ltp
}

Server -> Client (tick):
{
    "type": "tick",
    "symbol": "RELIANCE",
    "ltp": 2499.00,
    "bid": 2498.00,
    "ask": 2502.00,
    "volume": 1234500,
    "open": 2480.00,
    "high": 2515.00,
    "low": 2475.00,
    "change": 19.00,
    "change_pct": 0.76,
    "timestamp": 1714123456789
}

Client -> Server (unsubscribe):
{
    "action": "unsubscribe",
    "symbols": ["INFY"]
}
```

#### Order Updates Stream

```
WebSocket /ws/v1/orders
Authorization: Bearer <jwt_token>

Server -> Client (order update):
{
    "type": "order_update",
    "order_id": "ORD-20260426-000001",
    "status": "FILLED",
    "symbol": "RELIANCE",
    "side": "BUY",
    "filled_quantity": 100,
    "avg_fill_price": 2498.50,
    "timestamp": 1714123456900
}

Server -> Client (trade):
{
    "type": "trade",
    "trade_id": "TRD-20260426-000001",
    "order_id": "ORD-20260426-000001",
    "symbol": "RELIANCE",
    "side": "BUY",
    "price": 2498.00,
    "quantity": 50,
    "timestamp": 1714123456800
}
```

---

## 7. Data Model

### Entity Relationship

```
+----------+       +----------+       +-------+
|   User   |1----*|  Account  |       | Stock |
+----------+       +----------+       +-------+
     |                  |                  |
     |1                 |1                 |1
     |                  |                  |
     *                  *                  *
+----------+       +----------+    +------------+
|Watchlist |       |  Order   |----| MarketData |
+----------+       +----------+    +------------+
                     |    |
                     |    |1
                     |    |
                     |    *
                     |  +----------+
                     |  |  Trade   |
                     |  +----------+
                     |
                     |1
                     |
                     *
                +----------+
                | Position |
                +----------+
```

### User

```
+------------------+------------------+--------------------------------------+
| Column           | Type             | Description                          |
+------------------+------------------+--------------------------------------+
| user_id          | UUID (PK)        | Unique user identifier               |
| username         | VARCHAR(50)      | Login username                       |
| email            | VARCHAR(255)     | Email address (unique)               |
| phone            | VARCHAR(15)      | Phone number (unique)                |
| pan_number       | VARCHAR(10)      | PAN for tax/KYC (Indian context)     |
| status           | ENUM             | ACTIVE, SUSPENDED, CLOSED            |
| created_at       | TIMESTAMP        | Account creation time                |
| updated_at       | TIMESTAMP        | Last update time                     |
+------------------+------------------+--------------------------------------+
```

### Account

```
+------------------+------------------+--------------------------------------+
| Column           | Type             | Description                          |
+------------------+------------------+--------------------------------------+
| account_id       | UUID (PK)        | Unique account identifier            |
| user_id          | UUID (FK)        | References User                      |
| balance          | DECIMAL(18,2)    | Available cash balance               |
| blocked_margin   | DECIMAL(18,2)    | Margin blocked for open orders       |
| used_margin      | DECIMAL(18,2)    | Margin used for intraday positions   |
| total_deposits   | DECIMAL(18,2)    | Lifetime deposits                    |
| total_withdrawals| DECIMAL(18,2)    | Lifetime withdrawals                 |
| account_type     | ENUM             | INDIVIDUAL, CORPORATE                |
| status           | ENUM             | ACTIVE, FROZEN, CLOSED               |
| created_at       | TIMESTAMP        | Account creation time                |
| updated_at       | TIMESTAMP        | Last update time                     |
+------------------+------------------+--------------------------------------+

Key constraint: balance >= 0 (enforced at DB level)
Invariant: balance + blocked_margin + used_margin = total_funds
```

### Stock

```
+------------------+------------------+--------------------------------------+
| Column           | Type             | Description                          |
+------------------+------------------+--------------------------------------+
| symbol           | VARCHAR(20) (PK) | Trading symbol (e.g., "RELIANCE")    |
| isin             | VARCHAR(12)      | ISIN code (unique)                   |
| name             | VARCHAR(200)     | Full company name                    |
| exchange         | ENUM             | NSE, BSE                             |
| segment          | ENUM             | EQUITY, FNO, CURRENCY                |
| lot_size         | INT              | Minimum tradeable quantity           |
| tick_size        | DECIMAL(10,2)    | Minimum price movement (0.05)        |
| upper_circuit    | DECIMAL(12,2)    | Upper price band                     |
| lower_circuit    | DECIMAL(12,2)    | Lower price band                     |
| previous_close   | DECIMAL(12,2)    | Previous day's closing price         |
| face_value       | DECIMAL(10,2)    | Face value of share                  |
| listing_date     | DATE             | IPO listing date                     |
| status           | ENUM             | ACTIVE, SUSPENDED, DELISTED          |
+------------------+------------------+--------------------------------------+
```

### Order

```
+------------------+------------------+--------------------------------------+
| Column           | Type             | Description                          |
+------------------+------------------+--------------------------------------+
| order_id         | VARCHAR(30) (PK) | e.g., "ORD-20260426-000001"         |
| user_id          | UUID (FK)        | References User                      |
| account_id       | UUID (FK)        | References Account                   |
| symbol           | VARCHAR(20) (FK) | Trading symbol                       |
| side             | ENUM             | BUY, SELL                            |
| order_type       | ENUM             | MARKET, LIMIT, STOP_LOSS, STOP_LIMIT|
| price            | DECIMAL(12,2)    | Limit price (null for MARKET)        |
| trigger_price    | DECIMAL(12,2)    | Stop trigger price (null if N/A)     |
| quantity         | INT              | Total order quantity                 |
| filled_quantity  | INT              | Quantity already matched             |
| pending_quantity | INT              | quantity - filled_quantity            |
| avg_fill_price   | DECIMAL(12,2)    | Weighted average fill price          |
| validity         | ENUM             | DAY, IOC, GTC, GTD                   |
| product          | ENUM             | DELIVERY, INTRADAY                   |
| disclosed_qty    | INT              | Visible qty in book (iceberg)        |
| status           | ENUM             | See OrderStatus below                |
| rejection_reason | VARCHAR(500)     | Reason if REJECTED                   |
| placed_at        | TIMESTAMP(6)     | Microsecond precision                |
| accepted_at      | TIMESTAMP(6)     | When risk check passed               |
| filled_at        | TIMESTAMP(6)     | When fully/partially filled          |
| cancelled_at     | TIMESTAMP(6)     | When cancelled                       |
| updated_at       | TIMESTAMP(6)     | Last state change                    |
+------------------+------------------+--------------------------------------+

Indexes:
  - (user_id, placed_at DESC) -- user's order history
  - (symbol, status) -- active orders per symbol
  - (status, placed_at) -- pending orders scan
```

### OrderType (Enum)

```
MARKET       -- execute at best available price, no price specified
LIMIT        -- execute at specified price or better
STOP_LOSS    -- becomes MARKET when LTP crosses trigger_price
STOP_LIMIT   -- becomes LIMIT when LTP crosses trigger_price
```

### OrderSide (Enum)

```
BUY          -- buying shares
SELL         -- selling shares
```

### OrderStatus (Enum / State Machine)

```
PENDING_RISK_CHECK  -- order received, awaiting risk validation
ACCEPTED            -- risk check passed, sent to matching engine
OPEN                -- resting in order book, awaiting match
PARTIALLY_FILLED    -- some quantity matched, remainder in book
FILLED              -- fully matched, all quantity executed
CANCELLED           -- cancelled by user or system
REJECTED            -- failed risk check or validation
EXPIRED             -- DAY order not filled by market close
CANCEL_REQUESTED    -- cancel submitted, awaiting engine ack

State transitions:
  PENDING_RISK_CHECK --> ACCEPTED (risk passed)
  PENDING_RISK_CHECK --> REJECTED (risk failed)
  ACCEPTED --> OPEN (placed in order book, no immediate match)
  ACCEPTED --> PARTIALLY_FILLED (immediate partial match)
  ACCEPTED --> FILLED (immediate full match -- market order)
  OPEN --> PARTIALLY_FILLED (match found)
  OPEN --> FILLED (full match)
  OPEN --> CANCELLED (user cancel)
  OPEN --> EXPIRED (market close)
  PARTIALLY_FILLED --> FILLED (remaining matched)
  PARTIALLY_FILLED --> CANCELLED (user cancels remainder)
  OPEN --> CANCEL_REQUESTED --> CANCELLED
```

### Trade

```
+------------------+------------------+--------------------------------------+
| Column           | Type             | Description                          |
+------------------+------------------+--------------------------------------+
| trade_id         | VARCHAR(30) (PK) | e.g., "TRD-20260426-000001"         |
| symbol           | VARCHAR(20)      | Trading symbol                       |
| buy_order_id     | VARCHAR(30) (FK) | Buyer's order ID                     |
| sell_order_id    | VARCHAR(30) (FK) | Seller's order ID                    |
| buyer_id         | UUID             | Buyer user ID                        |
| seller_id        | UUID             | Seller user ID                       |
| price            | DECIMAL(12,2)    | Execution price                      |
| quantity         | INT              | Executed quantity                    |
| trade_value      | DECIMAL(18,2)    | price * quantity                     |
| buyer_brokerage  | DECIMAL(10,2)    | Brokerage charged to buyer           |
| seller_brokerage | DECIMAL(10,2)    | Brokerage charged to seller          |
| traded_at        | TIMESTAMP(6)     | Execution timestamp (microsecond)    |
| settlement_date  | DATE             | T+1 settlement date                  |
| settlement_status| ENUM             | PENDING, SETTLED, FAILED             |
+------------------+------------------+--------------------------------------+

Indexes:
  - (symbol, traded_at DESC) -- trade tape per symbol
  - (buyer_id, traded_at DESC) -- buyer's trade history
  - (seller_id, traded_at DESC) -- seller's trade history
  - (settlement_date, settlement_status) -- settlement batch
```

### Position

```
+------------------+------------------+--------------------------------------+
| Column           | Type             | Description                          |
+------------------+------------------+--------------------------------------+
| position_id      | UUID (PK)        | Unique position identifier           |
| user_id          | UUID (FK)        | References User                      |
| account_id       | UUID (FK)        | References Account                   |
| symbol           | VARCHAR(20)      | Trading symbol                       |
| quantity         | INT              | Current holding quantity             |
| avg_cost         | DECIMAL(12,2)    | Volume-weighted average cost         |
| invested_value   | DECIMAL(18,2)    | quantity * avg_cost                  |
| product          | ENUM             | DELIVERY, INTRADAY                   |
| realized_pnl     | DECIMAL(18,2)    | P&L from closed portions             |
| opened_at        | TIMESTAMP        | When first acquired                  |
| updated_at       | TIMESTAMP        | Last trade update                    |
+------------------+------------------+--------------------------------------+

Unique constraint: (user_id, symbol, product) -- one position per symbol per product type
```

### MarketData

```
+------------------+------------------+--------------------------------------+
| Column           | Type             | Description                          |
+------------------+------------------+--------------------------------------+
| symbol           | VARCHAR(20) (PK) | Trading symbol                       |
| ltp              | DECIMAL(12,2)    | Last traded price                    |
| open             | DECIMAL(12,2)    | Day open price                       |
| high             | DECIMAL(12,2)    | Day high                             |
| low              | DECIMAL(12,2)    | Day low                              |
| close            | DECIMAL(12,2)    | Previous close                       |
| volume           | BIGINT           | Total traded volume today            |
| turnover         | DECIMAL(18,2)    | Total traded value today             |
| best_bid         | DECIMAL(12,2)    | Best bid price                       |
| best_bid_qty     | INT              | Best bid quantity                    |
| best_ask         | DECIMAL(12,2)    | Best ask price                       |
| best_ask_qty     | INT              | Best ask quantity                    |
| upper_circuit    | DECIMAL(12,2)    | Upper circuit limit                  |
| lower_circuit    | DECIMAL(12,2)    | Lower circuit limit                  |
| oi               | BIGINT           | Open interest (for derivatives)      |
| updated_at       | TIMESTAMP(6)     | Last tick timestamp                  |
+------------------+------------------+--------------------------------------+

Note: This is stored in Redis for real-time access. PostgreSQL for persistence.
```

### OrderBook (In-Memory)

```
Per symbol, maintained in matching engine memory:

OrderBook {
    symbol: "RELIANCE"
    bids: TreeMap<Price, Queue<OrderBookEntry>>  -- sorted descending
    asks: TreeMap<Price, Queue<OrderBookEntry>>  -- sorted ascending
    lastTradedPrice: 2499.00
    totalBidQty: 45000
    totalAskQty: 38000
}
```

### OrderBookEntry (In-Memory)

```
OrderBookEntry {
    order_id: "ORD-20260426-000001"
    user_id: UUID
    price: 2500.00
    quantity: 100           -- original quantity
    remaining_qty: 60       -- unfilled quantity
    timestamp: 1714123456789 -- for time priority (earlier = higher priority)
    side: BUY
}
```

### Watchlist

```
+------------------+------------------+--------------------------------------+
| Column           | Type             | Description                          |
+------------------+------------------+--------------------------------------+
| watchlist_id     | UUID (PK)        | Unique watchlist identifier          |
| user_id          | UUID (FK)        | References User                      |
| name             | VARCHAR(100)     | Watchlist name                       |
| symbols          | VARCHAR[]        | Array of symbols                     |
| created_at       | TIMESTAMP        | Creation time                        |
| updated_at       | TIMESTAMP        | Last update time                     |
+------------------+------------------+--------------------------------------+

Constraint: max 5 watchlists per user, max 50 symbols per watchlist
```

---

## 8. High-Level Architecture

```
+-------------------------------------------------------------------+
|                         CLIENT LAYER                               |
|                                                                    |
|  +---------------+  +---------------+  +----------------------+    |
|  |  Mobile App   |  |   Web App     |  |  Trading Terminal    |    |
|  | (iOS/Android) |  | (React/Next)  |  | (Desktop, DMA)      |    |
|  +-------+-------+  +-------+-------+  +----------+-----------+    |
|          |                  |                      |               |
+-------------------------------------------------------------------+
           |                  |                      |
           +------------------+----------------------+
                              |
                              v
+-------------------------------------------------------------------+
|                       API GATEWAY                                  |
|                                                                    |
|  +------------------------------------------------------------+   |
|  | - JWT Authentication          - Rate Limiting (per user)   |   |
|  | - Request Validation          - SSL Termination            |   |
|  | - Route to Service            - WebSocket Upgrade          |   |
|  | - Idempotency Check           - Request Logging            |   |
|  +------------------------------------------------------------+   |
+-------------------------------------------------------------------+
           |                       |                |
           v                       v                v
+-------------------+  +------------------+  +------------------+
|  ORDER SERVICE    |  | PORTFOLIO SVC    |  | ACCOUNT SERVICE  |
|                   |  |                  |  |                  |
| - Validate order  |  | - Holdings       |  | - Balance        |
| - Persist order   |  | - Positions      |  | - Margin mgmt    |
| - Lifecycle mgmt  |  | - P&L calc       |  | - Fund add/      |
| - Cancel/modify   |  | - Avg cost       |  |   withdraw       |
+--------+----------+  +------------------+  +------------------+
         |
         v
+-------------------+
|   RISK ENGINE     |
|                   |
| - Margin check    |
| - Position limits |
| - Circuit breaker |
| - Rate limiting   |
| - Price band check|
+---------+---------+
          |
          v (order accepted)
+-------------------------------------------------------------------+
|                      KAFKA CLUSTER                                 |
|                                                                    |
|  orders.accepted  |  trades.executed  |  market.ticks  |  events  |
+-------------------------------------------------------------------+
          |                    ^                ^
          v                    |                |
+-------------------------------------------------------------------+
|                    MATCHING ENGINE CLUSTER                          |
|                                                                    |
|  +------------------+  +------------------+  +------------------+  |
|  | Engine Instance 1|  | Engine Instance 2|  | Engine Instance 3|  |
|  | Symbols: A-F     |  | Symbols: G-N     |  | Symbols: O-Z     |  |
|  |                  |  |                  |  |                  |  |
|  | +--------------+ |  | +--------------+ |  | +--------------+ |  |
|  | |  Order Book  | |  | |  Order Book  | |  | |  Order Book  | |  |
|  | |  RELIANCE    | |  | |  INFY        | |  | |  TCS         | |  |
|  | |  Bids | Asks | |  | |  Bids | Asks | |  | |  Bids | Asks | |  |
|  | | 2498  | 2502 | |  | |  1450 | 1453 | |  | |  3200 | 3205 | |  |
|  | | 2497  | 2503 | |  | |  1449 | 1454 | |  | |  3199 | 3206 | |  |
|  | | 2496  | 2504 | |  | |  1448 | 1455 | |  | |  3198 | 3207 | |  |
|  | +--------------+ |  | +--------------+ |  | +--------------+ |  |
|  |                  |  |                  |  |                  |  |
|  | Single-threaded  |  | Single-threaded  |  | Single-threaded  |  |
|  | per symbol!      |  | per symbol!      |  | per symbol!      |  |
|  +------------------+  +------------------+  +------------------+  |
+-------------------------------------------------------------------+
          |
          v (trades produced)
+-------------------------------------------------------------------+
|                      KAFKA CLUSTER                                 |
|  trades.executed  |  orderbook.updates  |  market.ticks            |
+-------------------------------------------------------------------+
     |          |                |                |
     v          v                v                v
+----------+ +----------+ +----------------+ +-------------------+
|  TRADE   | | SETTLE-  | | MARKET DATA    | |  NOTIFICATION     |
|  SERVICE | | MENT SVC | | SERVICE        | |  SERVICE          |
|          | |          | |                | |                   |
| - Persist| | - T+1    | | - LTP update   | | - Order fills     |
|   trades | |   settle | | - OHLCV candles| | - Price alerts    |
| - Update | | - Fund   | | - Depth update | | - Margin calls    |
|   orders | |   xfer   | | - Ticker stream| | - Push/SMS/Email  |
| - Update | | - Share  | |                | |                   |
|   P&L    | |   xfer   | +--------+-------+ +-------------------+
+----------+ +----------+          |
                                   v
                         +-------------------+
                         | WEBSOCKET HUB     |
                         |                   |
                         | - 500K connections|
                         | - Topic-based     |
                         |   subscription    |
                         | - Batched ticks   |
                         |   (100ms window)  |
                         +--------+----------+
                                  |
                                  v
                         +-------------------+
                         |   CLIENTS         |
                         | (Mobile/Web/DMA)  |
                         +-------------------+

STORAGE LAYER:
+-------------------------------------------------------------------+
| +--------------+ +--------+ +--------+ +------------------------+ |
| | PostgreSQL   | | Redis  | | Kafka  | | TimescaleDB            | |
| |              | |        | |        | |                        | |
| | - Orders     | | - Mkt  | | - Event| | - OHLCV candles        | |
| | - Trades     | |   data | |   log  | | - Tick history         | |
| | - Positions  | |   cache| | - Order| | - Volume profiles      | |
| | - Accounts   | | - Sess | |   stream| |                       | |
| | - Users      | |   ions | | - Trade| |                        | |
| |              | | - Rate | |   stream| |                       | |
| |              | |   limit| |        | |                        | |
| +--------------+ +--------+ +--------+ +------------------------+ |
+-------------------------------------------------------------------+
```

### Request Flow: Place Order (Numbered Sequence)

```
+--------+    +--------+    +--------+    +--------+    +----------+    +--------+
| Client |    |API GW  |    |Order   |    | Risk   |    | Matching |    | Trade  |
|        |    |        |    |Service |    | Engine |    | Engine   |    | Service|
+---+----+    +---+----+    +---+----+    +---+----+    +----+-----+    +---+----+
    |             |             |             |              |              |
    |(1) POST     |             |             |              |              |
    |  /orders    |             |             |              |              |
    +------------>|             |             |              |              |
    |             |(2) Auth +   |             |              |              |
    |             | rate limit  |             |              |              |
    |             +------------>|             |              |              |
    |             |             |(3) Validate |              |              |
    |             |             | + persist   |              |              |
    |             |             | (PENDING)   |              |              |
    |             |             +------------>|              |              |
    |             |             |             |(4) Margin    |              |
    |             |             |             | check,       |              |
    |             |             |             | circuit      |              |
    |             |             |             | breaker,     |              |
    |             |             |             | position     |              |
    |             |             |             | limit        |              |
    |             |             |<------------+              |              |
    |             |             |(5) APPROVED |              |              |
    |             |             | margin      |              |              |
    |             |             | blocked     |              |              |
    |             |<-----------+|             |              |              |
    |<------------|             |             |              |              |
    |(6) 201:     |             |             |              |              |
    | order_id,   |             |             |              |              |
    | ACCEPTED    |             |             |              |              |
    |             |             |(7) Publish  |              |              |
    |             |             | to Kafka    |              |              |
    |             |             | orders.     |              |              |
    |             |             | accepted    |              |              |
    |             |             +------------>+------------->|              |
    |             |             |             |              |(8) Check     |
    |             |             |             |              | order book   |
    |             |             |             |              | for matches  |
    |             |             |             |              |              |
    |             |             |             |              |(9) Match!    |
    |             |             |             |              | Generate     |
    |             |             |             |              | trades       |
    |             |             |             |              +------------->|
    |             |             |             |              |              |(10)
    |             |             |             |              |              |Persist
    |             |             |             |              |              |trades,
    |             |             |             |              |              |update
    |             |             |             |              |              |orders
    |             |             |             |              |              |
    |(11) WS: order_update FILLED, trade details             |              |
    |<--------------------------------------------------------------------|
    |             |             |             |              |              |
```

### Request Flow: Market Data Fan-Out

```
+----------+    +--------+    +----------+    +---------+    +--------+
| Matching |    | Kafka  |    | Market   |    |WebSocket|    | Client |
| Engine   |    |        |    | Data Svc |    |  Hub    |    |        |
+----+-----+    +---+----+    +----+-----+    +----+----+    +---+----+
     |              |              |               |              |
     |(1) Trade     |              |               |              |
     | executed:    |              |               |              |
     | RELIANCE     |              |               |              |
     | @2499, qty50 |              |               |              |
     +------------->|              |               |              |
     |              |(2) Consume   |               |              |
     |              | trades.      |               |              |
     |              | executed     |               |              |
     |              +------------->|               |              |
     |              |              |(3) Update     |              |
     |              |              | in-memory:    |              |
     |              |              | LTP=2499      |              |
     |              |              | Volume+=50    |              |
     |              |              | High=max()    |              |
     |              |              | OHLCV candle  |              |
     |              |              |               |              |
     |              |              |(4) Write to   |              |
     |              |              | Redis cache   |              |
     |              |              |               |              |
     |              |              |(5) Batch tick |              |
     |              |              | (100ms window)|              |
     |              |              +-------------->|              |
     |              |              |               |(6) Fan-out   |
     |              |              |               | to all subs  |
     |              |              |               | of RELIANCE  |
     |              |              |               +------------->|
     |              |              |               |              |(7) UI
     |              |              |               |              | update:
     |              |              |               |              | RELIANCE
     |              |              |               |              | 2499
     |              |              |               |              | +0.76%
     |              |              |               |              |
```

---

## 9. Component Deep Dive

### 9.1 Order Service

**Responsibility**: Order placement, validation, lifecycle management, cancel/modify.

```
Order Service Flow:

(1) Receive POST /orders from API Gateway
(2) Validate request:
    - Symbol exists and is ACTIVE?
    - Side is BUY or SELL?
    - Quantity > 0 and within lot size?
    - Price within tick size? (e.g., multiples of 0.05)
    - Limit price within circuit breaker band?
    - Market is open? (9:15 AM - 3:30 PM)
(3) Generate order_id (ORD-{date}-{sequence})
(4) Persist order to PostgreSQL (status=PENDING_RISK_CHECK)
(5) Call Risk Engine synchronously
(6) If APPROVED:
    - Update order status=ACCEPTED
    - Block margin in Account
    - Publish to Kafka topic "orders.accepted" (partitioned by symbol)
    - Return 201 with order_id
(7) If REJECTED:
    - Update order status=REJECTED with reason
    - Return 400 with rejection reason
```

**Cancel Flow:**

```
(1) Receive DELETE /orders/{order_id}
(2) Validate: order belongs to user, status is OPEN or PARTIALLY_FILLED
(3) Update order status=CANCEL_REQUESTED
(4) Publish cancel request to Kafka "orders.cancel" (same partition as symbol)
(5) Matching Engine receives cancel:
    - Remove order from order book
    - Publish cancel confirmation
(6) Order Service: update status=CANCELLED
(7) Account Service: unblock margin for unfilled quantity
(8) Return 200 with CANCELLED status
```

**Modify Flow:**

```
Modify = Cancel + New Order (atomically)

(1) Receive PUT /orders/{order_id} {new_price, new_qty}
(2) Cancel existing order (steps above)
(3) Place new order with modified price/qty
(4) Note: new order loses time priority (placed at current time)
(5) Return new order_id (old order shows CANCELLED, new shows ACCEPTED)
```

**Key Design Decisions:**

- Order is persisted BEFORE sending to matching engine (durability first)
- Risk check is synchronous (must block order if margin insufficient)
- Kafka publish is AFTER persistence (at-least-once delivery; matching engine deduplicates by order_id)
- Order ID includes date for natural partitioning and human readability

---

### 9.2 Matching Engine

**THE core component of this system.** Detailed in Section 10.

**Responsibility**: Maintain order books, match incoming orders against resting orders using price-time priority, generate trades.

```
Matching Engine per symbol:

                    RELIANCE Order Book
    +-------------------+---+-------------------+
    |       BIDS        |   |       ASKS        |
    | (sorted desc)     |   | (sorted asc)      |
    +-------------------+---+-------------------+
    | Price  | Qty | Tm |   | Price  | Qty | Tm |
    +--------+-----+----+---+--------+-----+----+
    | 2498   | 500 | T1 |   | 2502   | 300 | T3 |
    | 2497   | 800 | T2 |   | 2503   | 450 | T4 |
    | 2496   | 1200| T5 |   | 2504   | 700 | T6 |
    | 2495   | 600 | T7 |   | 2505   | 350 | T8 |
    +--------+-----+----+---+--------+-----+----+
    |                   |   |                   |
    | Best Bid: 2498    |   | Best Ask: 2502    |
    |                   |   |                   |
    | Spread: 2502-2498 = 4 (Rs)                |
    +-------------------------------------------+
```

- Single-threaded per symbol for correctness (no locks needed)
- Consumes from Kafka partition (one partition per symbol or symbol group)
- Publishes trades to Kafka "trades.executed"
- Publishes order book updates to Kafka "orderbook.updates"

---

### 9.3 Risk Engine

**Responsibility**: Pre-trade risk validation. MUST execute synchronously before order enters matching engine.

```
Risk Check Pipeline:

(1) MARGIN CHECK
    +--------------------------------------------------+
    | Order: BUY 100 RELIANCE @ 2500 (DELIVERY)       |
    | Required margin = 100 * 2500 = Rs 2,50,000      |
    | User balance = Rs 3,00,000                        |
    | Already blocked = Rs 40,000                       |
    | Available = 3,00,000 - 40,000 = Rs 2,60,000     |
    | 2,60,000 >= 2,50,000? YES --> PASS               |
    +--------------------------------------------------+

    For INTRADAY (margin trading):
    +--------------------------------------------------+
    | Order: BUY 100 RELIANCE @ 2500 (INTRADAY)       |
    | Margin percentage = 20% (5x leverage)             |
    | Required margin = 100 * 2500 * 0.20 = Rs 50,000 |
    | Available = Rs 2,60,000                           |
    | 2,60,000 >= 50,000? YES --> PASS                 |
    +--------------------------------------------------+

(2) POSITION LIMIT CHECK
    +--------------------------------------------------+
    | Current RELIANCE holding: 5,000 shares            |
    | This order: +100 shares                           |
    | New total: 5,100 shares                           |
    | Max allowed: 10% of free float = 50,000 shares   |
    | 5,100 <= 50,000? YES --> PASS                    |
    +--------------------------------------------------+

(3) CIRCUIT BREAKER CHECK
    +--------------------------------------------------+
    | RELIANCE previous close: Rs 2,480                 |
    | Upper circuit: 2,480 * 1.20 = Rs 2,976           |
    | Lower circuit: 2,480 * 0.80 = Rs 1,984           |
    | Order price: Rs 2,500                             |
    | 1,984 <= 2,500 <= 2,976? YES --> PASS            |
    +--------------------------------------------------+

(4) ORDER RATE LIMIT CHECK
    +--------------------------------------------------+
    | User's orders in last 1 second: 45                |
    | Max allowed: 100 orders/sec                       |
    | 45 < 100? YES --> PASS                           |
    +--------------------------------------------------+

(5) PRICE BAND CHECK (sanity)
    +--------------------------------------------------+
    | LTP of RELIANCE: Rs 2,499                         |
    | Order price: Rs 2,500                             |
    | Deviation: 0.04%                                  |
    | Max deviation from LTP: 5%                        |
    | 0.04% < 5%? YES --> PASS                         |
    +--------------------------------------------------+

ALL CHECKS PASSED --> APPROVED, block Rs 2,50,000 margin
ANY CHECK FAILS --> REJECTED with specific reason
```

**Key Design Decisions:**

- Risk Engine is synchronous (not via Kafka) because order MUST NOT reach matching engine without approval
- Margin blocking is atomic with risk approval (DB transaction)
- Risk rules are configurable per symbol, per segment (equity vs F&O)
- Circuit breaker check uses exchange-provided bands (refreshed daily)

---

### 9.4 Market Data Service

**Responsibility**: Aggregate trade data into real-time market feeds, maintain OHLCV candles, provide order book depth.

```
Market Data Processing Pipeline:

+---------------+     +------------------+     +------------------+
| Kafka Topic:  |     | Market Data      |     | Output Channels  |
| trades.       |---->| Processor        |---->|                  |
| executed      |     |                  |     | (1) Redis Cache  |
+---------------+     | (1) Update LTP   |     | (2) WebSocket Hub|
                      | (2) Update OHLCV |     | (3) TimescaleDB  |
+---------------+     | (3) Update Volume|     | (4) Kafka topic: |
| Kafka Topic:  |---->| (4) Calc change% |     |     market.ticks |
| orderbook.    |     | (5) Update depth |     |                  |
| updates       |     +------------------+     +------------------+
+---------------+

Data Aggregation (per symbol):

(1) On each trade:
    - LTP = trade.price
    - Volume += trade.quantity
    - Turnover += trade.value
    - If trade.price > High: High = trade.price
    - If trade.price < Low: Low = trade.price
    - Change = LTP - PreviousClose
    - ChangePct = (Change / PreviousClose) * 100

(2) OHLCV Candle (1-minute):
    - Open = first trade price in minute
    - High = max trade price in minute
    - Low = min trade price in minute
    - Close = last trade price in minute
    - Volume = sum of trade quantities in minute

(3) Tick Batching (100ms window):
    - Collect all updates in 100ms window
    - Send single batched update to WebSocket Hub
    - Reduces message count by 10-100x
```

**Market Data Modes:**

```
LTP Mode (lightweight):
  {symbol, ltp, change, change_pct}
  ~50 bytes per tick

Quote Mode (standard):
  {symbol, ltp, bid, ask, volume, open, high, low, change, change_pct}
  ~120 bytes per tick

Full Mode (depth):
  {symbol, ltp, bid, ask, volume, open, high, low, change, change_pct,
   depth: {bids: [{price, qty, orders} x 5], asks: [{price, qty, orders} x 5]}}
  ~500 bytes per tick
```

---

### 9.5 Portfolio Service

**Responsibility**: Maintain user holdings, calculate P&L (realized and unrealized), compute average cost.

```
Portfolio Update on Trade:

(1) Trade received: BUY 100 RELIANCE @ 2498.50

(2) Check existing position:
    Existing: 200 RELIANCE @ avg_cost 2450.00

(3) Update position (weighted average):
    New qty = 200 + 100 = 300
    New avg_cost = (200 * 2450 + 100 * 2498.50) / 300
                 = (490000 + 249850) / 300
                 = 739850 / 300
                 = 2466.17
    New invested_value = 300 * 2466.17 = 739850

(4) Persist updated position

P&L Calculation:

Unrealized P&L (mark-to-market):
    LTP = 2510
    Current value = 300 * 2510 = 753000
    Invested value = 739850
    Unrealized P&L = 753000 - 739850 = +13,150 (+1.78%)

Realized P&L (on SELL):
    SELL 50 RELIANCE @ 2520
    Sell value = 50 * 2520 = 126000
    Cost basis = 50 * 2466.17 = 123308.50
    Realized P&L = 126000 - 123308.50 = +2,691.50

    Remaining position: 250 RELIANCE @ avg_cost 2466.17
```

**Average Cost Methods:**

```
FIFO (First In, First Out):
  Sell the oldest shares first
  More complex tracking but tax-advantageous

Weighted Average:
  Single average cost for all shares of a symbol
  Simpler, used by most Indian brokers (Zerodha uses this)
  avg_cost = total_invested / total_quantity

We use Weighted Average (industry standard for Indian equities).
```

---

### 9.6 Settlement Service

**Responsibility**: T+1 settlement of funds and securities after trades are executed.

```
Settlement Timeline:

Trade Day (T):
  09:15 AM - Trade executed: BUY 100 RELIANCE @ 2498.50
  03:30 PM - Market close
  05:00 PM - Settlement batch begins:
    (1) Calculate net obligations per user per symbol
    (2) Netting: if user bought 100 and sold 50 RELIANCE today
        Net obligation: BUY 50 RELIANCE

Settlement Day (T+1):
  (1) Clearing corporation processes net obligations
  (2) Fund transfer:
      - Debit Rs 2,49,850 from buyer's account
      - Credit Rs 2,49,850 to seller's account (minus charges)
  (3) Share transfer:
      - Transfer 100 RELIANCE shares from seller's demat
      - Credit 100 RELIANCE shares to buyer's demat
  (4) Update settlement_status = SETTLED for all trades
  (5) Release any excess blocked margin
```

```
Settlement Netting Example:

User A's trades today:
  BUY  100 RELIANCE @ 2498  = -249800
  SELL  50 RELIANCE @ 2510  = +125500
  BUY  200 TCS      @ 3200  = -640000

Net obligations:
  RELIANCE: BUY 50 shares, pay Rs 124,300 (net)
  TCS:      BUY 200 shares, pay Rs 640,000
  Total fund obligation: Rs 764,300
```

---

### 9.7 Account Service

**Responsibility**: Manage user funds -- balance, margin blocking/unblocking, deposits, withdrawals.

```
Account Operations:

(1) Fund Deposit:
    POST /account/deposit {amount: 500000, payment_method: "UPI"}
    - Validate payment
    - Credit balance
    - Audit log entry

(2) Fund Withdrawal:
    POST /account/withdraw {amount: 100000, bank_account: "..."}
    - Check: amount <= (balance - blocked_margin)
    - Debit balance
    - Initiate bank transfer (async)

(3) Margin Block (on order placement):
    - Deduct from balance
    - Add to blocked_margin
    - Atomic DB transaction

(4) Margin Unblock (on order cancel/reject/fill):
    - Deduct from blocked_margin
    - Add back to balance
    - For fills: deducted permanently (trade settlement)

Balance Invariant:
  balance >= 0
  blocked_margin >= 0
  balance + blocked_margin + used_margin = total_available_funds

All operations use SELECT FOR UPDATE to prevent race conditions.
```

---

### 9.8 Notification Service

**Responsibility**: Notify users of trading events via push notification, WebSocket, SMS, email.

```
Notification Triggers:

(1) ORDER FILLS
    Kafka consumer: trades.executed
    "Your order to BUY 100 RELIANCE has been filled at avg price Rs 2,498.50"
    Channel: Push + WebSocket (real-time)

(2) ORDER REJECTION
    "Your order to BUY 100 RELIANCE has been rejected: INSUFFICIENT_MARGIN"
    Channel: Push + WebSocket

(3) PRICE ALERTS
    User sets: "Alert me when RELIANCE crosses Rs 2,600"
    Market Data Service triggers when LTP >= 2,600
    Channel: Push + SMS

(4) MARGIN CALLS
    Risk Engine detects: user's intraday MTM loss exceeds 80% of margin
    "Margin call: Add funds or close positions. Auto-square-off at 3:15 PM"
    Channel: Push + SMS + Email

(5) CORPORATE ACTIONS
    "RELIANCE: Dividend of Rs 8/share. Record date: 2026-05-15"
    Channel: Push + Email

Notification Pipeline:

+--------+     +-----------+     +----------------+     +----------+
| Kafka  |---->| Notif.    |---->| Channel Router |---->| Push/SMS |
| events |     | Processor |     |                |     | /Email/  |
+--------+     | - Template|     | - Push: FCM    |     | WebSocket|
               | - Dedup   |     | - SMS: Twilio  |     +----------+
               | - Throttle|     | - Email: SES   |
               +-----------+     | - WS: Hub      |
                                 +----------------+
```

---

## 10. Order Matching Engine Deep Dive

This is **THE star component** of a stock trading platform interview. If you can explain this well, you've demonstrated strong data structure and algorithm skills under real-world constraints.

### 10.1 Order Book Data Structure

```
The Order Book for each symbol has two sides:

BIDS (Buy Orders):                    ASKS (Sell Orders):
  Sorted by DESCENDING price            Sorted by ASCENDING price
  (highest bid = best bid)              (lowest ask = best ask)

  Within same price level:              Within same price level:
  FIFO (first placed = first filled)    FIFO (first placed = first filled)

Data Structure: TreeMap<BigDecimal, LinkedList<OrderBookEntry>>

  BIDS TreeMap (descending):            ASKS TreeMap (ascending):
  +--------+-------------------------+  +--------+-------------------------+
  | Price  | Queue (FIFO)            |  | Price  | Queue (FIFO)            |
  +--------+-------------------------+  +--------+-------------------------+
  | 2498.00| [Ord-A(500,T1),         |  | 2502.00| [Ord-F(300,T3),         |
  |        |  Ord-B(200,T4)]         |  |        |  Ord-G(100,T6)]        |
  +--------+-------------------------+  +--------+-------------------------+
  | 2497.00| [Ord-C(800,T2)]         |  | 2503.00| [Ord-H(450,T5)]        |
  +--------+-------------------------+  +--------+-------------------------+
  | 2496.00| [Ord-D(1000,T7),        |  | 2504.00| [Ord-I(700,T8)]        |
  |        |  Ord-E(200,T9)]         |  |        |                         |
  +--------+-------------------------+  +--------+-------------------------+

  Best Bid = 2498.00 (first key)       Best Ask = 2502.00 (first key)
  Spread = 2502.00 - 2498.00 = 4.00
```

### 10.2 Price-Time Priority

```
Priority Rule:
  1. PRICE priority: better price gets matched first
     - For BIDS: higher price = better (buyer willing to pay more)
     - For ASKS: lower price = better (seller willing to accept less)

  2. TIME priority: at same price, earlier order gets matched first
     - This is why each price level uses a FIFO queue (LinkedList)

Example: Two sell orders at Rs 2,502
  Ord-F: placed at 09:15:01.234 (T3) -- EARLIER, higher priority
  Ord-G: placed at 09:15:02.567 (T6) -- LATER, lower priority

  When a buy crosses 2502, Ord-F gets filled first (FIFO within price).
```

### 10.3 Market Order Matching

```
Market Order: Execute immediately at best available price.
No price specified -- takes whatever the market offers.

Example: BUY MARKET, qty=200

Step-by-step against the ask book:

  Ask Book (before):
  +--------+-----+----+
  | 2502   | 300 | T3 |  <-- best ask (Ord-F:300)
  | 2503   | 450 | T5 |
  | 2504   | 700 | T8 |
  +--------+-----+----+

  (1) Match against best ask: 2502
      Available at 2502: 300 (Ord-F)
      Need: 200
      Fill: 200 @ 2502 (Ord-F partially filled, 100 remaining)
      Trade: {buyer=Incoming, seller=F, price=2502, qty=200}

  Ask Book (after):
  +--------+-----+----+
  | 2502   | 100 | T3 |  <-- Ord-F reduced from 300 to 100
  | 2503   | 450 | T5 |
  | 2504   | 700 | T8 |
  +--------+-----+----+

  Result: Order FULLY FILLED. 200 @ 2502.
```

### 10.4 Limit Order Matching

```
Limit Order: Execute at specified price or BETTER.
"Better" for a BUY = lower price. "Better" for a SELL = higher price.

Example: BUY LIMIT @ 2503, qty=400

The buyer says: "I'll pay up to Rs 2,503 per share."

Step-by-step against the ask book:

  Ask Book (before):
  +--------+---------------------------+
  | 2502   | [Ord-F(300,T3), Ord-G(100,T6)] |
  | 2503   | [Ord-H(450,T5)]                |
  | 2504   | [Ord-I(700,T8)]                |
  +--------+---------------------------+

  (1) Best ask = 2502. Is 2502 <= 2503 (buyer's limit)? YES --> match
      Ord-F has 300 remaining. Need 400.
      Trade 1: {buyer=Incoming, seller=F, price=2502, qty=300}
      Remaining to fill: 400 - 300 = 100

  (2) Next at 2502: Ord-G has 100.
      Trade 2: {buyer=Incoming, seller=G, price=2502, qty=100}
      Remaining to fill: 100 - 100 = 0

  FULLY FILLED! 300 @ 2502 + 100 @ 2502 = 400 @ avg 2502.00

  Note: Buyer placed limit at 2503 but got filled at 2502!
  This is "price improvement" -- the buyer got a BETTER price.

  Ask Book (after):
  +--------+---------------------------+
  | 2503   | [Ord-H(450,T5)]                |
  | 2504   | [Ord-I(700,T8)]                |
  +--------+---------------------------+
  2502 level completely depleted, removed from TreeMap.
```

### 10.5 Limit Order: Partial Fill + Resting

```
Example: BUY LIMIT @ 2503, qty=600

  Ask Book (before):
  +--------+---------------------------+
  | 2502   | [Ord-F(300,T3), Ord-G(100,T6)] |
  | 2503   | [Ord-H(450,T5)]                |
  | 2504   | [Ord-I(700,T8)]                |
  +--------+---------------------------+

  (1) Best ask = 2502 <= 2503? YES
      Trade 1: seller=F, price=2502, qty=300. Remaining: 300
  (2) Next at 2502: Ord-G, qty=100
      Trade 2: seller=G, price=2502, qty=100. Remaining: 200
  (3) 2502 depleted. Next ask = 2503 <= 2503? YES
      Trade 3: seller=H, price=2503, qty=200 (partial fill of H's 450)
      Remaining: 0. FULLY FILLED!

  Wait -- the buyer wanted 600 but ask book only had 400 at 2502
  and we took 200 from 2503. Let me recalculate:

  (1) Ord-F: 300 @ 2502. Remaining: 600 - 300 = 300
  (2) Ord-G: 100 @ 2502. Remaining: 300 - 100 = 200
  (3) Ord-H: 200 @ 2503 (partial, H had 450). Remaining: 200 - 200 = 0

  FILLED: 300@2502 + 100@2502 + 200@2503 = 600 @ avg 2502.33

  Ask Book (after):
  +--------+---------------------------+
  | 2503   | [Ord-H(250,T5)]  <-- reduced from 450 to 250  |
  | 2504   | [Ord-I(700,T8)]                                |
  +--------+---------------------------+
```

### 10.6 Limit Order: No Match (Rests in Book)

```
Example: BUY LIMIT @ 2500, qty=100

  Ask Book:
  +--------+-----+
  | 2502   | 400 |  <-- best ask is 2502
  | 2503   | 450 |
  +--------+-----+

  Best ask = 2502. Is 2502 <= 2500? NO --> no match.
  The buyer's limit (2500) is below the best ask (2502).
  Nobody is willing to sell at 2500 or lower right now.

  Action: order RESTS in the BID side of the book.

  Bid Book (after):
  +--------+-----+----+
  | 2500   | 100 | T* |  <-- new entry! (if 2500 > current best bid)
  | 2498   | 500 | T1 |
  | 2497   | 800 | T2 |
  +--------+-----+----+

  Order status: OPEN (resting in book, waiting for a matching sell)
  When a SELL order comes at Rs 2500 or lower, this bid will match.
```

### 10.7 Matching Algorithm (Pseudocode)

```java
// Core matching algorithm for an incoming order
List<Trade> match(Order incoming) {
    List<Trade> trades = new ArrayList<>();
    
    // Determine which side of the book to match against
    TreeMap<BigDecimal, LinkedList<OrderBookEntry>> oppositeBook;
    if (incoming.side == BUY) {
        oppositeBook = asks;  // buy matches against asks
    } else {
        oppositeBook = bids;  // sell matches against bids
    }
    
    while (incoming.remainingQty > 0 && !oppositeBook.isEmpty()) {
        // Get best price level from opposite side
        Map.Entry<BigDecimal, LinkedList<OrderBookEntry>> bestLevel;
        if (incoming.side == BUY) {
            bestLevel = oppositeBook.firstEntry();  // lowest ask
        } else {
            bestLevel = oppositeBook.firstEntry();  // highest bid (descending map)
        }
        
        BigDecimal bestPrice = bestLevel.getKey();
        
        // Price check: does this order cross the best price?
        if (incoming.type == LIMIT) {
            if (incoming.side == BUY && bestPrice.compareTo(incoming.price) > 0) {
                break;  // best ask > buy limit --> no match
            }
            if (incoming.side == SELL && bestPrice.compareTo(incoming.price) < 0) {
                break;  // best bid < sell limit --> no match
            }
        }
        // MARKET orders always match (no price check)
        
        // Match against orders at this price level (FIFO)
        LinkedList<OrderBookEntry> queue = bestLevel.getValue();
        while (incoming.remainingQty > 0 && !queue.isEmpty()) {
            OrderBookEntry resting = queue.peek();
            
            // Determine fill quantity
            int fillQty = Math.min(incoming.remainingQty, resting.remainingQty);
            
            // Execution price = resting order's price (price improvement for taker)
            BigDecimal execPrice = resting.price;
            
            // Generate trade
            Trade trade = new Trade(
                incoming.orderId,   // taker
                resting.orderId,    // maker
                incoming.symbol,
                execPrice,
                fillQty,
                Instant.now()
            );
            trades.add(trade);
            
            // Update quantities
            incoming.remainingQty -= fillQty;
            resting.remainingQty -= fillQty;
            
            // Remove fully filled resting order
            if (resting.remainingQty == 0) {
                queue.poll();
            }
        }
        
        // Remove empty price level
        if (queue.isEmpty()) {
            oppositeBook.pollFirstEntry();
        }
    }
    
    // If incoming order has remaining qty and is LIMIT, add to book
    if (incoming.remainingQty > 0 && incoming.type == LIMIT) {
        addToBook(incoming);  // rests in book
    }
    // If MARKET with remaining qty: no match available
    // (can reject remainder or convert to limit at LTP)
    
    return trades;
}
```

### 10.8 Trade Generation

```
Each match produces a Trade record:

Trade {
    trade_id:      "TRD-20260426-000042"
    symbol:        "RELIANCE"
    buy_order_id:  "ORD-20260426-000001"  (the incoming buy)
    sell_order_id: "ORD-20260426-000015"  (the resting sell)
    buyer_id:      UUID-of-buyer
    seller_id:     UUID-of-seller
    price:         2498.00               (resting order's price)
    quantity:      50                     (min of both remaining)
    trade_value:   124900.00             (price * quantity)
    traded_at:     2026-04-26T09:15:01.567890Z
}

One incoming order can generate MULTIPLE trades (partial fills):

BUY 200 @ MARKET -->
  Trade 1: 50 @ 2498 (against resting ask Ord-X)
  Trade 2: 100 @ 2498 (against resting ask Ord-Y)
  Trade 3: 50 @ 2499 (against resting ask Ord-Z, next price level)

Total: 200 shares filled across 3 trades, avg price = 2498.25
```

### 10.9 Order Book Update Events

```
After every match, the matching engine publishes order book updates:

(1) Order Book Snapshot (periodic, every 100ms):
    {
        symbol: "RELIANCE",
        bids: [{price: 2498, qty: 500, orders: 12}, ...top 20],
        asks: [{price: 2502, qty: 300, orders: 8}, ...top 20],
        sequence: 123456
    }

(2) Incremental Updates (per trade):
    {
        symbol: "RELIANCE",
        type: "trade",
        price: 2498.00,
        qty: 50,
        side: "BUY",  // aggressor side
        sequence: 123457
    }

Sequence numbers ensure clients can detect missed messages and
request a fresh snapshot.
```

---

## 11. Order Types

### 11.1 Market Order

```
Execute immediately at the best available price.
No price specified by the trader.

Characteristics:
  - Guaranteed execution (if liquidity exists)
  - NOT guaranteed price (can get worse price in fast markets)
  - Fills against resting orders from best to worst price
  - "Takes" liquidity from the book

Example:
  BUY MARKET qty=500
  Ask book: 100@2498, 200@2499, 300@2500
  Fills: 100@2498 + 200@2499 + 200@2500 = 500 @ avg 2499.20

Risk: in illiquid stocks, can fill at very unfavorable prices.
Brokers often convert to limit orders at LTP +/- buffer.
```

### 11.2 Limit Order

```
Execute at specified price or better. If no match, rest in book.

Characteristics:
  - Guaranteed price (or better)
  - NOT guaranteed execution (may never fill if price doesn't reach)
  - "Makes" liquidity when resting in book
  - Price improvement possible (buy limit at 2500, get filled at 2498)

Example:
  BUY LIMIT @ 2500, qty=100
  Best ask = 2498 --> MATCH at 2498 (price improvement!)
  Best ask = 2502 --> NO match, order rests in bid book at 2500

  SELL LIMIT @ 2500, qty=100
  Best bid = 2502 --> MATCH at 2502 (price improvement!)
  Best bid = 2498 --> NO match, order rests in ask book at 2500
```

### 11.3 Stop-Loss Order

```
Becomes a MARKET order when the trigger price is hit.
Used to limit losses on existing positions.

Mechanism:
  - Order sits in a TRIGGER BOOK (not the main order book)
  - When LTP crosses trigger_price:
    - Stop-loss BUY: triggered when LTP >= trigger_price
    - Stop-loss SELL: triggered when LTP <= trigger_price
  - Once triggered, becomes a MARKET order and enters matching engine

Example (protecting a long position):
  Trader bought RELIANCE at 2500. Wants to limit loss to Rs 50/share.
  SELL STOP-LOSS, trigger=2450

  RELIANCE trades:
    2499... 2495... 2460... 2448 <-- LTP crosses 2450!

  (1) Stop-loss triggered at LTP=2448
  (2) Becomes: SELL MARKET
  (3) Matches against best bid (say 2447)
  (4) Loss limited to ~Rs 53/share (vs potentially Rs 100+ without stop)
```

### 11.4 Stop-Limit Order

```
Becomes a LIMIT order when trigger price is hit.
More control than stop-loss but risk of non-execution.

Example:
  SELL STOP-LIMIT, trigger=2450, price=2445

  When LTP <= 2450:
    (1) Triggered
    (2) Becomes: SELL LIMIT @ 2445
    (3) Will only execute at 2445 or HIGHER
    (4) If market crashes below 2445: order may NOT fill!
         (unlike stop-loss which fills at any price)

  Tradeoff: stop-loss guarantees execution, stop-limit guarantees price.
  In a fast-falling market, stop-limit can leave you unprotected.
```

### 11.5 Time-in-Force / Validity

```
IOC (Immediate or Cancel):
  - Fill whatever is available NOW, cancel the rest
  - If BUY 200 IOC and only 150 available: fill 150, cancel 50
  - Used by institutional traders who want instant execution

GTC (Good Till Cancel):
  - Order stays active until filled or manually cancelled
  - Survives end-of-day (unlike DAY orders)
  - Usually has a max duration (e.g., 90 days)

GTD (Good Till Date):
  - Order stays active until specified date or until filled
  - Expires at end of trading on the specified date

DAY (default):
  - Active for current trading day only
  - Cancelled automatically at market close (3:30 PM)
  - Most common for retail traders
```

---

## 12. Risk Management

### 12.1 Pre-Trade Risk Checks

```
Every order goes through these checks BEFORE entering the matching engine:

+-------------------------------------------------------------------+
|                    PRE-TRADE RISK CHECK PIPELINE                   |
+-------------------------------------------------------------------+
|                                                                    |
| (1) AUTHENTICATION & AUTHORIZATION                                 |
|     - Valid JWT token?                                             |
|     - User account ACTIVE?                                         |
|     - User allowed to trade this segment?                          |
|                                                                    |
| (2) SYMBOL VALIDATION                                              |
|     - Symbol exists and is ACTIVE?                                 |
|     - Not suspended / in auction?                                  |
|     - Trading allowed in this segment?                             |
|                                                                    |
| (3) ORDER VALIDATION                                               |
|     - Quantity > 0 and multiple of lot_size?                       |
|     - Price multiple of tick_size (0.05)?                          |
|     - Valid order type + required fields present?                  |
|                                                                    |
| (4) MARGIN / BALANCE CHECK                                         |
|     - Delivery: full amount (price * qty)                          |
|     - Intraday: margin % (e.g., 20% of price * qty)               |
|     - Available balance >= required margin?                        |
|     - Account not frozen?                                          |
|                                                                    |
| (5) POSITION LIMIT CHECK                                           |
|     - New total position <= max allowed?                           |
|     - Concentrated position check (% of free float)?              |
|                                                                    |
| (6) PRICE BAND CHECK                                               |
|     - Order price within circuit breaker band?                     |
|     - lower_circuit <= price <= upper_circuit?                     |
|     - Sanity: not too far from LTP (prevent fat-finger errors)?   |
|                                                                    |
| (7) ORDER RATE LIMIT                                               |
|     - User hasn't exceeded orders/sec limit?                      |
|     - System isn't in throttle mode?                               |
|                                                                    |
| ALL PASS --> APPROVED (block margin, send to matching engine)      |
| ANY FAIL --> REJECTED (return specific error, no margin blocked)   |
+-------------------------------------------------------------------+
```

### 12.2 Circuit Breakers

```
Circuit breakers halt trading when prices move too sharply:

STOCK-LEVEL CIRCUIT BREAKER:
  - Price band: +-20% of previous close (set by exchange)
  - Example: RELIANCE prev close = 2480
    Upper circuit = 2480 * 1.20 = 2976
    Lower circuit = 2480 * 0.80 = 1984
  - Orders outside this band are REJECTED
  - If LTP hits circuit: trading halts for cooldown period

INDEX-LEVEL CIRCUIT BREAKER (market-wide):
  +------------------+-------------+----------------------------+
  | Trigger           | Movement   | Action                      |
  +------------------+-------------+----------------------------+
  | Level 1          | +-10%      | Trading halt for 45 min     |
  | Level 2          | +-15%      | Trading halt for 1 hr 45 min|
  | Level 3          | +-20%      | Trading halted for the day  |
  +------------------+-------------+----------------------------+

  Measured against previous day's closing index value.

RAPID PRICE MOVEMENT DETECTOR:
  - If price moves +-10% within 5 minutes for a stock
  - Trigger: temporary trading pause (5 minutes)
  - Prevents flash crashes from algorithmic errors

Implementation:
  (1) Market Data Service tracks 5-minute price windows
  (2) On each trade, check: |LTP - price_5min_ago| / price_5min_ago > 10%?
  (3) If triggered: publish circuit_breaker event to Kafka
  (4) Matching Engine: stop accepting orders for this symbol
  (5) After cooldown: resume with pre-open auction
```

### 12.3 Margin Calculation

```
DELIVERY ORDERS (CNC - Cash and Carry):
  Full payment required upfront.

  BUY 100 RELIANCE @ 2500
  Required margin = 100 * 2500 = Rs 2,50,000 (100% of order value)

  After settlement (T+1): shares appear in demat, margin released
  No daily mark-to-market risk (fully paid for)


INTRADAY ORDERS (MIS - Margin Intraday Squared-off):
  Only a percentage (margin %) required. Leverage provided by broker.

  BUY 100 RELIANCE @ 2500 (INTRADAY, margin=20%)
  Required margin = 100 * 2500 * 0.20 = Rs 50,000

  Leverage = 5x (control Rs 2,50,000 worth of shares with Rs 50,000)

  RISK: if RELIANCE drops 5%, loss = 100 * 125 = Rs 12,500 (25% of margin)
  Must be squared off by end of day (auto-close at 3:15 PM)

  Margin percentages vary by stock (based on volatility):
  +------------------+--------+----------+
  | Stock Category   | Margin | Leverage |
  +------------------+--------+----------+
  | Large Cap (NIFTY)| 15-20% | 5-6.7x  |
  | Mid Cap          | 25-35% | 2.8-4x  |
  | Small Cap        | 50-75% | 1.3-2x  |
  | Illiquid         | 100%   | 1x      |
  +------------------+--------+----------+
```

### 12.4 Mark-to-Market (MTM)

```
Real-time P&L calculation based on Last Traded Price.

For INTRADAY positions:

  Position: LONG 100 RELIANCE, entry @ 2500
  Margin blocked: Rs 50,000 (20%)

  At 10:30 AM: LTP = 2480 (down Rs 20)
  MTM Loss = 100 * (2480 - 2500) = -Rs 2,000
  Effective margin = 50,000 - 2,000 = Rs 48,000

  At 11:00 AM: LTP = 2440 (down Rs 60)
  MTM Loss = 100 * (2440 - 2500) = -Rs 6,000
  Effective margin = 50,000 - 6,000 = Rs 44,000

  MARGIN CALL TRIGGER:
  If effective margin < 30% of required margin:
    Required = 50,000
    Threshold = 50,000 * 0.30 = Rs 15,000
    If effective margin < 15,000: MARGIN CALL

  At 2:00 PM: LTP = 2350 (down Rs 150)
  MTM Loss = 100 * (2350 - 2500) = -Rs 15,000
  Effective margin = 50,000 - 15,000 = Rs 35,000
  35,000 > 15,000? YES, still safe.

  But if LTP hits 2160:
  MTM Loss = 100 * (2160 - 2500) = -Rs 34,000
  Effective margin = 50,000 - 34,000 = Rs 16,000
  At 15,000 threshold --> MARGIN CALL triggered
  User must add funds or position auto-closed (square-off)

AUTO SQUARE-OFF:
  (1) At 3:15 PM, all intraday positions auto-closed
  (2) If margin call not met: immediate forced close
  (3) System places MARKET SELL order for the position
  (4) User bears the loss + square-off penalty
```

---

## 13. Market Data

### 13.1 Level 1 Data

```
Best Bid, Best Ask, LTP, Volume -- the essentials.

Level 1 Tick:
{
    "symbol": "RELIANCE",
    "ltp": 2499.00,          // Last Traded Price
    "ltq": 50,               // Last Traded Quantity
    "best_bid": 2498.00,     // Best (highest) bid price
    "best_bid_qty": 500,     // Quantity at best bid
    "best_ask": 2502.00,     // Best (lowest) ask price
    "best_ask_qty": 300,     // Quantity at best ask
    "volume": 1234500,       // Total traded volume today
    "open": 2480.00,
    "high": 2515.00,
    "low": 2475.00,
    "close": 2480.00,        // Previous day close
    "change": 19.00,
    "change_pct": 0.76,
    "timestamp": 1714123456789
}

Size: ~120 bytes per tick
Rate: up to 1000 ticks/sec per active symbol
Use case: price display, watchlist, portfolio valuation
```

### 13.2 Level 2 Data (Market Depth)

```
Full order book depth -- top 5 or 20 price levels on each side.

Level 2 (Top 5 Depth):
{
    "symbol": "RELIANCE",
    "bids": [
        {"price": 2498.00, "qty": 500,  "orders": 12},
        {"price": 2497.00, "qty": 800,  "orders": 18},
        {"price": 2496.00, "qty": 1200, "orders": 25},
        {"price": 2495.00, "qty": 600,  "orders": 15},
        {"price": 2494.00, "qty": 400,  "orders": 10}
    ],
    "asks": [
        {"price": 2502.00, "qty": 300,  "orders": 8},
        {"price": 2503.00, "qty": 450,  "orders": 11},
        {"price": 2504.00, "qty": 700,  "orders": 16},
        {"price": 2505.00, "qty": 350,  "orders": 9},
        {"price": 2506.00, "qty": 550,  "orders": 14}
    ],
    "total_buy_qty": 45000,
    "total_sell_qty": 38000
}

Size: ~500 bytes (5 levels) or ~2KB (20 levels)
Rate: every trade or 100ms snapshot
Use case: active traders analyzing supply/demand imbalance
```

### 13.3 OHLCV Candles

```
Aggregated price bars for charting (1min, 5min, 15min, 1hr, 1day).

OHLCV Candle:
{
    "symbol": "RELIANCE",
    "interval": "1m",            // 1m, 5m, 15m, 1h, 1d
    "open": 2498.00,             // first trade price in interval
    "high": 2510.00,             // max trade price in interval
    "low": 2495.00,              // min trade price in interval
    "close": 2508.00,            // last trade price in interval
    "volume": 12500,             // total shares traded in interval
    "turnover": 31200000.00,     // total value traded
    "timestamp": 1714123500000   // interval start time
}

Candle Aggregation:

  Within each interval (e.g., 1 minute from 09:15:00 to 09:15:59):

  (1) First trade: price=2498 --> Open=2498
  (2) Trade at 2510 --> High=max(2498,2510)=2510
  (3) Trade at 2495 --> Low=min(2498,2495)=2495
  (4) Trade at 2508 --> Close=2508 (last trade before minute ends)
  (5) Sum all quantities --> Volume=12500

Storage: TimescaleDB (time-series optimized PostgreSQL extension)
  - Hypertable partitioned by time
  - Efficient range queries: "give me 1-min candles for RELIANCE, last 30 days"
  - Automatic data compression for older candles
```

### 13.4 Ticker (Trade Stream)

```
Continuous stream of every executed trade.

Trade Tick:
{
    "symbol": "RELIANCE",
    "price": 2499.00,
    "quantity": 50,
    "side": "BUY",          // aggressor side (taker)
    "trade_id": "TRD-20260426-000042",
    "timestamp": 1714123456789
}

Rate: can be very high for liquid stocks (100+ trades/sec for NIFTY 50 stocks)
Use case: time & sales window, volume analysis, market microstructure

Fan-out optimization:
  - Not every user needs the full trade stream
  - Most users need only Level 1 (LTP updates)
  - Subscriptions are tiered: LTP -> Quote -> Full -> Trade Stream
  - Each tier adds data but also bandwidth cost
```

### 13.5 Market Data Architecture

```
+-------------------------------------------------------------------+
|                    MARKET DATA PIPELINE                             |
+-------------------------------------------------------------------+
|                                                                    |
|  +------------+                                                    |
|  | Matching   |  (1) Trade executed:                               |
|  | Engine     |      {symbol, price, qty, buyer, seller, time}     |
|  +-----+------+                                                    |
|        |                                                           |
|        v                                                           |
|  +------------+  (2) Kafka topic: trades.executed                  |
|  |   Kafka    |      Partitioned by symbol                         |
|  +-----+------+                                                    |
|        |                                                           |
|        v                                                           |
|  +------------------+                                              |
|  | Market Data      |  (3) Consumes trades, updates in-memory:     |
|  | Aggregator       |      - LTP, Volume, High, Low                |
|  | (per symbol)     |      - OHLCV candle (current minute)         |
|  +--+------+------+-+      - Best bid/ask (from order book topic)  |
|     |      |      |                                                |
|     v      v      v                                                |
|  +-----+ +-----+ +------------+                                   |
|  |Redis| |TSDB | | WebSocket  |                                   |
|  |Cache| |     | | Publisher  |                                   |
|  +-----+ +-----+ +-----+------+                                   |
|                         |                                          |
|                         v                                          |
|                   +------------+                                   |
|                   | WebSocket  |  (4) Fan-out to subscribers:      |
|                   | Hub        |      - 500K connections           |
|                   | (clustered)|      - Topic: symbol subscription |
|                   +-----+------+      - Batching: 100ms windows   |
|                         |                                          |
|                         v                                          |
|                   +------------+                                   |
|                   | Clients    |  (5) Receive JSON/binary ticks    |
|                   +------------+                                   |
+-------------------------------------------------------------------+
```

---

## 14. Concurrency

### 14.1 Matching Engine: Single-Threaded Per Symbol

```
WHY single-threaded?

The order book for a single symbol is a shared mutable data structure.
Two threads matching simultaneously against the same book can cause:

Problem 1: Double Fill
  Thread A: reads ask at 2498, qty=100
  Thread B: reads ask at 2498, qty=100  (same order!)
  Thread A: fills 100 from ask
  Thread B: fills 100 from ask  <-- DOUBLE FILL! 200 sold but only 100 available

Problem 2: Race Condition on Best Price
  Thread A: buy @ market, sees best ask = 2498
  Thread B: cancel ask at 2498 (the resting order is cancelled)
  Thread A: tries to match at 2498 --> order no longer exists --> crash or bad state

Solution: single-threaded event loop per symbol

  +------------------+
  | RELIANCE Engine  |
  |                  |
  | Single Thread:   |
  | (1) Read next    |
  |     event from   |
  |     Kafka        |
  | (2) Process:     |
  |     - New order  |
  |     - Cancel     |
  |     - Modify     |
  | (3) Match        |
  | (4) Emit trades  |
  | (5) Repeat       |
  +------------------+

  No locks needed! No synchronization! No race conditions!
  The single thread processes events sequentially.

Performance:
  A single-threaded matching engine can process 1M+ matches/sec.
  For 22K OPS across 5,000 symbols: average 4.4 OPS per symbol.
  Even the most active symbol (RELIANCE) might see 500 OPS -- trivially
  handled by a single thread.

Horizontal scale: partition symbols across CPU cores / machines.
  Machine 1: symbols A-G (Thread 1: A-C, Thread 2: D-G)
  Machine 2: symbols H-N
  Machine 3: symbols O-Z
```

### 14.2 Market Data Fan-Out

```
Challenge: 5M ticks/sec total, 500K connected clients, each watching ~20 symbols.

Naive approach: for each tick, iterate 500K clients, check subscription --> O(n) per tick
  5M * 500K = 2.5 trillion checks/sec --> impossible

Better: topic-based pub/sub

  +-------------------------------------------------------------------+
  | WebSocket Hub (clustered, 10 nodes)                               |
  |                                                                    |
  | Subscription Map (per node):                                      |
  |   RELIANCE --> [conn1, conn2, conn3, ...conn5000]                 |
  |   TCS      --> [conn4, conn5, conn6, ...conn3000]                 |
  |   INFY     --> [conn2, conn7, conn8, ...conn4000]                 |
  |                                                                    |
  | On tick for RELIANCE:                                              |
  |   (1) Look up RELIANCE subscriber list: O(1)                      |
  |   (2) Serialize tick message once                                  |
  |   (3) Write to all 5000 connections: O(subscribers)                |
  |   (4) Use writev/scatter-gather for batch socket writes            |
  +-------------------------------------------------------------------+

Batching optimization:
  - Don't send every tick individually (could be 1000/sec per symbol)
  - Batch ticks within 100ms window
  - Send consolidated update: latest LTP, high, low, volume
  - Reduces 1000 messages to 10 messages per second per symbol
  - Client sees smooth updates at 10Hz (more than enough for human eyes)

Binary encoding:
  - JSON is ~120 bytes per tick
  - Binary (protobuf/custom): ~40 bytes per tick
  - 3x reduction in bandwidth
  - Zerodha's Kite uses custom binary WebSocket protocol for this reason
```

### 14.3 Concurrent Order Placement

```
Problem: User places two orders simultaneously. Both read balance=300,000.
  Order A: BUY 100 RELIANCE @ 2500 = Rs 250,000
  Order B: BUY 100 TCS @ 3200 = Rs 320,000

  If both read balance as 300,000 and both pass margin check:
  Total blocked = 570,000 > 300,000 --> OVERDRAFT!

Solution: Pessimistic locking on account balance

  Order A:
    (1) BEGIN TRANSACTION
    (2) SELECT balance FROM accounts WHERE account_id=? FOR UPDATE
        -- acquires row lock, blocks Order B from reading
    (3) balance = 300,000, required = 250,000
    (4) 300,000 >= 250,000? YES
    (5) UPDATE accounts SET balance = 50,000, blocked_margin = 250,000
    (6) COMMIT
        -- releases lock

  Order B (was blocked, now runs):
    (1) BEGIN TRANSACTION
    (2) SELECT balance FROM accounts WHERE account_id=? FOR UPDATE
    (3) balance = 50,000, required = 320,000
    (4) 50,000 >= 320,000? NO --> REJECTED: INSUFFICIENT_MARGIN
    (5) ROLLBACK

  Result: Order A succeeds, Order B correctly rejected.
  No overdraft possible.

Alternative: Optimistic locking with version counter
  UPDATE accounts SET balance = balance - 250000, version = version + 1
  WHERE account_id = ? AND version = 42 AND balance >= 250000
  -- if affected rows = 0: concurrent modification, retry or reject
```

---

## 15. Scaling

### 15.1 Partition Order Books by Symbol

```
The matching engine scales horizontally by partitioning symbols:

  +-------------------------------------------------------------------+
  |                    SYMBOL PARTITIONING                              |
  +-------------------------------------------------------------------+
  |                                                                    |
  | Kafka Topic: orders.accepted                                       |
  | Partitioning key: symbol                                           |
  |                                                                    |
  | Partition 0: RELIANCE, HDFC, ICICI, ...    --> Engine Instance 0  |
  | Partition 1: TCS, INFY, WIPRO, ...         --> Engine Instance 1  |
  | Partition 2: SBIN, AXIS, KOTAK, ...        --> Engine Instance 2  |
  | ...                                                                |
  | Partition N: ZOMATO, PAYTM, NYKAA, ...     --> Engine Instance N  |
  |                                                                    |
  +-------------------------------------------------------------------+

Key properties:
  - Each symbol's orders go to exactly ONE partition (Kafka guarantees)
  - Each partition consumed by exactly ONE engine instance (consumer group)
  - This gives us single-threaded-per-symbol semantics automatically
  - Adding more partitions = adding more engine instances = linear scale

Partition assignment strategy:
  - Simple: hash(symbol) % num_partitions
  - Better: weighted by volume (RELIANCE gets its own partition,
    100 illiquid stocks share one partition)
  - Rebalancing: when adding/removing engines, Kafka rebalances partitions
```

### 15.2 Horizontal Market Data

```
Market Data Fan-Out Scaling:

  Problem: 500K WebSocket connections on one server is impossible.
  Solution: cluster of WebSocket Hub servers.

  +-------------------------------------------------------------------+
  |                    WEBSOCKET HUB CLUSTER                           |
  +-------------------------------------------------------------------+
  |                                                                    |
  | Load Balancer (sticky sessions by user_id):                        |
  |   User A --> Hub Node 1                                            |
  |   User B --> Hub Node 2                                            |
  |   User C --> Hub Node 3                                            |
  |                                                                    |
  | Hub Node 1 (50K connections):                                      |
  |   Subscribes to Kafka: market.ticks (all partitions)               |
  |   Local subscription map: which of MY 50K users watch RELIANCE?   |
  |   On RELIANCE tick: fan-out to ~1000 local subscribers             |
  |                                                                    |
  | Hub Node 2 (50K connections):                                      |
  |   Same Kafka consumer, local fan-out to its 50K users              |
  |                                                                    |
  | ...10 Hub Nodes total = 500K connections                           |
  +-------------------------------------------------------------------+

Each Hub Node:
  - Consumes ALL market data from Kafka (broadcast consumer group)
  - Maintains local subscription map
  - Fans out only to its own connected clients
  - Stateless: if node dies, clients reconnect to another node

Bandwidth per Hub Node:
  5M ticks/sec * 100 bytes = 500 MB/sec inbound from Kafka
  With batching (100ms): ~50 MB/sec
  Outbound to 50K clients: ~50K * 20 symbols * 100 bytes * 10/sec = 1 GB/sec
  With binary encoding: ~300 MB/sec (feasible with 10Gbps NIC)
```

### 15.3 Read Replicas for Portfolio

```
Portfolio reads are much more frequent than writes:
  Writes: ~30M trades/day (updates to positions)
  Reads: every portfolio page load, every P&L refresh (~100M reads/day)

Read/Write ratio: ~3:1 to 10:1

  +-------------------------------------------------------------------+
  |                    PORTFOLIO DB SCALING                             |
  +-------------------------------------------------------------------+
  |                                                                    |
  |  Writes (trades)                    Reads (portfolio, P&L)        |
  |       |                                    |                      |
  |       v                                    v                      |
  |  +----------+     replication     +------------------+            |
  |  | Primary  | ------------------> | Read Replica 1   |            |
  |  | (PG)     | ------------------> | Read Replica 2   |            |
  |  |          | ------------------> | Read Replica 3   |            |
  |  +----------+                     +------------------+            |
  |                                                                    |
  |  Replication lag: < 100ms (acceptable for portfolio display)      |
  |  P&L uses Redis for real-time LTP, not replica                    |
  +-------------------------------------------------------------------+

For real-time unrealized P&L:
  positions (from DB) + LTP (from Redis) = unrealized P&L
  No need for DB to have real-time LTP.
```

### 15.4 Matching Engine Failover

```
The matching engine is stateful (holds the order book in memory).
Failover strategy:

Primary-Backup with WAL:

  +----------+                     +----------+
  | Primary  |  --- WAL stream --> | Standby  |
  | Engine   |     (Kafka log)     | Engine   |
  +----------+                     +----------+

  (1) Every order/cancel/trade event is written to Kafka FIRST
  (2) Primary engine consumes from Kafka and updates in-memory book
  (3) Standby engine also consumes from Kafka (separate consumer group)
      and maintains a hot replica of the order book
  (4) If primary fails:
      - Standby detects via heartbeat (ZooKeeper / leader election)
      - Standby becomes primary
      - Resumes consuming from Kafka at last committed offset
      - Order book is already up-to-date (was replaying same stream)
  (5) Recovery time: < 5 seconds (mostly leader election)

  Key insight: Kafka IS the WAL. The order book is a materialized view
  of the Kafka event log. Any engine instance can reconstruct the book
  by replaying from the beginning (or a snapshot + recent events).
```

---

## 16. Database Choice

### 16.1 PostgreSQL -- Orders, Trades, Accounts, Positions

```
Why PostgreSQL?

  +-------------------------------------------------------------------+
  | Requirement              | PostgreSQL Capability                    |
  +-------------------------------------------------------------------+
  | ACID transactions        | Full ACID with MVCC                      |
  | Order persistence        | WAL for durability, fsync guarantees     |
  | Margin blocking          | SELECT FOR UPDATE (row-level locks)      |
  | Complex queries          | Full SQL, window functions, CTEs         |
  | Audit trail              | Trigger-based change tracking            |
  | Partitioning             | Native table partitioning (by date)      |
  | Replication              | Streaming replication for read replicas  |
  +-------------------------------------------------------------------+

Table partitioning strategy:
  Orders: partition by placed_at (monthly)
    orders_2026_01, orders_2026_02, ...
  Trades: partition by traded_at (monthly)
    trades_2026_01, trades_2026_02, ...

  Benefit: queries for "today's orders" only scan today's partition.
  Old partitions can be moved to cheaper storage.

Performance:
  50M orders/day = ~3,500 inserts/sec (average)
  Peak: ~15,000 inserts/sec
  PostgreSQL on NVMe SSD: handles 50K+ inserts/sec easily
  With connection pooling (PgBouncer): no problem
```

### 16.2 Redis -- Market Data Cache, Sessions, Rate Limiting

```
Why Redis?

  +-------------------------------------------------------------------+
  | Use Case                 | Redis Feature                           |
  +-------------------------------------------------------------------+
  | Market data cache        | Hash per symbol, O(1) get/set           |
  | LTP for P&L calc         | GET reliance:ltp --> "2499.00"          |
  | Session management       | JWT token blacklist, session data       |
  | Rate limiting            | INCR + EXPIRE (sliding window)         |
  | Watchlist hot data       | Sorted sets for user watchlists        |
  | Circuit breaker state    | Flags per symbol: is_halted=true       |
  +-------------------------------------------------------------------+

Market data structure in Redis:

  HSET market:RELIANCE ltp 2499 bid 2498 ask 2502 volume 1234500
                        open 2480 high 2515 low 2475 change 19
                        change_pct 0.76 timestamp 1714123456789

  HGET market:RELIANCE ltp --> "2499"  (O(1), sub-millisecond)

  TTL: no expiry during market hours, reset at end of day.

Rate limiting:

  Key: ratelimit:{user_id}:{second}
  INCR ratelimit:user123:1714123456
  EXPIRE ratelimit:user123:1714123456 2
  If value > 100: reject order (rate limited)
```

### 16.3 Kafka -- Event Streaming

```
Why Kafka?

  +-------------------------------------------------------------------+
  | Use Case                 | Kafka Feature                           |
  +-------------------------------------------------------------------+
  | Order flow               | orders.accepted topic, partitioned by   |
  |                          | symbol for matching engine               |
  | Trade events             | trades.executed topic, consumed by       |
  |                          | trade service, portfolio, market data    |
  | Order book updates       | orderbook.updates topic, consumed by    |
  |                          | market data service                      |
  | Audit log                | Immutable log, configurable retention   |
  | Matching engine WAL      | Replay-able event stream for recovery   |
  | Decoupling               | Producers don't wait for consumers      |
  +-------------------------------------------------------------------+

Topic design:

  orders.accepted     -- 100 partitions (by symbol hash)
  orders.cancel       -- 100 partitions (by symbol hash, same as above)
  trades.executed     -- 100 partitions (by symbol hash)
  orderbook.updates   -- 50 partitions  (by symbol hash)
  market.ticks        -- 50 partitions  (by symbol hash)
  notifications       -- 20 partitions  (by user hash)

Configuration:
  Replication factor: 3 (survive 2 broker failures)
  Min ISR: 2 (acks=all ensures write to 2+ brokers before ack)
  Retention: orders/trades: forever (regulatory)
             market data: 7 days (archived to S3)

Performance:
  Kafka cluster (5 brokers, NVMe):
  - 500K messages/sec write throughput
  - < 5ms p99 end-to-end latency (producer to consumer)
  - More than enough for 22K orders/sec + 5M ticks/sec
```

### 16.4 TimescaleDB -- OHLCV Candles, Tick History

```
Why TimescaleDB?

  TimescaleDB = PostgreSQL extension optimized for time-series data.

  +-------------------------------------------------------------------+
  | Use Case                 | TimescaleDB Feature                     |
  +-------------------------------------------------------------------+
  | OHLCV candles            | Hypertable auto-partitioned by time     |
  | Historical tick data     | Efficient range queries, compression    |
  | Charting APIs            | time_bucket() function for aggregation  |
  | Analytics                | Continuous aggregates (materialized)    |
  +-------------------------------------------------------------------+

Schema:

  CREATE TABLE candles (
      symbol      VARCHAR(20) NOT NULL,
      interval    VARCHAR(5)  NOT NULL,  -- '1m', '5m', '15m', '1h', '1d'
      timestamp   TIMESTAMPTZ NOT NULL,
      open        DECIMAL(12,2),
      high        DECIMAL(12,2),
      low         DECIMAL(12,2),
      close       DECIMAL(12,2),
      volume      BIGINT,
      turnover    DECIMAL(18,2)
  );

  SELECT create_hypertable('candles', 'timestamp');

Query: Get 1-minute candles for RELIANCE, last hour:

  SELECT * FROM candles
  WHERE symbol = 'RELIANCE' AND interval = '1m'
    AND timestamp >= NOW() - INTERVAL '1 hour'
  ORDER BY timestamp ASC;

Continuous aggregation (5-min candles from 1-min candles):

  CREATE MATERIALIZED VIEW candles_5m WITH (timescaledb.continuous) AS
  SELECT
      symbol,
      time_bucket('5 minutes', timestamp) AS timestamp,
      first(open, timestamp) AS open,
      max(high) AS high,
      min(low) AS low,
      last(close, timestamp) AS close,
      sum(volume) AS volume
  FROM candles
  WHERE interval = '1m'
  GROUP BY symbol, time_bucket('5 minutes', timestamp);

Storage:
  Raw 1-min candles: 5,000 * 375/day * 100 bytes = 187 MB/day
  With compression (after 7 days): ~20 MB/day
  1 year of data: ~7.5 GB (uncompressed), ~750 MB (compressed)
```

---

## 17. CAP Theorem

### 17.1 CP for Orders / Trades (Correctness Non-Negotiable)

```
Orders and trades require CONSISTENCY over AVAILABILITY.

Why?
  - A trade at the wrong price = financial loss = lawsuits
  - A double-fill = sold more shares than exist = settlement failure
  - A lost order = broken regulatory audit trail

  +-------------------------------------------------------------------+
  | CP Decisions                                                       |
  +-------------------------------------------------------------------+
  | Order persistence  | Write to PostgreSQL with fsync before ack.   |
  |                    | If DB is down, REJECT the order (don't lose  |
  |                    | it in memory hoping DB comes back).           |
  +-------------------------------------------------------------------+
  | Matching engine    | Single-threaded per symbol. No parallel       |
  |                    | matching that could violate price-time        |
  |                    | priority.                                     |
  +-------------------------------------------------------------------+
  | Margin blocking    | SELECT FOR UPDATE (pessimistic lock).         |
  |                    | Sacrifice throughput to prevent overdraft.    |
  +-------------------------------------------------------------------+
  | Kafka writes       | acks=all, min.insync.replicas=2.             |
  |                    | Wait for 2 brokers to confirm before ack.    |
  |                    | Slower but no data loss.                     |
  +-------------------------------------------------------------------+
  | Trade settlement   | Exactly-once: idempotency keys on trades.    |
  |                    | Replay from Kafka if consumer crashes.       |
  +-------------------------------------------------------------------+

  Tradeoff: during a PostgreSQL failover (30-60 sec), new orders are
  REJECTED. This is acceptable -- better to reject than to lose orders
  or create phantom trades.
```

### 17.2 AP for Market Data (Slightly Stale OK)

```
Market data can tolerate eventual consistency.

Why?
  - A slightly stale LTP (50ms old) is still useful for display
  - The user's watchlist showing 2499 instead of 2500 is NOT harmful
  - Market data is inherently "best effort" -- not a financial commitment

  +-------------------------------------------------------------------+
  | AP Decisions                                                       |
  +-------------------------------------------------------------------+
  | Redis market data  | No persistence (volatile cache). If Redis    |
  | cache              | node dies, other node may serve stale data   |
  |                    | briefly until repopulated.                   |
  +-------------------------------------------------------------------+
  | WebSocket fan-out  | Batching (100ms windows) means data is       |
  |                    | already up to 100ms stale by design.         |
  +-------------------------------------------------------------------+
  | Read replicas      | Portfolio reads from replica may be ~100ms   |
  | (portfolio)        | behind primary. Acceptable for display.      |
  +-------------------------------------------------------------------+
  | Level 2 depth      | Order book depth snapshots every 100ms.      |
  |                    | In between, depth may be slightly outdated.  |
  +-------------------------------------------------------------------+

  Tradeoff: if a WebSocket Hub node dies, clients reconnect to another
  node and miss ~1-2 seconds of ticks. The next tick they receive is
  current. No financial harm.

Key insight:
  "The ORDER must be correct. The DISPLAY can be stale."
  This separation lets us use CP for the critical path (order -> trade)
  and AP for the display path (market data -> UI).
```

---

## 18. Cloud Services

### AWS Mapping

```
+----------------------------+----------------------------+---------------------------+
| Component                  | AWS Service                | Why                       |
+----------------------------+----------------------------+---------------------------+
| API Gateway                | ALB + API Gateway          | JWT auth, rate limiting,  |
|                            |                            | WebSocket support         |
+----------------------------+----------------------------+---------------------------+
| Order Service              | ECS Fargate / EKS          | Stateless, auto-scaling   |
+----------------------------+----------------------------+---------------------------+
| Matching Engine            | EC2 bare metal             | Low latency, predictable  |
|                            | (c7g.metal)                | perf, no container        |
|                            |                            | overhead, NUMA-aware      |
+----------------------------+----------------------------+---------------------------+
| Risk Engine                | ECS Fargate                | Stateless, scales with    |
|                            |                            | order volume              |
+----------------------------+----------------------------+---------------------------+
| PostgreSQL                 | RDS PostgreSQL             | Managed, Multi-AZ,       |
|                            | (Multi-AZ)                 | automated backups         |
+----------------------------+----------------------------+---------------------------+
| Redis                      | ElastiCache Redis          | Managed, cluster mode,   |
|                            | (Cluster)                  | sub-ms latency            |
+----------------------------+----------------------------+---------------------------+
| Kafka                      | Amazon MSK                 | Managed Kafka, multi-AZ, |
|                            |                            | no ops overhead           |
+----------------------------+----------------------------+---------------------------+
| TimescaleDB                | EC2 + EBS (gp3)            | Self-managed (no managed  |
|                            |                            | TimescaleDB on AWS)       |
+----------------------------+----------------------------+---------------------------+
| WebSocket Hub              | ECS + ALB (WebSocket)      | Sticky sessions, auto-    |
|                            |                            | scaling                   |
+----------------------------+----------------------------+---------------------------+
| Notifications (Push)       | SNS + FCM/APNs             | Push notifications        |
+----------------------------+----------------------------+---------------------------+
| Notifications (SMS)        | SNS SMS / Twilio           | SMS for margin calls      |
+----------------------------+----------------------------+---------------------------+
| Notifications (Email)      | SES                        | Transactional email       |
+----------------------------+----------------------------+---------------------------+
| Object Storage             | S3                         | Trade archives, reports,  |
|                            |                            | tick data archival        |
+----------------------------+----------------------------+---------------------------+
| Monitoring                 | CloudWatch + Grafana       | Latency dashboards,       |
|                            |                            | order rate, fill rate     |
+----------------------------+----------------------------+---------------------------+
| DNS                        | Route 53                   | Latency-based routing     |
+----------------------------+----------------------------+---------------------------+
```

### GCP Mapping

```
+----------------------------+----------------------------+
| Component                  | GCP Service                |
+----------------------------+----------------------------+
| Matching Engine            | Compute Engine (C3 metal)  |
| Order/Risk Services        | Cloud Run / GKE            |
| PostgreSQL                 | Cloud SQL (HA)             |
| Redis                      | Memorystore Redis          |
| Kafka                      | Confluent on GCP / Pub/Sub |
| TimescaleDB                | Compute Engine + SSD       |
| WebSocket Hub              | GKE + Cloud Load Balancer  |
| Object Storage             | Cloud Storage              |
| Notifications              | Firebase Cloud Messaging   |
+----------------------------+----------------------------+
```

### Azure Mapping

```
+----------------------------+----------------------------+
| Component                  | Azure Service              |
+----------------------------+----------------------------+
| Matching Engine            | Azure VMs (Ev5 series)     |
| Order/Risk Services        | AKS / Container Apps       |
| PostgreSQL                 | Azure Database for PG      |
| Redis                      | Azure Cache for Redis      |
| Kafka                      | Azure Event Hubs (Kafka)   |
| TimescaleDB                | Azure VMs + Managed Disks  |
| WebSocket Hub              | Azure SignalR Service      |
| Object Storage             | Blob Storage               |
+----------------------------+----------------------------+
```

---

## 19. Tradeoffs Summary

| Decision                           | Chosen                          | Alternative                    | Why                                                   |
|------------------------------------|---------------------------------|--------------------------------|-------------------------------------------------------|
| Matching engine threading          | Single-threaded per symbol      | Multi-threaded with locks      | Correctness > throughput; no risk of double-fills      |
| Order persistence before matching  | Persist first, then match       | Match first, persist async     | Durability; if engine crashes, order is not lost       |
| Risk check timing                  | Synchronous pre-trade           | Async (check after matching)   | Must block invalid orders BEFORE they enter the book   |
| Market data delivery               | WebSocket with batching         | HTTP polling                   | Low latency; polling wastes bandwidth and adds delay   |
| Tick batching window               | 100ms                           | Per-tick (no batching)         | 10x bandwidth reduction; 100ms acceptable for display  |
| Order book data structure          | TreeMap + LinkedList             | Array-based flat book          | TreeMap: O(log n) insert/remove; dynamic price levels  |
| Margin locking                     | Pessimistic (SELECT FOR UPDATE) | Optimistic (version check)     | Financial correctness; can't afford retry-fail loops   |
| Market data encoding               | Binary (protobuf-like)          | JSON                           | 3x bandwidth savings at 500K connections               |
| Kafka partition key                | Symbol                          | User ID                        | Symbol ensures single-consumer per order book          |
| Portfolio P&L calculation          | On-demand (LTP from Redis)      | Pre-computed continuous         | Simpler; Redis lookup is sub-ms; avoids stale cache    |
| Order modify                       | Cancel + New (lose priority)    | In-place modify (keep priority)| Simpler matching engine; industry standard approach    |
| Settlement                         | T+1 batch                       | Real-time settlement           | Regulatory standard; netting reduces settlement volume |
| Database for orders                | PostgreSQL                      | MongoDB / DynamoDB             | ACID required; complex queries for reporting           |
| Database for candles               | TimescaleDB                     | InfluxDB / PostgreSQL          | Time-series optimized; continuous aggregates; SQL       |
| Matching engine failover           | Kafka replay (hot standby)      | Shared state (DB-backed book)  | DB-backed book adds latency; Kafka is already the WAL  |
| Circuit breaker check location     | Risk Engine (pre-trade)         | Matching Engine                | Reject early; don't waste matching engine cycles       |

---

## 20. Interview Talking Points

### Opening Statement (30 seconds)

> "A stock trading platform is fundamentally an order matching system with real-time market data distribution. The two hardest problems are: (1) the matching engine must be single-threaded per symbol to guarantee price-time priority without double-fills, and (2) market data must fan out to 500K concurrent WebSocket connections with sub-50ms latency. I'll design it as an event-driven system with Kafka connecting the order flow to the matching engine, and a clustered WebSocket hub for market data."

### Key "Aha Moments" to Hit

```
1. WHY SINGLE-THREADED MATCHING ENGINE
   "Two threads matching the same order book can double-fill a resting
   order. The fix is single-threaded per symbol. This seems like a
   bottleneck, but a single thread handles 1M+ matches/sec -- far more
   than any single stock needs. We scale horizontally by partitioning
   symbols across cores."

2. PRICE-TIME PRIORITY
   "The order book uses a TreeMap (sorted by price) of LinkedLists
   (FIFO within each price). For bids: descending TreeMap (best bid
   first). For asks: ascending TreeMap (best ask first). A buy limit
   order at $105 scans asks from the lowest: if ask <= $105, match.
   Within the same price level, the earliest order fills first."

3. TRADE EXECUTION PRICE
   "The execution price is ALWAYS the resting order's price, not the
   incoming order's price. A buy limit at $105 matching a resting ask
   at $100 executes at $100 -- this is price improvement for the buyer.
   The resting order 'made' the market, the incoming order 'took' it."

4. KAFKA AS WAL FOR MATCHING ENGINE
   "The matching engine's order book is an in-memory materialized view
   of the Kafka event log. If the engine crashes, the standby instance
   replays from Kafka to reconstruct the exact same order book state.
   Kafka IS the write-ahead log."

5. CP FOR ORDERS, AP FOR DISPLAY
   "Orders and trades need strong consistency -- a wrong trade price
   is a financial loss. Market data display can be eventually consistent
   -- a 100ms stale LTP is harmless. This split lets us use PostgreSQL
   with fsync for orders and Redis with async replication for market data."

6. MARKET DATA BATCHING
   "At 1000 ticks/sec per symbol, sending every tick to every subscriber
   would need 500GB/sec of bandwidth. Instead, we batch ticks in 100ms
   windows and send one consolidated update per window. This reduces
   bandwidth by 100x while still giving users 10 updates/sec -- more
   than enough for the human eye."

7. WHY ORDER IS PERSISTED BEFORE MATCHING
   "We persist the order to PostgreSQL BEFORE sending it to the matching
   engine via Kafka. If the matching engine crashes after receiving the
   order but before matching, we haven't lost the order -- it's in Kafka
   and will be replayed. If we matched first and then tried to persist,
   a crash after matching would mean a trade with no order record."

8. MARGIN BLOCKING WITH PESSIMISTIC LOCKS
   "Two concurrent orders from the same user could both read the same
   balance and both pass the margin check -- leading to overdraft. We
   use SELECT FOR UPDATE to serialize balance checks. Financial systems
   always prefer pessimistic locking -- the cost of a retry is nothing
   compared to the cost of an overdraft."
```

### Common Follow-Up Questions

```
Q: "How does the system handle a flash crash?"
A: "Circuit breakers at multiple levels -- stock-level (+-20% band),
   rapid movement (+-10% in 5 min triggers 5-min pause), and index-level
   (+-10/15/20% halts market). The risk engine checks circuit bands
   pre-trade and rejects out-of-band orders."

Q: "What happens if the matching engine goes down?"
A: "Hot standby engine replays from Kafka. Recovery < 5 seconds.
   During recovery, new orders queue in Kafka. No orders lost because
   orders are persisted to PostgreSQL before Kafka publish."

Q: "How do you handle 500K WebSocket connections?"
A: "Cluster of 10 WebSocket Hub nodes behind a load balancer with
   sticky sessions. Each node handles ~50K connections. Each node
   consumes ALL market data from Kafka and fans out only to its
   local subscribers. Topic-based subscription maps."

Q: "Why not use a database for the order book?"
A: "Latency. A database round-trip is 1-5ms. The matching engine
   needs to match in < 1ms. The order book must be in-memory.
   We use Kafka as the durability layer and reconstruct the book
   from the event log on failover."

Q: "How do you prevent a user from trading with insufficient funds?"
A: "Synchronous pre-trade risk check with pessimistic locking on
   the account balance. Margin is blocked atomically within a DB
   transaction BEFORE the order enters the matching engine."

Q: "What if Kafka goes down?"
A: "Kafka is a 5-broker cluster with replication factor 3 and
   min.insync.replicas=2. It can survive 2 broker failures.
   If Kafka is truly down (all 5 brokers), we stop accepting
   new orders (CP choice). We don't lose orders already in Kafka."

Q: "How do you handle market-open spikes (50x normal load)?"
A: "Pre-open session: orders collected but not matched during
   9:00-9:15. At 9:15, matching begins. Kafka absorbs the
   burst (it's disk-backed, handles spikes natively). Matching
   engine processes sequentially -- burst means higher queue
   depth but each order still processes in < 1ms."
```

### Time Management (45-min interview)

```
+--------+------------------------------------------------------+
| Time   | Topic                                                 |
+--------+------------------------------------------------------+
| 0-3    | Clarify scope: exchange vs broker, order types needed |
| 3-8    | Functional + non-functional requirements              |
| 8-12   | High-level architecture: draw the components           |
| 12-22  | DEEP DIVE: Matching Engine (order book, price-time    |
|        | priority, walk through a matching example)             |
| 22-28  | Order flow: placement -> risk -> matching -> trade     |
| 28-33  | Market data pipeline: how ticks reach the client       |
| 33-38  | Scaling: symbol partitioning, WebSocket fan-out        |
| 38-42  | Database choices, CAP decisions, tradeoffs             |
| 42-45  | Handle follow-ups, circuit breakers, edge cases        |
+--------+------------------------------------------------------+

The matching engine deep dive (12-22) is THE section interviewers
care about most. Spend 10 minutes here with concrete examples.
Draw the order book. Show a match. Explain price-time priority.
```

---

*End of High-Level Design: Stock Trading Platform*
