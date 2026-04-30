# Interview Walkthrough -- Stock Trading Platform (Zerodha/Upstox)

> **Total time: ~35 minutes. The Matching Engine Deep Dive is 60% of this interview.**
> This problem tests order book data structures, matching algorithms, risk management, real-time data delivery, and low-latency system design. The hard part is explaining how the matching engine maintains a TreeMap-based order book with price-time priority, handles partial fills across multiple price levels, and processes orders in a single-threaded event loop for correctness -- with concrete numbers and real exchange architecture as reference.

---

## Phase 1: Clarify Requirements (2-3 min)

### Questions to Ask

- "What order types do we need to support? Market, limit, stop-loss? Do we need advanced types like iceberg or bracket orders? This determines matching engine complexity."
- "Is this a retail brokerage (Zerodha/Upstox -- routes to exchange) or an actual exchange (NSE/BSE -- runs the matching engine)? A broker validates and routes; an exchange matches. I'll design as an exchange to show the full matching engine."
- "Do we need real-time market data? WebSocket push for price ticks, order book depth, OHLCV candles? This drives the market data fan-out architecture."
- "What's the settlement model? T+1 (India, most markets now) or T+0 (real-time gross)? T+1 means end-of-day netting batch. This affects position management."
- "How many symbols and what's the order throughput? 5,000 symbols, 1M orders/day? This determines partitioning strategy -- one matching engine per symbol or shared."
- "What's the latency target? HFT (sub-microsecond) or retail (sub-10ms)? This changes whether we need FPGA/kernel bypass or if JVM with tuned GC is sufficient."

### Clarified Scope

```
In scope:   Order placement (market, limit, stop-loss), matching engine
            with price-time priority, partial fills, order book management,
            risk checks (margin, position limits, circuit breaker),
            real-time market data (WebSocket), P&L calculation,
            T+1 settlement, order cancellation/modification
Out of scope: HFT co-location (mention only), options/futures pricing
              (mention Greeks), algorithmic trading strategies (mention API),
              regulatory reporting details (mention audit log), multi-exchange
              routing (mention smart order routing)
```

### What This Signals

You understand this is a **matching + latency problem** where the hard part is building a correct, fast matching engine with proper order book data structures, not just a CRUD app with a database. You're probing for order types (matching complexity), latency targets (architectural constraints), and settlement model (batch vs real-time) because these fundamentally drive the design.

**Common follow-up:** "Why does single-threaded matter for a matching engine?"

**Answer:** "Correctness and determinism. A matching engine must process orders in strict arrival order -- if Order A arrives before Order B at the same price, A must fill first (time priority). With multiple threads, you need locks on the order book, which introduces: (1) lock contention -- 50-200ns per lock acquire, 500+ orders/sec means locks dominate latency; (2) non-deterministic execution -- thread scheduling varies, making replay impossible; (3) subtle bugs -- partial fill across two price levels must be atomic, which requires locking multiple tree nodes. Single-threaded: read order from queue, match against book, publish trade. Zero locks, deterministic, replayable. LMAX Exchange processes 6 million orders per second on a single thread. The bottleneck is never CPU -- it's network I/O, which is handled separately."

---

## Phase 2: High-Level Architecture (5-7 min)

### What to Say

> "I'll structure this as a pipeline with three distinct latency zones. **Zone 1 (hot path, <10ms)**: order ingestion through API Gateway to ECS Fargate for validation and risk checks, then publish to **Kafka partitioned by symbol**. **Zone 2 (ultra-hot path, <0.1ms)**: the matching engine consumes from Kafka, matches against the in-memory **order book** (TreeMap-based, single-threaded per symbol), and publishes trade events back to Kafka. **Zone 3 (async, <1s)**: post-trade consumers update **RDS Aurora** (order status), **DynamoDB** (positions, margins), **Redis** (market data cache), **Timestream** (candles), and push via **WebSocket** to clients. Risk checks run as a **chain of responsibility** -- MarginCheck, PositionLimit, CircuitBreaker -- all against **Redis** (cached from DynamoDB) for sub-millisecond validation. The system is **CP for orders/trades** (Kafka acks=all, Aurora synchronous replication) and **AP for market data** (Redis cache, stale ticks acceptable)."

### Draw This Diagram

```
              +------------------------------------+
              |     Clients (Mobile / Web / API)   |
              | Place orders, view portfolio,       |
              | stream market data via WebSocket    |
              +-----------------+------------------+
                                |
              1. POST /orders (BUY LIMIT 100 RELIANCE @ 2500)
              2. WebSocket: subscribe to RELIANCE price ticks
                                |
                                v
              +------------------------------------+
              |    API Gateway + WAF (edge)        |
              | JWT auth, rate limit (10 ord/s),   |
              | payload validation, DDoS protection|
              +---------+--------------+-----------+
                        |              |
           order flow   |              |   market data
           (REST)       |              |   (WebSocket)
                        v              v
              +-----------------+  +-------------------+
              | ECS Fargate     |  | WebSocket Gateway  |
              | (Order Service) |  | (Market Data Push) |
              |                 |  |                    |
              | 3. Validate     |  | 10. Push price     |
              |    order fields |  |     ticks to all   |
              | 4. Risk checks: |  |     subscribers    |
              |    Margin       |  |     (<50ms)        |
              |    Position     |  |                    |
              |    Circuit Brkr |  +--------+-----------+
              | 5. Persist to   |           ^
              |    RDS (PENDING)|           |
              | 6. Block margin |    trade events
              |    (DynamoDB)   |    (from Kafka)
              | 7. Publish to   |           |
              |    Kafka        |           |
              +--------+--------+  +--------+-----------+
                       |           | ECS Post-Trade      |
                       |           | Consumers            |
                       v           |                      |
              +-------------------+| 9a. Update order    |
              | Kafka (MSK)       ||     status (RDS)    |
              | Topic: orders.*   || 9b. Update positions|
              | Partitioned by    ||     (DynamoDB)      |
              | symbol            || 9c. Update market   |
              +--------+----------+|     data (Redis)    |
                       |           | 9d. Write candles   |
                       |           |     (Timestream)    |
                       v           | 9e. Audit log (S3)  |
              +-------------------++ 9f. Notify user     |
              | Matching Engine   ||     (WebSocket)     |
              | (EC2 bare metal)  |+---------------------+
              |                   |
              | 8. Single-threaded|
              |    per symbol:    |
              |    Read order     |
              |    Match vs book  |
              |    Generate trades|
              |    Publish trades |
              |    to Kafka       |
              |                   |
              | Order Book:       |
              |  ASKS: TreeMap ASC|     +----------------+
              |  BIDS: TreeMap DSC| --> | RDS Aurora     |
              |  In-memory only   |     | (Orders DB)    |
              +-------------------+     | order lifecycle|
                                        | trade history  |
                                        +----------------+
                       |
           +-----------+-----------+--------------+
           |           |           |              |
    +------v---+ +-----v------+ +-v-----------+ +v-----------+
    | DynamoDB | | ElastiCache| | Timestream  | | S3 (Audit) |
    |          | | Redis      | |             | |            |
    | positions| | real-time  | | OHLCV       | | immutable  |
    | margins  | | quotes     | | candles     | | trade log  |
    | holdings | | order book | | 1m,5m,1h,1D | | WORM       |
    |          | | snapshots  | |             | | 7yr retain |
    +----------+ +------------+ +-------------+ +------------+
```

### Components to Name

| Component | Role | CAP |
|-----------|------|-----|
| API Gateway + WAF | Entry point for order submission. JWT auth, rate limiting (10 orders/sec/user), DDoS protection. Routes to order service. | N/A (edge routing) |
| ECS Fargate (Order Service) | Validates order fields, runs risk check chain (all from Redis cache), persists order to RDS, blocks margin in DynamoDB, publishes to Kafka. Stateless, horizontally scalable. | N/A (compute layer) |
| Kafka (MSK) | Central nervous system. Partitioned by symbol for ordered delivery. orders.{symbol} topics feed matching engines. trades.{symbol} topics feed post-trade consumers. Event sourcing: the log IS the source of truth. | CP (acks=all, no data loss) |
| Matching Engine (EC2 bare metal) | Single-threaded per symbol. Consumes orders from Kafka, matches against in-memory order book, publishes trades. No database I/O on hot path. Rebuilds state from Kafka replay on restart. | CP (deterministic, replayable) |
| RDS Aurora PostgreSQL | Order lifecycle (PENDING -> FILLED), trade history, user accounts. ACID transactions. Global Database for RPO=0 DR. | CP (order records must be consistent) |
| DynamoDB | User positions, margins, holdings. High-throughput key-value access pattern. Updated asynchronously from trade events. Global tables for DR. | CP for margin (under-count = regulatory risk) |
| ElastiCache Redis | Real-time market data cache: quotes, order book snapshots, circuit breaker state. Sub-ms reads. Source for risk checks (margin balance cached here). | AP (stale cache acceptable, DynamoDB is truth) |
| Timestream | OHLCV candle data: 1-minute, 5-minute, 1-hour, 1-day candles. Time-series queries for charting. Memory store (recent) + magnetic store (historical). | AP (candle 1-2 seconds late is invisible) |
| WebSocket Gateway | Pushes real-time price ticks, order status updates, P&L changes to connected clients. Lambda fan-out for symbol-based subscriptions. Target: < 50ms from trade to client. | AP (dropped tick replaced by next tick) |

### What This Signals

You lead with the **three latency zones** -- showing you understand that order processing, matching, and post-trade have fundamentally different latency requirements. You name **Kafka partitioned by symbol** as the backbone, demonstrating you understand ordered delivery is critical for matching correctness. You mention **single-threaded matching** upfront, signaling you know the LMAX pattern.

**Common follow-up:** "Why Kafka and not a simple message queue like SQS?"

**Answer:** "Three reasons. (1) **Ordered delivery per partition**: Kafka guarantees order within a partition. With symbol-based partitioning, all RELIANCE orders arrive at the matching engine in exact submission order -- critical for price-time priority. SQS is best-effort FIFO. (2) **Replay**: if the matching engine crashes, it replays the Kafka log from the last committed offset to rebuild the order book. SQS deletes messages after consumption -- no replay possible. (3) **Fan-out**: one trade event in Kafka is consumed by 5+ downstream services (order update, position, market data, audit, notification) independently. SQS would need SNS fan-out + separate queues per consumer. Kafka's consumer group model handles this natively."

---

## Phase 3: Matching Engine Deep Dive (8-10 min)

**This is THE star section for trading platform interviews. Spend the most time here.**

### Part A: Order Book Data Structure

> "The order book is a pair of sorted maps. Bids use a TreeMap with descending price order -- highest bid first, because buyers want the best (highest) price matched first. Asks use a TreeMap with ascending price order -- lowest ask first, because sellers want the best (lowest) price matched first. At each price level, orders are in a FIFO queue -- first order placed at that price gets filled first. This is price-time priority."

```
ORDER BOOK DATA STRUCTURE (Numbered):

      1. BID SIDE (buy orders, TreeMap descending by price):
         |
         Rs 2,495.00: [BUY-006 (75 qty, 10:00:01), BUY-010 (50 qty, 10:00:03)]
         Rs 2,490.00: [BUY-007 (150 qty, 10:00:02)]
         Rs 2,485.00: [BUY-008 (200 qty, 09:59:58)]
         |
         Best bid = Rs 2,495.00 (TreeMap.firstKey() in DESC map = O(1))
         Total bid depth = 475 shares across 3 price levels
         |
         BUY-006 at Rs 2,495 was placed BEFORE BUY-010 at Rs 2,495.
         If a sell matches at Rs 2,495, BUY-006 fills first (time priority).

      2. ASK SIDE (sell orders, TreeMap ascending by price):
         |
         Rs 2,498.00: [SELL-001 (50 qty, 09:58:30), SELL-002 (30 qty, 09:59:15)]
         Rs 2,500.00: [SELL-003 (200 qty, 10:00:00)]
         Rs 2,505.00: [SELL-004 (100 qty, 09:57:22)]
         |
         Best ask = Rs 2,498.00 (TreeMap.firstKey() in ASC map = O(1))
         Total ask depth = 380 shares across 3 price levels

      3. SPREAD:
         Spread = best ask - best bid = 2498 - 2495 = Rs 3.00
         |
         Tight spread (Rs 0.05): high liquidity, active stock (RELIANCE)
         Wide spread (Rs 5.00): low liquidity, small-cap stock
         Spread = 0: crossed book, matching should occur (orders waiting to match)

      4. JAVA IMPLEMENTATION:
         |
         // Bids: highest price first (descending)
         TreeMap<BigDecimal, Queue<Order>> bids =
             new TreeMap<>(Comparator.reverseOrder());
         |
         // Asks: lowest price first (ascending)
         TreeMap<BigDecimal, Queue<Order>> asks =
             new TreeMap<>();
         |
         // Best bid: O(1)
         BigDecimal bestBid = bids.firstKey();    // highest price
         |
         // Best ask: O(1)
         BigDecimal bestAsk = asks.firstKey();     // lowest price
         |
         // Add order: O(log P) where P = number of price levels
         asks.computeIfAbsent(order.price, k -> new LinkedList<>()).add(order);
         |
         // Remove order (cancel): O(log P + Q) where Q = orders at that price
         // Must scan FIFO queue to find specific orderId

      5. WHY TREEMAP AND NOT HASHMAP:
         |
         HashMap: O(1) lookup by price, BUT:
           - Cannot find "best bid" without scanning all keys: O(P)
           - Cannot iterate prices in order: O(P log P) sort each time
           - Matching requires ordered traversal: UNUSABLE for matching
         |
         TreeMap: O(log P) insert/remove, AND:
           - Best bid/ask: O(1) via firstKey()/lastKey()
           - Ordered iteration: O(1) per step via iterator
           - Price levels typically P < 100: log(100) = 7 comparisons
           - Total matching: O(log P + k) where k = price levels touched
         |
         LinkedList for FIFO queue (not ArrayList):
           - Remove first element: O(1) (fill the front order)
           - Cancel arbitrary order: O(Q) scan (rare, acceptable)
           - Add to back: O(1) (new orders added at end)
```

### Part B: Matching Algorithm (BUY LIMIT)

> "Let me walk through a BUY LIMIT order matching step by step. This is the core algorithm that interviewers want to see."

```
BUY LIMIT MATCHING ALGORITHM (Numbered):

      Incoming: BUY LIMIT 100 shares @ Rs 2,500.00

      1. CHECK FOR MATCHABLE ASKS:
         Best ask = Rs 2,498.00
         Is best ask (<= 2498) <= buy limit (2500)? YES -> proceed to match.
         |
         If best ask > buy limit: NO MATCH. Order rests on bid side.
         (e.g., BUY LIMIT @ Rs 2,480 when best ask is Rs 2,498 -> rests)

      2. MATCH AT FIRST PRICE LEVEL (Rs 2,498.00):
         Queue at Rs 2,498: [SELL-001 (50 qty), SELL-002 (30 qty)]
         |
         Match SELL-001 first (FIFO):
           Fill qty = min(remaining=100, SELL-001.qty=50) = 50
           Trade: BUY-100 <-> SELL-001, 50 shares @ Rs 2,498.00
           BUY-100 remaining: 100 - 50 = 50
           SELL-001: FULLY FILLED -> remove from queue
         |
         Match SELL-002 next (still at Rs 2,498):
           Fill qty = min(remaining=50, SELL-002.qty=30) = 30
           Trade: BUY-100 <-> SELL-002, 30 shares @ Rs 2,498.00
           BUY-100 remaining: 50 - 30 = 20
           SELL-002: FULLY FILLED -> remove from queue
         |
         Price level Rs 2,498 exhausted (queue empty).
         Remove price level from TreeMap.

      3. MATCH AT NEXT PRICE LEVEL (Rs 2,500.00):
         Queue at Rs 2,500: [SELL-003 (200 qty)]
         |
         Is Rs 2,500 <= buy limit Rs 2,500? YES -> continue matching.
         |
         Match SELL-003:
           Fill qty = min(remaining=20, SELL-003.qty=200) = 20
           Trade: BUY-100 <-> SELL-003, 20 shares @ Rs 2,500.00
           BUY-100 remaining: 20 - 20 = 0 -> FULLY FILLED
           SELL-003: PARTIAL FILL -> qty reduced 200 -> 180, stays in queue

      4. MATCHING COMPLETE:
         BUY-100 status: FILLED (remaining = 0)
         |
         Trades generated:
           T001: 50 @ 2498.00 = Rs 124,900
           T002: 30 @ 2498.00 = Rs  74,940
           T003: 20 @ 2500.00 = Rs  50,000
           ---------------------------------
           Total: 100 shares, Rs 249,840
           Average price: Rs 2,498.40
         |
         Buyer got BETTER than limit price!
         Limit was Rs 2,500, avg fill was Rs 2,498.40.
         Price improvement: Rs 1.60 per share * 100 = Rs 160 saved.

      5. WHAT IF REMAINING QUANTITY?
         If buy limit was Rs 2,498 (not Rs 2,500):
           Match at Rs 2,498: fill 80 shares (SELL-001 + SELL-002)
           Next ask Rs 2,500 > limit Rs 2,498: STOP matching.
           Remaining 20 shares -> REST on bid side at Rs 2,498.
           Order status: PARTIAL (80 filled, 20 resting)
         |
         Updated order book:
           BID Rs 2,498.00: [BUY-100 (20 qty)]   <- new best bid!
           BID Rs 2,495.00: [BUY-006 (75 qty)]
           Spread narrowed: 2500 - 2498 = Rs 2.00 (was Rs 3.00)

      6. PSEUDOCODE:
         |
         List<Trade> matchBuyLimit(Order buyOrder):
             trades = []
             remaining = buyOrder.quantity
             |
             while remaining > 0:
                 bestAskEntry = asks.firstEntry()    // O(1)
                 if bestAskEntry == null: break       // no sellers
                 if bestAskEntry.key > buyOrder.price: break  // price too high
                 |
                 queue = bestAskEntry.value
                 while remaining > 0 AND !queue.isEmpty():
                     sellOrder = queue.peek()
                     fillQty = min(remaining, sellOrder.remainingQty)
                     fillPrice = bestAskEntry.key     // execute at ASK price
                     |
                     trades.add(Trade(buyOrder, sellOrder, fillPrice, fillQty))
                     remaining -= fillQty
                     sellOrder.remainingQty -= fillQty
                     |
                     if sellOrder.remainingQty == 0:
                         queue.poll()   // remove fully filled order
                 |
                 if queue.isEmpty():
                     asks.remove(bestAskEntry.key)   // remove empty price level
             |
             if remaining > 0:
                 // Resting order: add to bid side
                 bids.computeIfAbsent(buyOrder.price, k -> new LinkedList<>())
                     .add(buyOrder.withRemainingQty(remaining))
             |
             return trades
```

### Part C: Market Order Matching

> "Market orders are simpler but more dangerous. No price limit -- just sweep whatever is available."

```
MARKET ORDER MATCHING (Numbered):

      Incoming: MARKET BUY 250 shares RELIANCE

      1. NO PRICE LIMIT:
         Market order has no limit price.
         Sweep asks from lowest price, fill at each level.
         |
         Unlike limit orders, market orders NEVER rest on the book.
         They either fill completely or fail (if order book is empty).

      2. MATCHING (sweep):
         Ask Rs 2,500.00: [SELL-003 (180 qty)]
           Fill 180 @ Rs 2,500.00. Remaining: 250 - 180 = 70.
           SELL-003 exhausted. Remove price level.
         |
         Ask Rs 2,505.00: [SELL-004 (100 qty)]
           Fill 70 @ Rs 2,505.00. Remaining: 70 - 70 = 0. DONE.
           SELL-004 partial: 100 -> 30 remaining.

      3. SLIPPAGE:
         First fill: Rs 2,500.00 (best ask at time of order)
         Last fill:  Rs 2,505.00 (next price level)
         |
         Average price: (180*2500 + 70*2505) / 250 = Rs 2,501.40
         Slippage: Rs 1.40 from best ask (0.056%)
         |
         In thin markets, slippage can be much worse:
           Market buy 10,000 shares of illiquid stock:
           Sweeps from Rs 100 to Rs 115. Slippage: 15%.
           This is why exchanges offer "market-to-limit" protection.

      4. MARKET ORDER PROTECTIONS:
         Exchange protections against extreme slippage:
         |
         a. Market-to-limit: convert to limit at best price + 5%
            Market buy when best ask = Rs 100 -> limit at Rs 105.
            Fills up to Rs 105, remaining rests as limit order.
         |
         b. Fill-or-kill: fill 100% immediately or cancel entire order.
            Prevents partial fill across wide price gap.
         |
         c. Circuit breaker: if market order would move price > 10%,
            halt matching. Alert surveillance team.

      5. MARKET vs LIMIT COMPARISON:
         +---------------------+-----------------------+------------------------+
         | Aspect              | Market Order           | Limit Order            |
         +---------------------+-----------------------+------------------------+
         | Price guarantee     | NONE (slippage risk)   | YES (fills AT or BETTER|
         |                     |                        | than limit price)      |
         | Fill guarantee      | YES (fills immediately | NO (may never fill if  |
         |                     | if book has liquidity) | limit too aggressive)  |
         | Rests on book       | NEVER (fills or fails) | YES (if no match)      |
         | Best for            | "Get me in NOW"        | "Get me in at my price"|
         | Risk                | Slippage in thin book  | Order may never fill   |
         +---------------------+-----------------------+------------------------+
```

**Common follow-up:** "How do you handle a stop-loss order?"

**Answer:** "A stop-loss order has two phases. Phase 1: it sits dormant, watching the market price. It's NOT in the order book -- it's in a separate trigger list monitored by the matching engine. Phase 2: when the last-traded price hits the trigger price (e.g., LTP drops to Rs 2,400 for a stop-loss sell at Rs 2,400), the order 'activates' and becomes either a market order (stop-loss market: sell immediately at whatever price) or a limit order (stop-loss limit: sell at Rs 2,390 limit). The activated order then enters the normal matching flow. Stop-loss orders are critical for risk management but add complexity: the matching engine must check the trigger list after every trade that changes the last-traded price."

---

## Phase 4: Risk Management & Settlement (5-7 min)

### Part A: Risk Check Chain (Chain of Responsibility)

> "Before any order reaches the matching engine, it passes through a chain of risk checks. Each check can reject the order immediately. The chain short-circuits on first failure -- no point checking position limits if the user doesn't have enough margin."

```
RISK CHECK CHAIN OF RESPONSIBILITY (Numbered):

      Incoming: BUY LIMIT 100 RELIANCE @ Rs 2,500

      1. MARGIN CHECK (first in chain):
         |
         Query Redis: user_001 -> available_margin = Rs 500,000
         (Redis is cache; DynamoDB is source of truth. Cache refresh every trade.)
         |
         Calculate required margin:
           Order type: LIMIT (intraday assumed)
           Order value: 100 * 2,500 = Rs 250,000
           Margin rate: 20% (SEBI mandate for intraday equity)
           Required margin: Rs 50,000
         |
         Check: available_margin (500,000) >= required (50,000)?
         YES -> PASS. Forward to next check.
         |
         If NO -> REJECT immediately. Response:
           { status: "REJECTED", reason: "INSUFFICIENT_MARGIN",
             available: 500000, required: 250000 }
           Chain short-circuits. No further checks.

      2. POSITION LIMIT CHECK (second in chain):
         |
         Query Redis: user_001 -> RELIANCE_position = 200 shares
         |
         New position after order: 200 + 100 = 300 shares
         Exchange limit: 10,000 shares per user per symbol
         Broker limit: 5,000 shares (more conservative)
         |
         Check: 300 <= 5,000? YES -> PASS.
         |
         If NO -> REJECT: "POSITION_LIMIT_EXCEEDED"
         Chain short-circuits.

      3. CIRCUIT BREAKER CHECK (third in chain):
         |
         Query Redis: RELIANCE -> {
           circuit_status: "ACTIVE",
           upper_circuit: 2750.00,
           lower_circuit: 2250.00,
           last_traded_price: 2498.00
         }
         |
         Checks:
           a. Symbol not halted? ACTIVE -> PASS.
           b. Order price within circuit range?
              2250 <= 2500 <= 2750 -> PASS.
         |
         If symbol HALTED -> REJECT: "SYMBOL_HALTED_CIRCUIT_BREAKER"
         If price outside range -> REJECT: "PRICE_OUTSIDE_CIRCUIT_RANGE"

      4. CHAIN COMPLETE:
         All 3 checks passed. Total time: 2.1ms
         (3 Redis lookups * ~0.5ms each + validation logic)
         |
         Order proceeds to persistence + Kafka publish.

      5. CHAIN OF RESPONSIBILITY PATTERN:
         |
         interface RiskCheck {
             RiskResult check(Order order, UserContext ctx);
         }
         |
         class RiskCheckChain {
             List<RiskCheck> checks; // ordered: Margin, Position, Circuit
             |
             RiskResult validate(Order order, UserContext ctx) {
                 for (RiskCheck check : checks) {
                     RiskResult result = check.check(order, ctx);
                     if (result.isRejected()) return result; // short-circuit
                 }
                 return RiskResult.APPROVED;
             }
         }
         |
         Benefits:
           - Add/remove checks without changing order service
           - Reorder checks (put cheapest first for early rejection)
           - A/B test new risk checks by adding to chain conditionally
           - Each check is independently testable
```

### Part B: Circuit Breaker Deep Dive

> "Circuit breakers prevent flash crashes. When a stock's price moves too far too fast, trading halts."

```
CIRCUIT BREAKER MECHANISM (Numbered):

      1. HOW CIRCUITS WORK (Indian markets):
         Each stock has daily circuit limits set by the exchange:
           Rs 2,500 stock -> Upper circuit: Rs 2,750 (+10%)
                          -> Lower circuit: Rs 2,250 (-10%)
         |
         If last traded price hits Rs 2,750:
           -> UPPER CIRCUIT TRIGGERED
           -> All pending buy orders above Rs 2,750 cancelled
           -> No new buy orders accepted
           -> Sell orders still accepted (to relieve buying pressure)
         |
         Index-level circuit breakers (market-wide):
           Nifty drops 10% -> 45-minute trading halt
           Nifty drops 15% -> 1h45min halt
           Nifty drops 20% -> market closed for the day

      2. IMPLEMENTATION IN MATCHING ENGINE:
         After EVERY trade:
           newLTP = trade.price
           if newLTP >= upperCircuit:
               symbol.status = HALTED_UPPER
               cancelAllBuyOrders()
               publishEvent(CIRCUIT_BREAKER_TRIGGERED, symbol, UPPER)
           if newLTP <= lowerCircuit:
               symbol.status = HALTED_LOWER
               cancelAllSellOrders()
               publishEvent(CIRCUIT_BREAKER_TRIGGERED, symbol, LOWER)
         |
         Redis updated: RELIANCE -> circuit_status = HALTED_UPPER
         Order service reads this before accepting new orders.

      3. COOL-DOWN AND RESUME:
         After 15-minute cool-down:
           Exchange may widen circuit limits (+/- 15%)
           OR resume trading at same limits
           Symbol status: HALTED -> AUCTION -> ACTIVE
         |
         Auction mode: collect orders for 5 minutes,
           then match at equilibrium price.
           Prevents immediate re-trigger.
```

### Part C: T+1 Settlement

> "Settlement is the end-of-day batch that actually transfers shares and money. Trades during the day are promises; settlement makes them real."

```
T+1 SETTLEMENT PROCESS (Numbered):

      1. TRADE DAY (T):
         All trades execute. Matching engine generates trade records.
         At 3:30 PM (market close):
           Total trades: 800,000
           Total symbols: 5,000
           Total value: Rs 50 billion
         |
         Each trade is a PROMISE:
           "Buyer will pay Rs X. Seller will deliver Y shares."
           No actual transfer happens yet.

      2. NETTING (T, after market close):
         AWS Batch job runs at 3:30 PM:
         |
         For each user, net all trades per symbol:
           User U001:
             Bought: 100 RELIANCE @ 2498, 50 RELIANCE @ 2510
             Sold:   80 RELIANCE @ 2520
             Net: BUY 70 RELIANCE, net cost = Rs 174,720
         |
         Netting reduces settlement volume by 80-90%:
           800K trades -> ~200K net obligations
           Massively reduces clearing corporation's work.

      3. CLEARING (T+1 morning):
         Clearing corporation (NSCCL for NSE):
           Collects net funds from net buyers
           Collects net shares from net sellers
           Verifies all parties have sufficient shares/funds
         |
         If a party defaults (can't deliver shares):
           Clearing corporation steps in (central counterparty guarantee)
           Buys shares from market to deliver to buyer
           Debits penalty from defaulting seller's margin

      4. SETTLEMENT (T+1 afternoon):
         Shares transferred: seller's demat -> buyer's demat
         Funds transferred: buyer's bank -> seller's bank
         |
         Our system updates:
           DynamoDB: user positions (delivery quantity added)
           RDS: trade status -> SETTLED
           Margin: delivery margin released

      5. RECONCILIATION:
         Our trade records vs exchange's settlement file.
         Mismatch = potential financial loss or regulatory issue.
         |
         Automated reconciliation (Lambda, daily):
           Match: our tradeId + qty + price vs exchange confirmation
           Flag: any mismatch -> alert compliance team
           Target: 100% reconciliation within 30 minutes of settlement
```

**Common follow-up:** "What happens if your system goes down during market hours?"

**Answer:** "Two scenarios. (1) Order service goes down: ECS auto-scales replacement tasks in ~30 seconds. Clients get 503 errors during this window -- they can retry. No data loss because unprocessed orders weren't committed yet. (2) Matching engine goes down: the Kafka consumer stops reading. When it restarts, it resumes from the last committed offset, replays unprocessed orders, and rebuilds the order book from the event log. This is why event sourcing matters: the Kafka log IS the source of truth, and the order book is a derived view. Replay is deterministic -- same orders in same order produce identical order book state. During the outage (30-60 seconds), orders queue in Kafka. When the engine recovers, it processes the backlog. Users see delayed fills, not lost orders."

---

## Phase 5: Scaling & Latency (5-8 min)

### Part A: Single-Threaded Matching at Scale

```
SCALING THE MATCHING ENGINE (Numbered):

      1. SYMBOL PARTITIONING:
         5,000 symbols, each gets its own Kafka partition.
         |
         Tier 1 (top 50 symbols by volume): dedicated EC2 bare metal each.
           RELIANCE, TCS, INFY, HDFC, etc.
           Each handles 50K-100K orders/day.
         |
         Tier 2 (next 450 symbols): shared engines.
           10 EC2 instances, each handling ~45 symbols.
           Each instance: 45 single-threaded matching loops.
           Executor per symbol -> still single-threaded per symbol.
         |
         Tier 3 (remaining 4,500 symbols): pooled.
           5 EC2 instances, each handling ~900 symbols.
           Low volume: < 100 orders/day per symbol.

      2. THROUGHPUT MATH (single-threaded):
         Per match operation: ~100 microseconds (us)
           TreeMap lookup: ~0.2 us
           Queue operations: ~0.1 us
           Trade object creation: ~0.5 us
           Kafka produce (async): ~10 us
           Total: ~100 us
         |
         Single thread throughput: 1,000,000 / 100 = 10,000 matches/sec
         |
         Busiest symbol (RELIANCE): ~100K orders/day
           = ~30 orders/sec avg, ~500 orders/sec peak (market open)
           Single thread handles 10,000/sec -> 20x headroom at peak.
         |
         This is why single-threaded works:
           The matching operation is CPU-bound and fast.
           Bottleneck is network I/O (Kafka), not CPU.

      3. LMAX DISRUPTOR PATTERN:
         Standard approach: Kafka consumer -> process -> Kafka producer
         |
         LMAX optimization (if needed for HFT-level latency):
           Ring buffer (lock-free circular array)
           Single writer (network thread writes incoming orders)
           Single reader (matching thread reads and processes)
           |
           No garbage collection pressure:
             Pre-allocate ring buffer entries
             Reuse objects (object pooling)
             No allocation on hot path -> no GC pauses
           |
           Cache-friendly:
             Sequential memory access (ring buffer is contiguous)
             CPU prefetcher works optimally
             No pointer chasing (unlike LinkedList)
           |
           LMAX Exchange: 6 million orders/sec on single thread.
           Our target: 10K orders/sec. We don't need LMAX optimization,
           but mentioning it shows depth.

      4. MATCHING ENGINE RECOVERY:
         Engine crashes for symbol RELIANCE.
         |
         Recovery steps:
           a. New engine starts, reads from Kafka topic orders.RELIANCE
           b. Set consumer offset to beginning of day (market open)
           c. Replay ALL orders for the day:
                Order 1: BUY LIMIT 100 @ 2500 -> match -> trades
                Order 2: SELL LIMIT 50 @ 2498 -> match -> trades
                ... (replay 50,000 orders in ~5 seconds)
           d. Order book state is now identical to pre-crash state
           e. Resume processing new orders from Kafka
         |
         Why this works: matching is DETERMINISTIC.
           Same orders + same sequence = same order book state.
           This is why single-threaded matters: no thread scheduling variance.
         |
         Kafka retention: keep today's orders (intraday).
           End of day: snapshot order book state to S3.
           Next day: fresh order book (previous day's orders don't carry over).
```

### Part B: Co-Location and Network Optimization

```
LOW-LATENCY NETWORKING (Numbered):

      1. CO-LOCATION HIERARCHY:
         |
         +-- Exchange matching engine (NSE Mumbai)
         |     | 0.001ms (same rack)
         |     v
         |   Co-located servers (same data center as NSE)
         |     | 0.5ms (same building, Direct Connect)
         |     v
         +-- AWS ap-south-1 Mumbai
         |     | 1-2ms (Direct Connect fiber)
         |     v
         |   Our matching engine (EC2 bare metal)
         |     | 2-5ms (public internet)
         |     v
         +-- Our order service (ECS Fargate)
         |     | 50-200ms (mobile network)
         |     v
         +-- Client mobile app
         |
         Total: client -> our matching engine: ~55-210ms (retail acceptable)
         Total: client -> exchange (via us): ~58-215ms

      2. AWS DIRECT CONNECT:
         Dedicated 1 Gbps fiber from AWS Mumbai to exchange colo.
         |
         Benefits:
           - Consistent latency (no internet routing variance)
           - 1-2ms instead of 5-50ms
           - Private connection (encrypted, compliant)
           - Higher bandwidth (dedicated, not shared)
         |
         For a broker (not exchange): Direct Connect to exchange
         For an exchange: bare metal in own data center (no cloud)

      3. EC2 PLACEMENT GROUPS:
         Cluster placement group for matching engine fleet:
           All matching engines on same rack
           Inter-node latency: < 0.1ms
           Network throughput: 25 Gbps between instances
         |
         Why: matching engines may need to communicate
         (cross-symbol risk checks, index-level circuit breakers)

      4. JVM TUNING (for Java-based matching engine):
         |
         GC tuning (critical for low-latency):
           -XX:+UseZGC                  (< 1ms GC pauses)
           -XX:MaxGCPauseMillis=1       (target 1ms max pause)
           -Xmx16g -Xms16g             (pre-allocate, avoid resizing)
           -XX:+AlwaysPreTouch          (page fault at startup, not runtime)
         |
         Object allocation:
           Pre-allocate Order and Trade objects (object pooling)
           Avoid autoboxing: use primitive long/double, not BigDecimal on hot path
           Reuse buffers for Kafka serialization
         |
         Thread affinity:
           Pin matching thread to dedicated CPU core
           Isolate core from OS scheduling (isolcpus kernel param)
           Disable hyper-threading on that core (avoid pipeline sharing)
         |
         Target: p99 latency < 1ms for matching operation (excluding network)
```

### Part C: Market Data Fan-Out at Scale

```
MARKET DATA DISTRIBUTION (Numbered):

      1. THE PROBLEM:
         50,000 price ticks per second (all symbols combined)
         200,000 WebSocket connections (all users during market hours)
         Each user subscribes to ~10 symbols
         |
         Naive fan-out: 50K ticks * 200K users = 10 BILLION messages/sec
         Obviously impossible. Need smart fan-out.

      2. SYMBOL-BASED FAN-OUT:
         |
         Kafka topic: market-data.RELIANCE
         Consumer: Market Data Service (ECS)
         |
         Service maintains: symbol -> Set<WebSocket connections>
           RELIANCE: [conn_001, conn_002, ..., conn_50000]  (50K subscribers)
           TCS:      [conn_003, conn_004, ..., conn_30000]  (30K subscribers)
         |
         On new tick for RELIANCE:
           Read tick from Kafka (1 message)
           Broadcast to 50K WebSocket connections (50K writes)
           Total: 1 read + 50K writes per tick
         |
         10 ticks/sec for RELIANCE = 500K WebSocket writes/sec for one symbol
         50 active symbols * 500K = 25M WebSocket writes/sec total

      3. TIERED DISTRIBUTION:
         |
         Tier 1: Kafka (symbol-level topics)
           1 message per tick per symbol.
           50K ticks/sec total across all topics.
         |
         Tier 2: Market Data Service (ECS fleet)
           20 ECS tasks, each handling ~10K WebSocket connections
           Each task subscribes to ALL symbol topics
           Broadcasts to its 10K connections locally
         |
         Tier 3: API Gateway WebSocket
           Manages 200K persistent WebSocket connections
           Routes messages from ECS to correct client connections
         |
         Total per tick: 1 Kafka read + 1 ECS broadcast + N WebSocket pushes

      4. THROTTLING:
         Not all users need 10 ticks/sec:
           Professional traders: every tick (10/sec)
           Active retail: 1 tick/sec (throttle 10x)
           Casual viewers: 1 tick/5sec (throttle 50x)
         |
         Server-side throttle per connection:
           Reduce 25M writes/sec to ~5M writes/sec (80% reduction)
           Most retail users don't notice 1-second delayed price
```

**Common follow-up:** "How would you handle a flash crash where order volume spikes 100x?"

**Answer:** "Three layers of protection. (1) **Backpressure**: Kafka partitions buffer orders when matching engine can't keep up. Orders queue but aren't lost. Matching engine processes at its max rate (10K orders/sec per symbol), and the queue drains once the spike subsides. (2) **Circuit breakers**: if price moves 10% in a session, symbol is halted. This prevents cascading sells. (3) **Rate limiting at API Gateway**: 10 orders/sec per user prevents a single panicking user from flooding the system. At the system level, throttle to 50K orders/sec total. Beyond that, return 429 Too Many Requests. Users see 'Order temporarily delayed' -- better than a crashed system."

---

## Phase 6: Tradeoffs (3-5 min)

### Latency vs Consistency (Order Processing)

| Aspect | Low Latency (async writes) | Strong Consistency (sync writes) |
|--------|---------------------------|----------------------------------|
| Order persistence | Write to Kafka first, RDS async | Write to RDS first, then Kafka |
| Ack to client | After Kafka produce (~1ms) | After RDS commit (~5ms) |
| Risk | Order in Kafka but not RDS on crash | 5ms slower per order |
| Recovery | Replay from Kafka rebuilds RDS | RDS is always current |
| Used by | High-frequency exchanges | Retail brokerages |

**Say:** "I'd write to Kafka first and ack the client immediately. RDS update happens asynchronously via a consumer. This gives sub-2ms order acknowledgment. The risk is: if the Kafka-to-RDS consumer crashes, RDS might be briefly stale. But Kafka IS the source of truth -- the consumer can catch up on restart. For a retail brokerage (10ms target), writing to RDS synchronously is fine and simpler. For an exchange (sub-millisecond target), Kafka-first is mandatory."

### Market Orders vs Limit Orders

| Aspect | Market Order | Limit Order |
|--------|-------------|-------------|
| Fill guarantee | YES (if liquidity exists) | NO (may never fill) |
| Price guarantee | NO (slippage risk) | YES (at limit or better) |
| Order book impact | Never rests (fills or fails) | May rest (adds liquidity) |
| Complexity | Simple (sweep) | Moderate (match or rest) |
| Risk | Slippage in thin books | Opportunity cost of unfilled |
| Best for | "I must buy NOW" | "I want a specific price" |

**Say:** "Limit orders are the building blocks of the order book -- they ADD liquidity (resting orders for others to match against). Market orders REMOVE liquidity (they consume resting orders). A healthy market needs both: limit orders create depth, market orders create activity. Most exchanges charge lower fees for limit orders (maker rebate) to incentivize liquidity provision. Our matching engine treats them differently: limit orders may rest, market orders never rest."

### FIFO vs Average Cost Basis

| Aspect | FIFO (First In First Out) | Average Cost |
|--------|--------------------------|--------------|
| Which shares sold | Earliest purchased | Blended average |
| Tax optimization | YES (can harvest losses) | NO (blended, less control) |
| Complexity | Must track individual lots | Simple running average |
| P&L per trade | Varies by lot age | Consistent per share |
| Used by | US brokers (IRS default) | Indian brokers (Zerodha) |

**Say:** "Average cost is simpler to implement and explain to users: buy 100 at Rs 200, buy 100 at Rs 300, avg cost = Rs 250. Sell 100, cost basis = Rs 250, P&L = (sell price - 250) * 100. FIFO requires tracking each purchase lot separately: sell 100, cost basis = Rs 200 (first lot), P&L = (sell price - 200) * 100. FIFO is better for tax optimization (sell high-cost lots first to minimize taxable gains). I'd implement average cost as default (like Zerodha) and offer FIFO as an option for tax-conscious users."

### CP vs AP: Where Each Applies

| Component | CAP Choice | Why |
|-----------|-----------|-----|
| Order submission + matching | **CP** | Lost order = regulatory violation. Duplicate trade = financial loss. Kafka acks=all, exactly-once processing. |
| Trade records (RDS) | **CP** | Trade history must be perfectly accurate. Aurora synchronous replication. Auditors compare our records to exchange records. |
| Positions and margin (DynamoDB) | **CP** | Under-counting margin = user trades beyond their means. Over-counting = user can't trade when they should be able to. |
| Market data (Redis + WebSocket) | **AP** | Price tick 100ms late is invisible to retail. Cache miss falls through to Kafka consumer. Next tick overwrites stale data. |
| OHLCV candles (Timestream) | **AP** | Candle data 1-2 seconds delayed is fine. Charts are not used for millisecond-level decisions by retail traders. |
| Order book snapshots (Redis) | **AP** | Displayed depth is approximate. Professional traders use direct exchange feed for precise depth. Our display is for reference. |

**Say:** "The pattern is: CP for anything where incorrectness has financial or regulatory consequences (orders, trades, positions, margin), and AP for anything where brief staleness is invisible to the user (market data, candles, displayed order book). The margin system is an interesting edge case: it's cached in Redis (AP) for fast risk checks, but the DynamoDB source of truth is CP. If the cache is stale and a user slightly over-trades, we catch it in post-trade reconciliation and can reverse the trade if needed. This is a deliberate trade-off: we accept a tiny risk of over-trading (caught within seconds) to avoid adding 5ms latency to every risk check."

---

## Red Flags (What NOT to Do)

- No order book data structure -- "store orders in a database table and query for matching" adds 5ms per order, makes matching O(n) instead of O(log n)
- Multi-threaded matching without justification -- "use 10 threads for the matching engine" introduces race conditions in order book operations, non-deterministic replay
- Polling for market data -- "client polls every 1 second for price" misses price movements, wastes bandwidth, adds 500ms average latency
- No risk checks -- "validate order after matching" means the user could exhaust margin, creating financial liability for the broker
- Full order book in database -- "persist every order book change to RDS" adds 5ms per operation on the critical matching path
- Ignoring partial fills -- "an order either fully fills or doesn't" loses matching opportunities and reduces market liquidity
- No circuit breakers -- "let prices move freely" risks flash crashes and regulatory penalties
- Single point of failure on matching engine -- "one server handles all symbols" means one crash halts the entire exchange

## Green Flags (What Interviewers Want to Hear)

- Lead with order book: "Bids in descending TreeMap, asks in ascending TreeMap, each price level is a FIFO queue."
- Price-time priority: "Best price wins. At same price, earliest order fills first."
- Partial fills with numbers: "BUY LIMIT 100 @ 2500. Fill 50 @ 2498, 30 @ 2498, 20 @ 2500. Avg price 2498.40."
- Single-threaded by design: "One thread per symbol. No locks, deterministic, replayable from Kafka."
- Risk check chain: "MarginCheck -> PositionLimit -> CircuitBreaker. Short-circuit on first failure."
- Event sourcing: "Kafka log IS the source of truth. Order book is a derived view. Replay rebuilds state."
- Latency zones: "Hot path <10ms (order to Kafka). Ultra-hot <0.1ms (matching). Async <1s (post-trade)."
- Circuit breakers: "Price hits +/-10%, symbol halts, pending orders cancelled, 15-minute cool-down."
- Real exchange reference: "NSE processes 50K orders/sec. LMAX does 6M orders/sec single-threaded."

---

## 30-Second Elevator Pitch

> "For a stock trading platform, the core is the **matching engine** -- an in-memory **order book** with **bids in a descending TreeMap** and **asks in an ascending TreeMap**, each price level holding a **FIFO queue** for price-time priority. A BUY LIMIT at Rs 2,500 scans asks from lowest: if ask <= 2,500, **match**, handle **partial fills** across price levels. The engine is **single-threaded per symbol** -- no locks, deterministic, replayable from **Kafka** (event sourcing). Before matching, orders pass through a **risk check chain**: MarginCheck -> PositionLimit -> CircuitBreaker (chain of responsibility, short-circuit on failure). Post-trade processing is async via Kafka consumers: **RDS Aurora** for order lifecycle, **DynamoDB** for positions and margins, **Redis** for real-time market data cache, **Timestream** for OHLCV candles. **WebSocket** pushes price ticks and order status to clients in < 50ms. **Circuit breakers** halt symbols when price moves +/-10%. Settlement is **T+1 netting** -- trades net per user per symbol, clearing corporation transfers shares and funds. System is **CP for orders/trades** (zero tolerance for errors) and **AP for market data** (stale price for 100ms is invisible to retail traders)."

**Time: Under 30 seconds. Covers: order book, matching, partial fills, single-threaded, event sourcing, risk checks, post-trade pipeline, market data, circuit breakers, settlement, CAP.**

---

## Phase-by-Phase Timing Cheat Sheet

```
Phase 1:  Clarify Requirements              2-3 min   (order types, latency target, settlement model, exchange vs broker)
Phase 2:  High-Level Architecture            5-7 min   (three latency zones, Kafka partitioning, matching engine, post-trade)
Phase 3:  Matching Engine Deep Dive          8-10 min  (order book TreeMap, BUY LIMIT matching, partial fills, market orders)
Phase 4:  Risk Management & Settlement       5-7 min   (risk check chain, circuit breakers, T+1 netting, reconciliation)
Phase 5:  Scaling & Latency                  5-8 min   (single-threaded math, LMAX, co-location, JVM tuning, market data fan-out)
Phase 6:  Tradeoffs Discussion               3-5 min   (latency vs consistency, market vs limit, FIFO vs avg cost, CP vs AP)
-----------------------------------------------------------------------------------
Total:                                       ~35 min
```

If short on time, shorten Phase 5 (scaling/latency) and Phase 6 (tradeoffs). Never skip Phase 3 (matching engine deep dive) -- that IS the interview for this problem and what differentiates a senior answer from a generic one. Phase 4 (risk management + settlement) is the second priority -- it shows you understand the financial domain beyond just data structures.
