# Stock Trading Platform (Zerodha/Upstox) -- Cloud Service Mapping

## Component-to-Service Mapping

| Our Component | AWS | Azure | GCP | Notes |
|---------------|-----|-------|-----|-------|
| **API Gateway** | API Gateway + WAF + CloudFront | API Management + Front Door + WAF | Cloud Endpoints + Apigee + Cloud Armor | Order submission, market data feeds, auth, rate limiting |
| **Order Service** | ECS Fargate (low-latency) | AKS (dedicated node pools) | GKE (node affinity) | Stateless order validation, risk checks, routing to matching engine |
| **Matching Engine** | EC2 bare metal (i3en / c6i) | Dedicated Host VMs | Sole-tenant nodes | Single-threaded per symbol, kernel bypass networking, co-located with exchange |
| **Order DB** | RDS Aurora PostgreSQL | Azure SQL | Cloud SQL / AlloyDB | Order lifecycle: PENDING -> PARTIAL -> FILLED -> CANCELLED. ACID required. |
| **Positions & Holdings** | DynamoDB | Cosmos DB | Firestore / Bigtable | User positions, portfolio, holdings. High-throughput key-value access by userId. |
| **Market Data Cache** | ElastiCache Redis (cluster mode) | Azure Cache for Redis | Memorystore (Redis) | Real-time quotes, L2 order book snapshots, last-traded price. Sub-ms reads. |
| **Event Bus** | MSK (Managed Kafka) | Event Hubs (Kafka protocol) | Pub/Sub | Order events, trade events, market data fan-out. Ordered per partition (symbol). |
| **Time-Series (Candles)** | Amazon Timestream | Azure Data Explorer (ADX) | BigQuery + Bigtable | OHLCV candles: 1m, 5m, 15m, 1h, 1D. Aggregation queries. |
| **WebSocket Gateway** | API Gateway WebSocket + Lambda | SignalR Service | Firebase Realtime / Cloud Run | Real-time price ticks, order status updates, portfolio P&L push |
| **Monitoring** | CloudWatch + X-Ray | Azure Monitor + App Insights | Cloud Monitoring + Trace | Order latency p99, matching engine throughput, error rates |
| **WAF + DDoS** | AWS WAF + Shield Advanced | Azure WAF + DDoS Protection | Cloud Armor | Protect trading APIs from DDoS. Financial services = high-value target. |
| **Audit Log** | S3 + Athena (immutable audit trail) | Blob Storage + Synapse | Cloud Storage + BigQuery | Regulatory: every order, modification, cancellation must be logged immutably |
| **DNS** | Route 53 (latency-based) | Traffic Manager | Cloud DNS | Multi-region routing, health checks, failover |
| **Secrets / Keys** | KMS + Secrets Manager | Key Vault | Cloud KMS + Secret Manager | API keys, exchange credentials, encryption keys |

---

## Trading Platform Architecture on AWS (Numbered)

```
User places a BUY LIMIT order for 100 shares of RELIANCE at Rs 2,500.

    1. CLIENT SUBMITS ORDER (mobile/web app):
       POST /api/v1/orders
       {
         symbol: "RELIANCE",
         side: "BUY",
         type: "LIMIT",
         price: 2500.00,
         quantity: 100,
         userId: "user_001"
       }
       |
       Client receives orderId immediately (async processing).
       WebSocket connection receives real-time status updates.
    |
    v
    2. API GATEWAY + WAF (entry point):
       WAF rules:
         - Rate limit: 10 orders/sec per user (prevent accidental flooding)
         - IP reputation: block known bad actors
         - Payload validation: reject malformed order JSON
       |
       API Gateway:
         - JWT token validation (Cognito)
         - Request throttling (global: 50K orders/sec)
         - Route to ECS Order Service
    |
    v
    3. ORDER SERVICE (ECS Fargate, stateless):
       Step 3a: Validate order fields:
         - Symbol exists in instrument master (Redis cache lookup, <1ms)
         - Price within circuit breaker bounds (Redis: RELIANCE upper=2750, lower=2250)
         - Quantity > 0, within lot size constraints
         - Market hours check: 9:15 AM - 3:30 PM IST
       |
       Step 3b: Risk checks (chain of responsibility):
         - MarginCheck: user has sufficient margin?
             DynamoDB: user_001 -> available_margin = Rs 500,000
             Order value = 100 * 2500 = Rs 250,000
             Margin required = 250,000 * 20% (for intraday) = Rs 50,000
             Available (500,000) >= Required (50,000) -> PASS
         - PositionLimitCheck: user not exceeding position limits?
             DynamoDB: user_001 -> RELIANCE position = 200 shares
             New position = 200 + 100 = 300 shares
             Limit = 10,000 shares -> PASS
         - CircuitBreakerCheck: symbol not halted?
             Redis: RELIANCE -> circuit_status = ACTIVE -> PASS
       |
       Step 3c: Persist order to RDS Aurora:
         INSERT INTO orders (order_id, user_id, symbol, side, type, price, qty, status)
         VALUES ('ord_001', 'user_001', 'RELIANCE', 'BUY', 'LIMIT', 2500.00, 100, 'PENDING')
       |
       Step 3d: Block margin (optimistic lock):
         DynamoDB: user_001 -> available_margin = 500,000 - 50,000 = 450,000
         ConditionExpression: "available_margin >= :required"
       |
       Step 3e: Publish to Kafka (MSK):
         Topic: orders.RELIANCE (partitioned by symbol)
         { orderId: "ord_001", symbol: "RELIANCE", side: "BUY", type: "LIMIT",
           price: 2500.00, qty: 100, timestamp: 1714100000000 }
    |
    v
    4. MATCHING ENGINE (EC2 bare metal, single-threaded per symbol):
       Consumer group: one matching engine instance per symbol partition.
       RELIANCE matching engine reads from Kafka topic: orders.RELIANCE
       |
       ORDER BOOK STATE (in-memory):
         ASKS (sell orders, sorted ascending by price):
           Rs 2,498.00: [sell_007 (50 qty), sell_012 (30 qty)]   <- best ask
           Rs 2,500.00: [sell_003 (200 qty)]
           Rs 2,505.00: [sell_015 (100 qty)]
         |
         BIDS (buy orders, sorted descending by price):
           Rs 2,495.00: [buy_009 (75 qty)]                       <- best bid
           Rs 2,490.00: [buy_011 (150 qty)]
       |
       MATCHING LOGIC for BUY LIMIT at Rs 2,500:
         Scan asks from lowest price:
           Ask Rs 2,498 <= Limit Rs 2,500? YES -> MATCH!
             sell_007: fill 50 @ Rs 2,498. Remaining: 100 - 50 = 50.
             sell_012: fill 30 @ Rs 2,498. Remaining: 50 - 30 = 20.
           Ask Rs 2,500 <= Limit Rs 2,500? YES -> MATCH!
             sell_003: fill 20 @ Rs 2,500. Remaining: 0. ORDER FULLY FILLED.
             sell_003 partially filled: 200 - 20 = 180 remaining.
       |
       TRADES GENERATED:
         Trade 1: buy_ord_001 <-> sell_007, 50 shares @ Rs 2,498
         Trade 2: buy_ord_001 <-> sell_012, 30 shares @ Rs 2,498
         Trade 3: buy_ord_001 <-> sell_003, 20 shares @ Rs 2,500
       |
       Total cost: (50*2498) + (30*2498) + (20*2500) = Rs 249,840
       Average fill price: Rs 249,840 / 100 = Rs 2,498.40
    |
    v
    5. TRADE EVENTS PUBLISHED (Kafka MSK):
       Topic: trades.RELIANCE
       [
         { tradeId: "T001", buyOrder: "ord_001", sellOrder: "sell_007",
           price: 2498.00, qty: 50, timestamp: ... },
         { tradeId: "T002", buyOrder: "ord_001", sellOrder: "sell_012",
           price: 2498.00, qty: 30, timestamp: ... },
         { tradeId: "T003", buyOrder: "ord_001", sellOrder: "sell_003",
           price: 2500.00, qty: 20, timestamp: ... }
       ]
       |
       Topic: order-updates
       { orderId: "ord_001", status: "FILLED", filledQty: 100,
         avgPrice: 2498.40, fills: 3 }
    |
    v
    6. POST-TRADE PROCESSING (ECS consumers, parallel):
       |
       6a. ORDER UPDATE SERVICE:
           RDS Aurora: UPDATE orders SET status='FILLED', filled_qty=100,
                       avg_price=2498.40 WHERE order_id='ord_001'
       |
       6b. POSITION UPDATE SERVICE:
           DynamoDB: user_001 -> RELIANCE position += 100 shares
           DynamoDB: user_001 -> avg_cost recalculated
           DynamoDB: user_001 -> release excess blocked margin
             Blocked: Rs 50,000 (for 100 shares at Rs 2,500)
             Actual: Rs 49,968 (for 100 shares at avg Rs 2,498.40)
             Release: Rs 32 back to available margin
       |
       6c. MARKET DATA UPDATE:
           Redis: RELIANCE -> last_price = 2500.00 (last trade price)
           Redis: RELIANCE -> best_bid = 2495.00, best_ask = 2500.00
           Timestream: insert OHLCV candle data point
       |
       6d. NOTIFICATION SERVICE:
           WebSocket Gateway: push to user_001's connection:
             { type: "ORDER_FILLED", orderId: "ord_001",
               avgPrice: 2498.40, totalCost: 249840.00 }
       |
       6e. AUDIT LOG:
           S3 (append-only, WORM compliance):
             s3://audit-logs/2026/04/26/orders/ord_001.json
             Immutable record for regulatory compliance (SEBI requirements)
    |
    v
    7. REAL-TIME MARKET DATA BROADCAST (WebSocket Gateway):
       All subscribers to RELIANCE get price update:
         { symbol: "RELIANCE", ltp: 2500.00, bid: 2495.00, ask: 2500.00,
           volume: 1250000, change: +1.2% }
       |
       API Gateway WebSocket -> Lambda fan-out -> connected clients
       Latency target: < 50ms from trade execution to client notification
```

---

## Low-Latency Considerations

```
LATENCY OPTIMIZATION FOR TRADING (Numbered):

    1. CO-LOCATION (exchange proximity):
       Matching engine EC2 instances in same data center as exchange.
       AWS Direct Connect to NSE/BSE data center (Mumbai).
       |
       Network latency:
         Same AZ: < 0.5ms
         Cross-AZ (same region): 1-2ms
         Direct Connect to exchange: < 1ms
         Public internet to exchange: 5-50ms (unacceptable)
       |
       For Indian markets: ap-south-1 (Mumbai) is mandatory.
       EC2 placement groups: cluster placement for matching engines.

    2. KERNEL BYPASS NETWORKING:
       Standard Linux networking: ~50 microseconds per packet (kernel overhead).
       DPDK (Data Plane Development Kit): ~5 microseconds (bypass kernel).
       |
       EC2 bare metal (i3en.metal / c6i.metal):
         Elastic Network Adapter (ENA) with enhanced networking
         SR-IOV: direct NIC access, bypass hypervisor
         Jumbo frames: 9001 MTU (reduce packet count)
       |
       For matching engine: every microsecond matters.
       HFT firms use FPGA-based networking (<1 microsecond).
       Our target: order-to-ack < 10ms (retail trading, not HFT).

    3. PLACEMENT GROUPS + DEDICATED HOSTS:
       Cluster placement group: matching engine instances on same rack.
       |
       Benefits:
         - Lowest possible inter-node latency (~0.1ms)
         - Highest network throughput (25 Gbps between instances)
         - Consistent latency (no noisy neighbor on same rack)
       |
       Dedicated Hosts: no shared tenancy.
         - Predictable CPU performance (no steal time)
         - Required for some financial compliance (data isolation)

    4. IN-MEMORY EVERYTHING (matching engine):
       Order book: in-memory TreeMap (not database).
       Price levels: TreeMap<BigDecimal, Queue<Order>>
       |
       Matching engine NEVER touches disk for hot path:
         Read order from Kafka -> match in memory -> write trade to Kafka
         Total: < 100 microseconds per order (in-memory matching)
       |
       Persistence is async:
         Kafka provides durability (replicated log)
         RDS/DynamoDB updates happen in post-trade processing (async)
         Matching engine can replay from Kafka on restart

    5. SINGLE-THREADED MATCHING (no locks):
       One thread per symbol. No mutexes, no contention, no cache invalidation.
       |
       Why single-threaded is FASTER than multi-threaded:
         - No lock contention (locks cost 50-200ns each)
         - No cache line bouncing (shared mutable state kills L1 cache)
         - Predictable latency (no lock wait variance)
         - Easier correctness (no race conditions in order matching)
       |
       LMAX Disruptor pattern:
         Lock-free ring buffer for order ingestion.
         Single writer, multiple readers.
         Mechanical sympathy: cache-line padding, sequential memory access.

    6. DIRECT CONNECT (AWS to exchange):
       AWS Direct Connect: dedicated 10 Gbps link to exchange colo.
       |
       Benefits vs public internet:
         - Consistent latency (no internet routing variance)
         - Higher bandwidth (dedicated, not shared)
         - Lower packet loss (private fiber)
         - Compliance: encrypted private connection for financial data
       |
       Setup: VIF (Virtual Interface) from AWS Direct Connect location
       to exchange's data center in Mumbai.
```

---

## Cost Estimation (1M Users Scale)

### Assumptions

```
Total registered users:           1,000,000 (1M)
Daily Active Users:               200,000 (20% active)
Orders per active user per day:   5 (mix of market + limit)
Total orders per day:             1,000,000 (1M orders/day)
Peak orders per second:           500 (market open, 9:15 AM spike)
Trades per day:                   800,000 (80% fill rate)
Symbols tracked:                  5,000 (NSE + BSE)
Market data ticks per second:     50,000 (all symbols combined)
WebSocket connections (peak):     200,000 (all DAU connected)
Candle data points per day:       5,000 symbols * 375 min = 1.875M (1-min candles)
Average order value:              Rs 50,000 (~$600)
Daily trading volume:             Rs 50B (~$600M)
```

### Monthly Cost Breakdown (AWS)

| Resource | Spec | Monthly Cost |
|----------|------|-------------|
| **EC2 Bare Metal (Matching Engine)** | 5 x c6i.metal (128 vCPU, 256 GB), dedicated hosts, cluster placement group. One per symbol partition (top 5 partitions). | ~$45,000 |
| **ECS Fargate (Order Service)** | 50 tasks, 4 vCPU / 8 GB each. Handles order validation, risk checks, routing. Auto-scale to 150 at peak. | ~$22,000 |
| **ECS Fargate (Post-Trade Services)** | 30 tasks, 2 vCPU / 4 GB each. Position updates, notifications, audit logging. | ~$8,000 |
| **RDS Aurora PostgreSQL (Orders DB)** | Multi-AZ, db.r6g.4xlarge, 2 read replicas, 2 TB storage. Order lifecycle, trade history. | ~$12,000 |
| **DynamoDB (Positions + Holdings)** | On-demand. 1M writes/day + 5M reads/day. 500 GB storage. User positions, margins, portfolios. | ~$3,000 |
| **ElastiCache Redis (Market Data)** | 10 shards, r6g.xlarge, 1 replica each. Real-time quotes, order book snapshots, circuit breaker state. | ~$12,000 |
| **MSK (Managed Kafka)** | 6 brokers, kafka.m5.2xlarge. Topics: orders per symbol, trades, market-data. 7-day retention. | ~$18,000 |
| **Amazon Timestream (Candles)** | 1.875M writes/day (1-min candles). 1 year memory store, 5 years magnetic store. OHLCV queries. | ~$2,500 |
| **API Gateway WebSocket** | 200K concurrent connections, 50K messages/sec (market data ticks). | ~$8,000 |
| **API Gateway REST** | 1M orders/day + 10M market data API calls/day | ~$4,000 |
| **S3 (Audit Logs)** | 1M orders/day * 1KB = 30 GB/month. WORM (Object Lock). Athena for compliance queries. | ~$100 |
| **CloudWatch + X-Ray** | Order latency metrics, matching engine throughput, error rates, dashboards, alarms. | ~$3,000 |
| **WAF + Shield Advanced** | DDoS protection for trading APIs. Shield Advanced: $3K/month flat + WAF rules. | ~$4,000 |
| **Direct Connect** | 1 Gbps dedicated connection to exchange colo (Mumbai). | ~$2,500 |
| **KMS + Secrets Manager** | Encryption keys, exchange API credentials, JWT signing keys. | ~$300 |
| **Route 53** | DNS, health checks, failover routing. | ~$200 |
| **Total** | | **~$144,600/month** |

### Cost per User

| Scale | Users | Orders/Day | Monthly Cost | Cost/User/Month |
|-------|-------|-----------|-------------|-----------------|
| Startup | 10K | 50K | ~$25,000 | $2.50 |
| Growth | 100K | 500K | ~$65,000 | $0.65 |
| Scale | 1M | 1M | ~$145,000 | $0.145 |
| Zerodha-scale | 10M | 10M | ~$800,000 | $0.08 |

### Cost Optimization Strategies

1. **Symbol Partitioning** -- Only top 500 symbols (by volume) get dedicated matching engine capacity. Remaining 4,500 symbols share pooled matching engines. 80/20 rule: top 500 symbols generate 95% of volume.
2. **Spot Instances for Non-Critical** -- Post-trade processing, market data aggregation, and audit log compression run on Spot instances (70% cheaper). Matching engine and order service stay on-demand/reserved.
3. **Reserved Instances** -- EC2 bare metal (matching engines), RDS Aurora, ElastiCache: 1-year reserved saves ~40%. These run 24/7 during market hours.
4. **Timestream Tiering** -- Recent candles (7 days) in memory store for fast queries. Older candles in magnetic store (90% cheaper). Most chart queries are for recent data.
5. **Kafka Retention** -- 7-day retention for order/trade topics. Older data archived to S3 for compliance. Reduces MSK storage costs by 80%.
6. **WebSocket Connection Pooling** -- Share WebSocket connections for users subscribed to the same symbols. Fan-out at the gateway, not per-user Kafka consumers.

---

## Regulatory Compliance (SEBI / SEC Requirements)

```
AUDIT LOGGING AND TRADE REPORTING (Numbered):

    1. IMMUTABLE AUDIT TRAIL:
       Every order action must be logged immutably:
         - Order placed (with timestamp, user, IP, device)
         - Order modified (old values + new values)
         - Order cancelled (reason, timestamp)
         - Trade executed (both sides, price, quantity)
         - Risk check results (pass/fail, which check, values)
       |
       Storage: S3 with Object Lock (WORM -- Write Once Read Many)
         Governance mode: admin can override (internal audits)
         Compliance mode: NO ONE can delete (regulatory retention)
         Retention: 7 years (SEBI requirement for Indian markets)
       |
       Format: JSON lines, partitioned by date/symbol:
         s3://audit-logs/2026/04/26/RELIANCE/orders.jsonl
         s3://audit-logs/2026/04/26/RELIANCE/trades.jsonl

    2. TRADE REPORTING (T+1 settlement):
       End-of-day batch: generate trade reports for clearing corporation.
       |
       AWS Batch job (daily 3:30 PM IST):
         Read: all trades from Kafka (trades.* topics)
         Aggregate: net positions per user per symbol
         Generate: clearing file in exchange-specified format
         Upload: SFTP to clearing corporation (NSE/BSE)
       |
       Reconciliation:
         Our trades DB vs exchange confirmation file
         Mismatch alert -> manual investigation within 30 minutes

    3. MARKET SURVEILLANCE:
       Circuit breaker enforcement:
         Redis stores upper/lower circuit limits per symbol
         Order service rejects orders outside circuit limits
         Matching engine halts symbol if price hits circuit
       |
       Abnormal pattern detection:
         - Wash trading: same user buys and sells to self
         - Spoofing: large orders placed and cancelled rapidly
         - Front-running: orders placed ahead of large block orders
       |
       AWS: Kinesis Data Analytics for real-time pattern detection
       Alert: SNS -> PagerDuty -> compliance team

    4. DATA ENCRYPTION AND ACCESS CONTROL:
       At rest: KMS encryption for all databases (RDS, DynamoDB, S3)
       In transit: TLS 1.3 for all API calls, mutual TLS for exchange connections
       Access: IAM roles + Cognito (user auth) + API Gateway authorizers
       PII: user data (PAN, Aadhaar, bank details) encrypted with per-user KMS keys
       Audit: CloudTrail logs ALL API calls to AWS services (who accessed what, when)
```

---

## Disaster Recovery for Financial Trading

```
DR STRATEGY: ACTIVE-PASSIVE WITH RPO=0 (Numbered):

    1. WHY RPO=0 (zero data loss):
       In financial trading, losing even ONE trade record is unacceptable.
       Regulatory requirement: every order and trade must be recoverable.
       |
       RPO (Recovery Point Objective) = 0: no data loss on failover.
       RTO (Recovery Time Objective) = 5 minutes: acceptable downtime.
       |
       During market hours (9:15 AM - 3:30 PM IST, ~6 hours):
         5 minutes downtime = missed orders. Unpleasant but survivable.
         Lost trades = regulatory violation. NEVER acceptable.

    2. ACTIVE-PASSIVE ARCHITECTURE:
       Primary region: ap-south-1 (Mumbai) -- active, handles all traffic
       DR region: ap-south-2 (Hyderabad) -- passive, warm standby
       |
       Primary (Mumbai):
         - Matching engines running, processing orders
         - RDS Aurora primary writer
         - DynamoDB global table (active)
         - MSK cluster (primary)
         - All client connections here
       |
       Passive (Hyderabad):
         - Matching engines pre-provisioned but idle
         - RDS Aurora read replica (cross-region, sync lag < 1s)
         - DynamoDB global table (replica, async < 1s)
         - MSK MirrorMaker 2 (cross-region Kafka replication)
         - No client connections (Route 53 health check pointing to Mumbai)

    3. SYNCHRONOUS REPLICATION (RPO=0 components):
       |
       Orders DB (RDS Aurora):
         Aurora Global Database: synchronous commit to DR region.
         Every committed transaction replicated before ack to client.
         Penalty: ~5ms additional write latency (acceptable for order placement).
       |
       Kafka (MSK):
         MSK MirrorMaker 2: near-synchronous replication.
         acks=all on primary + async mirror to DR.
         Potential gap: last 1-2 seconds of events.
         Mitigation: replay from primary Kafka log on recovery.
       |
       DynamoDB:
         Global Tables: async replication (typically < 1 second).
         For positions/margins: eventual consistency is acceptable
         during failover (reconcile from trade log after recovery).

    4. FAILOVER PROCEDURE (RTO = 5 minutes):
       |
       Trigger: CloudWatch alarm detects primary region unhealthy
         - API Gateway 5xx rate > 50% for 60 seconds
         - Matching engine heartbeat missing for 30 seconds
         - RDS Aurora primary unreachable for 60 seconds
       |
       Automated failover (Step Functions + Lambda):
         Minute 0: Alarm triggers failover Lambda
         Minute 1: Route 53 health check fails, DNS switches to DR region
         Minute 2: Aurora Global Database promotes DR replica to writer
         Minute 3: Matching engines in DR start consuming from DR Kafka
         Minute 4: DynamoDB global table DR becomes primary
         Minute 5: Clients reconnect via new DNS. Trading resumes.
       |
       Post-failover:
         Reconcile: compare primary Kafka log (if accessible) with DR
         Identify: any trades in primary not yet replicated to DR
         Resolution: manual trade adjustment with exchange

    5. TESTING (mandatory for financial systems):
       Monthly DR drill: failover to DR during non-market hours (Saturday)
       Chaos engineering: simulate AZ failure during market hours (single AZ, not full region)
       Runbook: documented step-by-step for manual failover if automation fails
       Regulatory: SEBI requires BCP (Business Continuity Plan) documentation and annual testing
```

---

## Interview Tip

> "For a stock trading platform on AWS, I'd use **EC2 bare metal instances** for the matching engine -- **single-threaded per symbol**, no locks, no contention, pure in-memory matching using **TreeMap-based order books**. Orders flow through **API Gateway + WAF** to **ECS Fargate** (order validation, risk checks), then to **Kafka (MSK)** partitioned by symbol for ordered delivery to the matching engine. The order book maintains **bids in a descending TreeMap** and **asks in an ascending TreeMap** -- each price level is a **FIFO queue** (price-time priority). A BUY LIMIT at $105 scans asks from lowest: if ask <= $105, match, generate trades, handle partial fills across multiple price levels. Post-trade processing is async via Kafka consumers: **RDS Aurora** for order lifecycle (ACID), **DynamoDB** for positions and margins (high throughput), **Redis** for real-time market data cache, **Timestream** for OHLCV candles. Risk checks use a **chain of responsibility**: MarginCheck -> PositionLimit -> CircuitBreaker -- reject early, reject fast. **WebSocket** connections push real-time price ticks and order status. For DR: **active-passive with RPO=0** -- Aurora Global Database for synchronous cross-region replication, because losing a trade record is a regulatory violation. System is **CP for orders and trades** (zero tolerance for errors) and **AP for market data** (stale price for 100ms is acceptable)."

This shows you understand **matching engine architecture, price-time priority, order book data structures, risk management chain, event-driven post-trade processing, low-latency design, and financial regulatory requirements** -- the seven pillars of trading platform design.

---

## Quick Reference: Which Service When

| Decision Point | Service | Config | Why |
|---------------|---------|--------|-----|
| Order submission | API Gateway + WAF | Rate limit 10 orders/sec/user, JWT auth, payload validation | Security + throttling at edge |
| Order processing | ECS Fargate | Stateless, auto-scale 50-150 tasks, 4 vCPU each | Validation, risk checks, margin blocking |
| Order matching | EC2 bare metal | c6i.metal, cluster placement group, single-threaded per symbol | Lowest latency, no lock contention, kernel bypass |
| Order persistence | RDS Aurora PostgreSQL | Multi-AZ, 2 read replicas, Global Database for DR | ACID for order lifecycle, RPO=0 cross-region |
| Positions + margins | DynamoDB | On-demand, global tables for DR | High-throughput key-value, per-user access pattern |
| Market data cache | ElastiCache Redis | 10 shards, cluster mode, sub-ms reads | Real-time quotes, order book snapshots, circuit breaker state |
| Event streaming | MSK (Kafka) | 6 brokers, partitioned by symbol, acks=all | Ordered events per symbol, replay on matching engine restart |
| Time-series candles | Timestream | Memory store (7 days) + magnetic (5 years) | OHLCV aggregation, charting queries |
| Real-time push | API Gateway WebSocket | 200K concurrent connections, Lambda fan-out | Price ticks, order status, P&L updates |
| Audit compliance | S3 + Object Lock | WORM, 7-year retention, Athena for queries | Immutable trade records, SEBI/SEC compliance |
| DDoS protection | WAF + Shield Advanced | Financial services tier, $3K/month flat | Trading APIs are high-value DDoS targets |
| Exchange connectivity | Direct Connect | 1 Gbps dedicated, Mumbai region | Consistent low latency to NSE/BSE |
