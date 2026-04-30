# Technologies & Infrastructure for the Stock Trading Platform (Zerodha/Upstox)

> Interview-ready reference for a Senior Java developer.
> A stock trading platform sits at the intersection of ultra-low latency, financial correctness,
> and massive concurrent users. Know the production stack, why each technology was chosen,
> and how our plain Java simulation maps to it.

---

## Table of Contents

| Technology | Why It's Here | Interview Relevance |
|------------|--------------|---------------------|
| Matching Engines (LMAX, Aeron) | Ultra-low latency order matching | HIGH -- core architecture |
| FIX Protocol | Exchange connectivity standard | HIGH -- industry standard |
| Market Data Infrastructure | Multicast UDP, kernel bypass, FPGA | HIGH -- latency optimization |
| PostgreSQL | Orders, trades, accounts (ACID) | HIGH -- relational for financial data |
| Redis | Market data cache, margin cache | HIGH -- caching layer |
| TimescaleDB | OHLCV candlestick data | MEDIUM -- time-series storage |
| Kafka | Event streaming for trades, market data | HIGH -- async communication |
| Order Book Data Structures | TreeMap (Java), std::map (C++) | HIGH -- THE core data structure |
| Latency Optimization | Co-location, kernel bypass, pre-allocation | HIGH -- differentiating knowledge |
| Our Java Simulation | In-memory, plain Java, interview walkthrough | HIGH -- code you can explain |

---

## 1. Matching Engines: LMAX Disruptor, Aeron, Custom Lock-Free Queues

### LMAX Disruptor -- The Gold Standard for Java

```
  +----------------------------------------------------------------------+
  |  LMAX DISRUPTOR -- HIGH-PERFORMANCE INTER-THREAD MESSAGING            |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  What it is:                                                         |
  |  - A ring buffer-based messaging framework for Java                  |
  |  - Designed by LMAX Exchange (London, UK) for their trading platform |
  |  - Achieves 100 million messages/sec on a single thread              |
  |  - Open source: com.lmax:disruptor                                   |
  |                                                                      |
  |  Why it's fast:                                                      |
  |  +------------------------------------------------------------------+|
  |  | Technique              | Benefit                                 ||
  |  +------------------------+-----------------------------------------+|
  |  | Ring buffer (array)    | Pre-allocated, cache-line friendly,     ||
  |  |                        | no GC pressure (objects reused)         ||
  |  +------------------------+-----------------------------------------+|
  |  | Mechanical sympathy    | Data structure fits CPU cache lines     ||
  |  |                        | (padding to avoid false sharing)        ||
  |  +------------------------+-----------------------------------------+|
  |  | Lock-free (CAS only)   | No mutex, no context switches           ||
  |  |                        | Producers use CAS to claim slots        ||
  |  +------------------------+-----------------------------------------+|
  |  | Single-writer principle | One consumer per sequence -- no         ||
  |  |                        | contention on read side                 ||
  |  +------------------------+-----------------------------------------+|
  |  | Batch processing       | Consumer processes all available events ||
  |  |                        | in one go (amortizes overhead)          ||
  |  +------------------------+-----------------------------------------+|
  |                                                                      |
  |  How it maps to trading:                                             |
  |                                                                      |
  |  +------------------+    +------------------+    +------------------+ |
  |  | API Gateway      |    | Disruptor Ring   |    | Matching Thread  | |
  |  | (producers)      |--->| Buffer           |--->| (single consumer)| |
  |  | Multiple threads |    | (pre-allocated   |    | per symbol       | |
  |  |                  |    |  Order objects)   |    |                  | |
  |  +------------------+    +------------------+    +------------------+ |
  |                                                                      |
  |  Pattern: MPSC (Multiple Producer, Single Consumer)                  |
  |  - Many API threads publish orders to the ring buffer                |
  |  - ONE matching thread consumes orders for a symbol                  |
  |  - Ring buffer size: power of 2 (e.g., 1024, 4096)                  |
  |  - If buffer full: producer waits (back-pressure) or drops (rare)   |
  +----------------------------------------------------------------------+
```

### Aeron -- Ultra-Low Latency Messaging

```
  +----------------------------------------------------------------------+
  |  AERON -- RELIABLE UDP MESSAGING                                      |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  What it is:                                                         |
  |  - High-performance messaging library by Real Logic (Martin Thompson) |
  |  - Used for inter-process and inter-machine communication            |
  |  - Reliable UDP with minimal overhead                                |
  |  - Latency: < 1 microsecond for IPC, < 10 microseconds for network  |
  |                                                                      |
  |  Where used in trading:                                              |
  |  +------------------------------------------------------------------+|
  |  | Use Case                | Why Aeron                              ||
  |  +-------------------------+----------------------------------------+|
  |  | Gateway -> Matching     | Sub-microsecond IPC between processes  ||
  |  | Engine (same machine)   | on the same box. Shared memory.        ||
  |  +-------------------------+----------------------------------------+|
  |  | Matching -> Settlement  | Reliable UDP between machines in same  ||
  |  | (cross-machine)         | datacenter. Lower latency than TCP.    ||
  |  +-------------------------+----------------------------------------+|
  |  | Market data fan-out     | Multicast-like delivery to many        ||
  |  | (one-to-many)           | consumers. Efficient for price feeds.  ||
  |  +-------------------------+----------------------------------------+|
  |                                                                      |
  |  Aeron vs Kafka:                                                     |
  |  +------------------+------------------+----------------------------+ |
  |  | Property         | Aeron            | Kafka                      | |
  |  +------------------+------------------+----------------------------+ |
  |  | Latency          | < 10 us          | 1-5 ms                     | |
  |  | Throughput        | 10M+ msg/sec     | 1M msg/sec                | |
  |  | Durability        | In-memory/file   | Disk-persisted             | |
  |  | Use case          | Hot path         | Async, durable events     | |
  |  | Protocol          | Reliable UDP     | TCP                       | |
  |  +------------------+------------------+----------------------------+ |
  |                                                                      |
  |  In our design: Aeron for the hot path (order -> match -> trade),    |
  |  Kafka for the warm path (trade -> settlement -> notification).      |
  +----------------------------------------------------------------------+
```

### Custom Lock-Free Queues

```
  +----------------------------------------------------------------------+
  |  LOCK-FREE QUEUES -- WHEN DISRUPTOR ISN'T ENOUGH                      |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Real exchanges (NSE, NASDAQ) build custom queues because:           |
  |  - Disruptor is Java -- GC pauses are unacceptable at microsecond   |
  |    latencies (a 10ms GC pause = 10,000 missed matching cycles)       |
  |  - They need C++ with custom memory allocators                       |
  |  - They need queue behavior tuned to their exact hardware            |
  |                                                                      |
  |  Common patterns:                                                    |
  |  +------------------------------------------------------------------+|
  |  | Pattern                    | Description                         ||
  |  +----------------------------+-------------------------------------+|
  |  | SPSC ring buffer           | Single producer, single consumer.   ||
  |  |                            | Zero synchronization needed.        ||
  |  |                            | Used for symbol-specific queues.    ||
  |  +----------------------------+-------------------------------------+|
  |  | MPSC ring buffer           | Multiple producers (API threads),   ||
  |  |                            | single consumer (matching thread).  ||
  |  |                            | CAS on producer side only.          ||
  |  +----------------------------+-------------------------------------+|
  |  | Pre-allocated object pool  | All Order/Trade objects allocated   ||
  |  |                            | at startup. No malloc in hot path.  ||
  |  |                            | Objects recycled via free list.     ||
  |  +----------------------------+-------------------------------------+|
  |                                                                      |
  |  In our Java simulation:                                             |
  |  - We use ConcurrentLinkedQueue (good enough for interview)          |
  |  - In production Java: LMAX Disruptor                                |
  |  - In production C++: custom SPSC/MPSC ring buffer                  |
  +----------------------------------------------------------------------+
```

---

## 2. FIX Protocol -- Financial Information eXchange

### FIX Protocol Overview

```
  +----------------------------------------------------------------------+
  |  FIX PROTOCOL -- THE LANGUAGE OF FINANCIAL MARKETS                    |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  What it is:                                                         |
  |  - Industry standard protocol for trading communication              |
  |  - Text-based (FIX 4.x) or binary (FIXT 1.1, SBE)                  |
  |  - Used by every broker, exchange, and trading platform globally     |
  |  - Version: FIX 4.2 (most common), FIX 4.4 (newer), FIXT 1.1       |
  |                                                                      |
  |  Message format (FIX 4.2):                                           |
  |  Tag=Value pairs separated by SOH (0x01) delimiter                   |
  |                                                                      |
  |  Example -- New Order Single (MsgType=D):                            |
  |  8=FIX.4.2|9=178|35=D|49=ZERODHA|56=NSE|34=12345|                   |
  |  52=20260426-09:15:30.123|11=ORD-001|55=RELIANCE|                    |
  |  54=1|38=100|40=2|44=2500.00|59=0|10=234|                           |
  |                                                                      |
  |  Decoded:                                                            |
  |  +--------+---------------------+-----------------------------------+|
  |  | Tag    | Field               | Value                             ||
  |  +--------+---------------------+-----------------------------------+|
  |  | 8      | BeginString         | FIX.4.2 (protocol version)        ||
  |  | 9      | BodyLength          | 178 bytes                         ||
  |  | 35     | MsgType             | D (New Order Single)              ||
  |  | 49     | SenderCompID        | ZERODHA (broker identifier)       ||
  |  | 56     | TargetCompID        | NSE (exchange identifier)         ||
  |  | 34     | MsgSeqNum           | 12345 (sequence for gap detect)   ||
  |  | 52     | SendingTime         | 2026-04-26T09:15:30.123           ||
  |  | 11     | ClOrdID             | ORD-001 (client order ID)         ||
  |  | 55     | Symbol              | RELIANCE                          ||
  |  | 54     | Side                | 1 (Buy)                           ||
  |  | 38     | OrderQty            | 100                               ||
  |  | 40     | OrdType             | 2 (Limit)                         ||
  |  | 44     | Price               | 2500.00                           ||
  |  | 59     | TimeInForce         | 0 (Day order)                     ||
  |  | 10     | CheckSum            | 234 (integrity check)             ||
  |  +--------+---------------------+-----------------------------------+|
  |                                                                      |
  |  Common message types:                                               |
  |  +--------+---------------------+-----------------------------------+|
  |  | MsgType| Name                | Direction                         ||
  |  +--------+---------------------+-----------------------------------+|
  |  | D      | New Order Single    | Broker -> Exchange                ||
  |  | F      | Order Cancel Request| Broker -> Exchange                ||
  |  | G      | Order Modify Request| Broker -> Exchange                ||
  |  | 8      | Execution Report    | Exchange -> Broker                ||
  |  | 9      | Order Cancel Reject | Exchange -> Broker                ||
  |  | 0      | Heartbeat           | Both ways (keep-alive)            ||
  |  | A      | Logon               | Broker -> Exchange (session start)||
  |  | 5      | Logout              | Both ways (session end)           ||
  |  +--------+---------------------+-----------------------------------+|
  +----------------------------------------------------------------------+
```

### Numbered Call Chain -- Order via FIX Protocol

```
1.  User clicks "Buy 100 RELIANCE @ 2500" on Kite app
2.  Kite API validates order, checks margin (internal)
3.  OMS creates FIX New Order Single message (MsgType=D)
4.  FIX engine assigns MsgSeqNum=12345 (for gap detection)
5.  Message sent over persistent TCP connection to NSE FIX gateway
6.  NSE FIX gateway validates message format and session
7.  NSE routes to RELIANCE matching engine
8.  Matching engine matches order -> Trade executed
9.  NSE creates Execution Report (MsgType=8) with:
      - ExecType=F (Fill)
      - OrdStatus=2 (Filled)
      - AvgPx=2490 (execution price)
      - CumQty=100 (total filled)
10. Execution Report sent back to Zerodha via FIX
11. Zerodha OMS updates order status, settles trade
12. User notified: "Buy 100 RELIANCE @ 2490 executed"
```

### FIX Session Management

```
  +----------------------------------------------------------------------+
  |  FIX SESSION LIFECYCLE                                                |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Pre-Market (before 9:00 AM):                                        |
  |  (1) Broker sends Logon (MsgType=A) to exchange                     |
  |  (2) Exchange validates credentials, responds with Logon             |
  |  (3) Both sides reset MsgSeqNum to 1 (or continue from last)        |
  |  (4) Heartbeat interval agreed (e.g., 30 seconds)                   |
  |                                                                      |
  |  Trading Hours (9:15 AM - 3:30 PM):                                  |
  |  (5) Orders flow as NewOrderSingle, CancelRequest, ModifyRequest    |
  |  (6) Execution Reports flow back for fills, rejects, cancellations  |
  |  (7) Heartbeats exchanged every 30 seconds (keep-alive)             |
  |  (8) If heartbeat missed: TestRequest sent, if no response:          |
  |      connection assumed dead, reconnect + gap fill                   |
  |                                                                      |
  |  Gap Detection:                                                      |
  |  (9) Every message has MsgSeqNum (incrementing)                     |
  |  (10) If receiver gets SeqNum 100 after 98 (missed 99):             |
  |       -> Send ResendRequest for messages 99-100                      |
  |       -> Exchange replays missed messages                            |
  |       -> NO message is ever lost (exactly-once delivery guarantee)   |
  |                                                                      |
  |  Post-Market (after 3:30 PM):                                        |
  |  (11) Broker sends Logout (MsgType=5)                               |
  |  (12) Exchange responds with Logout                                  |
  |  (13) TCP connection closed                                          |
  |  (14) Next day: Logon again, continue or reset sequence numbers     |
  +----------------------------------------------------------------------+
```

---

## 3. Market Data Infrastructure

### Multicast UDP for Market Data

```
  +----------------------------------------------------------------------+
  |  MARKET DATA DELIVERY -- MULTICAST UDP                                |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Why multicast UDP (not TCP):                                        |
  |  - Exchange sends one packet -> all subscribers receive it           |
  |  - No per-connection overhead (5000 brokers, one multicast)          |
  |  - Lower latency than TCP (no handshake, no ACK wait)               |
  |  - Acceptable loss: if one tick is lost, next tick replaces it       |
  |                                                                      |
  |  Architecture:                                                       |
  |                                                                      |
  |  NSE Matching Engine                                                 |
  |       |                                                              |
  |       | Trade executed: RELIANCE @ 2490                              |
  |       v                                                              |
  |  +-------------------+                                               |
  |  | Market Data       |  Creates multicast packet:                    |
  |  | Dissemination     |  {symbol: RELIANCE, ltp: 2490, bid: 2489,    |
  |  | System            |   ask: 2491, vol: 100, time: 09:15:30.123}   |
  |  +-------------------+                                               |
  |       |                                                              |
  |       | Multicast UDP to 239.1.1.1:5000                              |
  |       |                                                              |
  |  +----+----+----+----+----+                                          |
  |  |    |    |    |    |    |                                           |
  |  v    v    v    v    v    v                                           |
  |  Zerodha Upstox ICICI Angel 5Paisa ... (all brokers receive same     |
  |  Feed   Feed   Feed  Feed  Feed        packet simultaneously)        |
  |  Handler Handler Handler Handler                                     |
  |                                                                      |
  |  Packet format (simplified):                                         |
  |  +-------+--------+-------+-------+-------+-------+---------+       |
  |  | MsgLen| Symbol | LTP   | Bid   | Ask   | Volume| Timestamp|      |
  |  | 2B    | 10B    | 8B    | 8B    | 8B    | 8B    | 8B       |      |
  |  +-------+--------+-------+-------+-------+-------+---------+       |
  |  Total: ~52 bytes per tick. At 10,000 ticks/sec = ~520 KB/sec.      |
  +----------------------------------------------------------------------+
```

### Kernel Bypass and FPGA

```
  +----------------------------------------------------------------------+
  |  LATENCY OPTIMIZATION -- KERNEL BYPASS                                |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Normal network path:                                                |
  |  NIC -> kernel (interrupt, copy to kernel buffer, copy to user       |
  |  space) -> application                                               |
  |  Latency: ~10-50 microseconds                                       |
  |                                                                      |
  |  Kernel bypass path (Solarflare OpenOnload, DPDK):                   |
  |  NIC -> application (direct, zero-copy, no kernel involvement)       |
  |  Latency: ~1-5 microseconds                                         |
  |                                                                      |
  |  +------------------------------------------------------------------+|
  |  | Technology     | How It Works                 | Latency Savings  ||
  |  +----------------+------------------------------+------------------+|
  |  | Solarflare     | NIC driver in user space.    | 10x reduction    ||
  |  | OpenOnload     | Bypasses kernel TCP/UDP      | (50us -> 5us)    ||
  |  |                | stack entirely.              |                  ||
  |  +----------------+------------------------------+------------------+|
  |  | DPDK (Intel)   | User-space network driver.   | Similar to       ||
  |  |                | Poll mode instead of          | Solarflare.      ||
  |  |                | interrupts.                  | More flexible.   ||
  |  +----------------+------------------------------+------------------+|
  |  | FPGA           | Network processing in        | 100x reduction   ||
  |  |                | hardware. FIX parsing,       | (50us -> 0.5us)  ||
  |  |                | risk check, order routing    |                  ||
  |  |                | all in FPGA fabric.          |                  ||
  |  +----------------+------------------------------+------------------+|
  |                                                                      |
  |  Who uses what:                                                      |
  |  - NSE: Solarflare NICs + custom kernel bypass                      |
  |  - NASDAQ: FPGA for market data parsing                              |
  |  - Citadel, Virtu (HFT): FPGA + kernel bypass + co-location         |
  |  - Zerodha: Standard TCP (broker, not HFT)                          |
  |  - Our simulation: Standard Java sockets (interview, not production) |
  +----------------------------------------------------------------------+
```

---

## 4. Databases: PostgreSQL, Redis, TimescaleDB, Kafka

### Database Selection Matrix

```
  +----------------------------------------------------------------------+
  |  DATABASE CHOICES -- WHY EACH TECHNOLOGY                              |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Data Type         | Technology  | Why This Choice                   |
  |  ------------------+------------+------------------------------------+
  |  Orders            | PostgreSQL | ACID transactions. Order must be   |
  |                    |            | persisted before ACK to user.      |
  |                    |            | FK to accounts, stocks.            |
  |  ------------------+------------+------------------------------------+
  |  Trades            | PostgreSQL | ACID. Trade is the source of       |
  |                    |            | truth for settlement. Immutable    |
  |                    |            | after creation. Audit trail.       |
  |  ------------------+------------+------------------------------------+
  |  Positions         | PostgreSQL | Balance updates must be atomic     |
  |                    |            | with trade settlement. ACID.       |
  |  ------------------+------------+------------------------------------+
  |  Accounts/Margin   | PostgreSQL | Financial data -- ACID required.   |
  |                    |            | Balance = SUM(credits) -           |
  |                    |            | SUM(debits). Must balance.         |
  |  ------------------+------------+------------------------------------+
  |  Market Data (LTP) | Redis      | Sub-millisecond reads. 50 lakh    |
  |                    |            | users querying prices. AP cache.   |
  |                    |            | TTL: 500ms. Key: stock:{symbol}   |
  |  ------------------+------------+------------------------------------+
  |  OHLCV Candles     | TimescaleDB| Time-series optimized. Efficient   |
  |                    |            | range queries (e.g., 1-min candles |
  |                    |            | for last 6 months). Compression.   |
  |  ------------------+------------+------------------------------------+
  |  Trade Events      | Kafka      | Event streaming. Decouples         |
  |                    |            | matching from settlement,          |
  |                    |            | notification, analytics. Durable.  |
  |  ------------------+------------+------------------------------------+
  |  Audit Log         | Kafka +    | Immutable event log (Kafka) +     |
  |                    | PostgreSQL | queryable archive (PostgreSQL).    |
  |                    |            | Retention: 5 years (SEBI).        |
  |  ------------------+------------+------------------------------------+
  |  Stock Master      | PostgreSQL | Rarely changes. Loaded once at     |
  |                    | + Redis    | start, cached in Redis.            |
  |  ------------------+------------+------------------------------------+
  |                                                                      |
  +----------------------------------------------------------------------+
```

### PostgreSQL Schema for Trading

```sql
-- Orders table: THE source of truth for order state
CREATE TABLE orders (
    order_id        VARCHAR(36) PRIMARY KEY,  -- UUID, dedup key
    user_id         VARCHAR(36) NOT NULL REFERENCES accounts(user_id),
    symbol          VARCHAR(20) NOT NULL REFERENCES stocks(symbol),
    side            VARCHAR(4)  NOT NULL CHECK (side IN ('BUY', 'SELL')),
    order_type      VARCHAR(10) NOT NULL CHECK (order_type IN ('MARKET', 'LIMIT', 'STOP_LOSS')),
    quantity        INT         NOT NULL CHECK (quantity > 0),
    filled_quantity INT         NOT NULL DEFAULT 0 CHECK (filled_quantity >= 0),
    price           DECIMAL(12,2),  -- NULL for market orders
    trigger_price   DECIMAL(12,2),  -- NULL if not stop-loss
    status          VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    reject_reason   TEXT,
    created_at      TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP   NOT NULL DEFAULT NOW(),
    -- Indexes for common queries
    CONSTRAINT valid_status CHECK (status IN
        ('CREATED','PENDING_RISK','OPEN','PARTIALLY_FILLED',
         'FILLED','CANCELLED','REJECTED','EXPIRED'))
);

CREATE INDEX idx_orders_user_id ON orders(user_id);
CREATE INDEX idx_orders_symbol ON orders(symbol);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_user_symbol_status ON orders(user_id, symbol, status);

-- Trades table: immutable record of every execution
CREATE TABLE trades (
    trade_id        VARCHAR(36) PRIMARY KEY,
    symbol          VARCHAR(20) NOT NULL REFERENCES stocks(symbol),
    price           DECIMAL(12,2) NOT NULL,
    quantity        INT         NOT NULL CHECK (quantity > 0),
    buy_order_id    VARCHAR(36) NOT NULL REFERENCES orders(order_id),
    sell_order_id   VARCHAR(36) NOT NULL REFERENCES orders(order_id),
    buyer_user_id   VARCHAR(36) NOT NULL,
    seller_user_id  VARCHAR(36) NOT NULL,
    executed_at     TIMESTAMP   NOT NULL DEFAULT NOW()
    -- NO update/delete -- trades are IMMUTABLE
);

CREATE INDEX idx_trades_symbol ON trades(symbol);
CREATE INDEX idx_trades_executed_at ON trades(executed_at);
CREATE INDEX idx_trades_buy_order ON trades(buy_order_id);
CREATE INDEX idx_trades_sell_order ON trades(sell_order_id);

-- Positions table: current holdings per user per symbol
CREATE TABLE positions (
    user_id         VARCHAR(36) NOT NULL REFERENCES accounts(user_id),
    symbol          VARCHAR(20) NOT NULL REFERENCES stocks(symbol),
    quantity        INT         NOT NULL DEFAULT 0,
    average_cost    DECIMAL(12,2) NOT NULL DEFAULT 0,
    realized_pnl    DECIMAL(14,2) NOT NULL DEFAULT 0,
    updated_at      TIMESTAMP   NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, symbol)
);
```

### Redis Data Model for Market Data

```
  +----------------------------------------------------------------------+
  |  REDIS DATA MODEL                                                     |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Market Data (per symbol):                                           |
  |  Key: market:{symbol}                                                |
  |  Type: HASH                                                          |
  |  TTL: 500ms (auto-expire if feed stops)                              |
  |                                                                      |
  |  HSET market:RELIANCE                                                |
  |    ltp       2490.50                                                 |
  |    bid       2490.00                                                 |
  |    ask       2491.00                                                 |
  |    volume    12450000                                                |
  |    open      2480.00                                                 |
  |    high      2510.00                                                 |
  |    low       2475.00                                                 |
  |    prev_close 2470.00                                                |
  |    timestamp  1714107330123                                          |
  |                                                                      |
  |  HGETALL market:RELIANCE -> returns all fields in ~0.1ms             |
  |                                                                      |
  |  Portfolio Cache:                                                     |
  |  Key: portfolio:{userId}                                             |
  |  Type: HASH                                                          |
  |  TTL: none (invalidated on trade)                                    |
  |                                                                      |
  |  HSET portfolio:user-1                                               |
  |    RELIANCE  {"qty":600,"avgCost":2450,"currentValue":1494300}       |
  |    TCS       {"qty":300,"avgCost":3800,"currentValue":1167000}       |
  |                                                                      |
  |  Margin Cache:                                                       |
  |  NOT CACHED -- always read from PostgreSQL                           |
  |  Why: stale margin = user can over-trade = broker's financial risk   |
  |                                                                      |
  |  Idempotency Cache:                                                  |
  |  Key: order:dedup:{orderId}                                          |
  |  Type: STRING (value = order result JSON)                            |
  |  TTL: 24 hours                                                       |
  |  SET order:dedup:ORD-001 "{...result...}" NX EX 86400               |
  +----------------------------------------------------------------------+
```

### TimescaleDB for OHLCV Data

```
  +----------------------------------------------------------------------+
  |  TIMESCALEDB -- TIME-SERIES FOR CANDLESTICK DATA                      |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  What it is:                                                         |
  |  - PostgreSQL extension for time-series data                         |
  |  - Hypertables: auto-partitioned by time                             |
  |  - Continuous aggregates: pre-computed rollups                       |
  |  - Compression: 90%+ reduction for historical data                   |
  |                                                                      |
  |  Schema:                                                             |
  |                                                                      |
  |  CREATE TABLE ticks (                                                |
  |      time     TIMESTAMPTZ NOT NULL,                                  |
  |      symbol   VARCHAR(20) NOT NULL,                                  |
  |      price    DECIMAL(12,2) NOT NULL,                                |
  |      quantity INT NOT NULL                                           |
  |  );                                                                  |
  |  SELECT create_hypertable('ticks', 'time');                          |
  |                                                                      |
  |  -- 1-minute OHLCV candles (continuous aggregate)                    |
  |  CREATE MATERIALIZED VIEW ohlcv_1m                                   |
  |  WITH (timescaledb.continuous) AS                                    |
  |  SELECT                                                              |
  |      time_bucket('1 minute', time) AS bucket,                        |
  |      symbol,                                                         |
  |      first(price, time)  AS open,                                    |
  |      max(price)          AS high,                                    |
  |      min(price)          AS low,                                     |
  |      last(price, time)   AS close,                                   |
  |      sum(quantity)       AS volume                                   |
  |  FROM ticks                                                          |
  |  GROUP BY bucket, symbol;                                            |
  |                                                                      |
  |  Query: "Show me RELIANCE 1-minute candles for today"                |
  |  SELECT * FROM ohlcv_1m                                              |
  |  WHERE symbol = 'RELIANCE'                                           |
  |    AND bucket >= '2026-04-26 09:15:00'                               |
  |  ORDER BY bucket;                                                    |
  |                                                                      |
  |  Performance:                                                        |
  |  - 1 year of tick data for 5000 symbols: ~500 GB uncompressed       |
  |  - With compression: ~50 GB                                          |
  |  - 1-minute candle query: < 10ms                                     |
  +----------------------------------------------------------------------+
```

### Kafka for Event Streaming

```
  +----------------------------------------------------------------------+
  |  KAFKA TOPICS IN A TRADING PLATFORM                                   |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Topic                    | Partitions | Key        | Purpose         |
  |  -------------------------+------------+------------+----------------  |
  |  orders.placed            | 50         | symbol     | New orders      |
  |  orders.status-changed    | 50         | orderId    | State changes   |
  |  trades.executed          | 50         | symbol     | Trade events    |
  |  market-data.ticks        | 200        | symbol     | Price ticks     |
  |  settlements.pending      | 20         | tradeId    | Settlement queue|
  |  notifications.user       | 30         | userId     | User alerts     |
  |  audit.events             | 10         | timestamp  | Compliance log  |
  |                                                                      |
  |  Why Kafka (not RabbitMQ):                                           |
  |  +------------------------------------------------------------------+|
  |  | Property          | Kafka                  | RabbitMQ             ||
  |  +-------------------+------------------------+----------------------+|
  |  | Throughput         | 1M+ msg/sec           | 50K msg/sec          ||
  |  | Message retention  | Days/weeks (replay)   | Until consumed       ||
  |  | Consumer groups    | Multiple independent  | Competing consumers  ||
  |  | Ordering           | Per-partition (by key) | Per-queue            ||
  |  | Use case           | Event streaming       | Task queue           ||
  |  +-------------------+------------------------+----------------------+|
  |                                                                      |
  |  Key insight: partitioning by SYMBOL ensures all events for one      |
  |  symbol go to the same partition -> ordered processing per symbol.   |
  |  This mirrors the single-threaded matching engine model.             |
  +----------------------------------------------------------------------+
```

---

## 5. Order Book Data Structures -- Java vs C++

### Java TreeMap (Our Simulation)

```
  +----------------------------------------------------------------------+
  |  JAVA ORDER BOOK -- TreeMap<Double, PriceLevel>                       |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  // Bids: highest price first                                        |
  |  TreeMap<Double, PriceLevel> bids = new TreeMap<>(reverseOrder());   |
  |                                                                      |
  |  // Asks: lowest price first                                         |
  |  TreeMap<Double, PriceLevel> asks = new TreeMap<>();                 |
  |                                                                      |
  |  Complexity:                                                         |
  |  +------------------+-------+---------------------------------------+|
  |  | Operation        | Big-O | Details                               ||
  |  +------------------+-------+---------------------------------------+|
  |  | Insert order     | O(log P) | P = number of price levels        ||
  |  | Remove order     | O(log P) | Find level + O(N) scan in Deque   ||
  |  | Best bid/ask     | O(log P) | firstKey() on TreeMap              ||
  |  | Sweep N levels   | O(N + M) | N levels, M orders matched        ||
  |  | Price level count| O(1)  | TreeMap.size()                       ||
  |  +------------------+-------+---------------------------------------+|
  |                                                                      |
  |  Pros:                                                               |
  |  - Built into Java standard library                                  |
  |  - NavigableMap API (headMap, tailMap) for sweep matching             |
  |  - Red-Black tree guarantees O(log N) worst case                     |
  |                                                                      |
  |  Cons:                                                               |
  |  - GC pressure from Double autoboxing                                |
  |  - Not cache-friendly (pointer-based tree, scattered memory)         |
  |  - Object overhead (~16 bytes per TreeMap.Entry)                     |
  +----------------------------------------------------------------------+
```

### C++ std::map (Production Exchanges)

```
  +----------------------------------------------------------------------+
  |  C++ ORDER BOOK -- std::map<double, PriceLevel>                       |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  // Same red-black tree as Java TreeMap, but:                        |
  |  // - No GC (deterministic memory management)                        |
  |  // - Custom allocator for cache locality                            |
  |  // - No autoboxing (double is primitive)                            |
  |                                                                      |
  |  struct PriceLevel {                                                 |
  |      double price;                                                   |
  |      int total_qty;                                                  |
  |      // Intrusive linked list (no heap allocation per node)          |
  |      Order* head;                                                    |
  |      Order* tail;                                                    |
  |  };                                                                  |
  |                                                                      |
  |  class OrderBook {                                                   |
  |      // Custom allocator pre-allocates nodes in contiguous memory    |
  |      using Allocator = PoolAllocator<std::pair<double, PriceLevel>>; |
  |      std::map<double, PriceLevel, std::greater<>, Allocator> bids;   |
  |      std::map<double, PriceLevel, std::less<>, Allocator> asks;      |
  |  };                                                                  |
  |                                                                      |
  |  Optimizations over Java:                                            |
  |  +------------------------------------------------------------------+|
  |  | Optimization              | Benefit                              ||
  |  +---------------------------+--------------------------------------+|
  |  | Pool allocator            | No malloc in hot path. Pre-allocated ||
  |  |                           | memory, O(1) allocation.             ||
  |  +---------------------------+--------------------------------------+|
  |  | Intrusive linked list     | Orders embedded in pre-allocated     ||
  |  |                           | array. No heap alloc per order.      ||
  |  +---------------------------+--------------------------------------+|
  |  | No GC                     | Deterministic latency. No pause.     ||
  |  +---------------------------+--------------------------------------+|
  |  | Cache-line alignment      | Struct padded to 64 bytes.           ||
  |  |                           | Adjacent levels likely in same       ||
  |  |                           | cache line.                          ||
  |  +---------------------------+--------------------------------------+|
  +----------------------------------------------------------------------+
```

---

## 6. Latency Optimization -- Co-location to FPGA

### Latency Budget Breakdown

```
  +----------------------------------------------------------------------+
  |  END-TO-END LATENCY BUDGET                                            |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Component                    | Retail Broker  | HFT Firm            |
  |  -----------------------------+----------------+--------------------  |
  |  User network (ISP to DC)    | 10-50 ms       | N/A (co-located)    |
  |  API gateway processing      | 1-5 ms         | < 10 us             |
  |  Risk checks                 | 1-2 ms         | < 5 us (FPGA)       |
  |  Order serialization (FIX)   | 0.1-1 ms       | < 1 us (SBE binary) |
  |  Network to exchange         | 0.1-1 ms       | < 5 us (co-located) |
  |  Exchange matching           | 6 us (NSE)     | 6 us (NSE)          |
  |  Network back                | 0.1-1 ms       | < 5 us              |
  |  Trade processing            | 1-5 ms         | < 10 us             |
  |  User notification           | 5-20 ms        | N/A                 |
  |  -----------------------------+----------------+--------------------  |
  |  TOTAL                        | 20-80 ms       | < 50 us             |
  |                                                                      |
  |  Our Java simulation: ~1-5ms (in-memory, no network)                 |
  |  Good enough for interview. Know the production numbers.             |
  +----------------------------------------------------------------------+
```

### Co-location

```
  +----------------------------------------------------------------------+
  |  CO-LOCATION -- SERVERS AT THE EXCHANGE DATACENTER                    |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  What it is:                                                         |
  |  - Broker/HFT firm rents rack space inside the exchange datacenter   |
  |  - Direct network connection (cross-connect) to exchange servers     |
  |  - All cables same length (fairness requirement by exchange)         |
  |                                                                      |
  |  Why it matters:                                                     |
  |  +------------------------------------------------------------------+|
  |  | Without co-location:                                             ||
  |  | Broker DC (Mumbai) --[1ms]--> NSE DC (Mumbai BKC)                ||
  |  |                                                                  ||
  |  | With co-location:                                                ||
  |  | Broker server (NSE DC) --[5us]--> NSE matching engine            ||
  |  |                                                                  ||
  |  | Speed advantage: 200x faster                                     ||
  |  +------------------------------------------------------------------+|
  |                                                                      |
  |  NSE co-location (India):                                            |
  |  - Location: NSE DC at BKC, Mumbai                                  |
  |  - Cost: ~15-25 lakh/year per rack                                  |
  |  - Who uses it: Zerodha, major institutional traders, HFT firms     |
  |  - Equal cable length: SEBI mandates fairness (no one is closer)    |
  |                                                                      |
  |  INTERVIEW TIP:                                                      |
  |  "When the interviewer asks about latency optimization, mention      |
  |   co-location FIRST. It's the single biggest latency win. Everything |
  |   else (kernel bypass, FPGA) is secondary."                         |
  +----------------------------------------------------------------------+
```

---

## 7. Our Java Simulation vs Production

### Mapping Table

```
  +----------------------------------------------------------------------+
  |  OUR SIMULATION vs PRODUCTION REALITY                                 |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Component           | Our Simulation          | Production           |
  |  --------------------+-------------------------+---------------------  |
  |  Language             | Java 17                 | C++ / Go / Rust      |
  |  --------------------+-------------------------+---------------------  |
  |  Order Book           | TreeMap<Double,         | std::map with        |
  |                       | PriceLevel>             | pool allocator       |
  |  --------------------+-------------------------+---------------------  |
  |  Inter-thread Queue   | ConcurrentLinkedQueue   | LMAX Disruptor /     |
  |                       |                         | custom ring buffer   |
  |  --------------------+-------------------------+---------------------  |
  |  Order Storage        | ConcurrentHashMap       | PostgreSQL + WAL     |
  |  --------------------+-------------------------+---------------------  |
  |  Market Data Cache    | HashMap                 | Redis cluster        |
  |  --------------------+-------------------------+---------------------  |
  |  Event Streaming      | Direct method calls     | Kafka / Aeron        |
  |  --------------------+-------------------------+---------------------  |
  |  Exchange Protocol    | Method calls            | FIX 4.2 over TCP     |
  |  --------------------+-------------------------+---------------------  |
  |  Network I/O          | None (in-memory)        | Kernel bypass /      |
  |                       |                         | Solarflare NIC       |
  |  --------------------+-------------------------+---------------------  |
  |  Risk Checks          | Synchronous in          | FPGA (HFT) or       |
  |                       | placeOrder()            | parallel pre-trade   |
  |  --------------------+-------------------------+---------------------  |
  |  Matching Thread      | Caller's thread         | Dedicated thread     |
  |                       |                         | per symbol           |
  |  --------------------+-------------------------+---------------------  |
  |  Notification         | System.out.println      | SMS/email/push via   |
  |                       |                         | Kafka consumer       |
  |  --------------------+-------------------------+---------------------  |
  |                                                                      |
  |  INTERVIEW APPROACH:                                                  |
  |  "I've built the simulation in plain Java to demonstrate the          |
  |   patterns cleanly. In production, the order book would use C++      |
  |   with a pool allocator, inter-thread communication would use        |
  |   LMAX Disruptor, and exchange connectivity would be FIX 4.2         |
  |   over co-located servers with kernel bypass."                       |
  +----------------------------------------------------------------------+
```

---

## Interview Q&A -- Technology Questions

### Q: "Why not just use a HashMap for the order book?"

> "HashMap gives O(1) insert but O(N) to find the best bid or ask, because
> you'd have to scan all prices. TreeMap gives O(log N) for both insert AND
> best-price access. For sweep matching (market orders), TreeMap's headMap()
> gives ordered iteration in O(log N + K) where K is the number of levels
> consumed. HashMap has no concept of ordered iteration."

### Q: "Why PostgreSQL and not MongoDB for trades?"

> "Trades involve money. We need ACID transactions for settlement:
> debit buyer, credit seller, update positions, mark trade settled -- all
> atomically. MongoDB's document model doesn't support multi-document
> transactions as robustly. PostgreSQL also gives us foreign keys for
> referential integrity (every trade references valid orders, accounts)
> and SQL for complex reporting queries that regulators require."

### Q: "What's LMAX Disruptor and why does it matter?"

> "It's a ring buffer-based inter-thread messaging library that achieves
> 100 million messages per second on a single thread. It's fast because it's
> lock-free (CAS only), pre-allocated (no GC pressure), and mechanically
> sympathetic (cache-line padded to avoid false sharing). We'd use it for
> the order queue: multiple API threads publish orders to the ring buffer,
> one matching thread per symbol consumes them."

### Q: "How would you handle 10 crore orders per day?"

> "Partition by symbol. Each symbol has its own matching thread and order book.
> RELIANCE and TCS are matched in parallel on different threads. Use LMAX
> Disruptor for the order queue per symbol. Persist trades asynchronously
> to Kafka -> PostgreSQL. Market data via Redis with sub-second TTL.
> The bottleneck is network I/O, not matching -- co-locate at the exchange."

### Q: "What's the difference between Kafka and Aeron?"

> "Aeron is for the hot path -- sub-10-microsecond latency, in-memory,
> used for order routing and trade confirmation between processes. Kafka
> is for the warm path -- 1-5ms latency, disk-persisted, used for trade
> events, settlement, notifications, and audit logging. You wouldn't send
> orders through Kafka (too slow for matching) or trade events through
> Aeron (need durability for settlement)."
