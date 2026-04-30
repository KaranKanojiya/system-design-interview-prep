# Technologies & Infrastructure for the Payment System (Stripe/UPI)

> Interview-ready reference for a Senior Java developer.
> A payment system sits at the intersection of financial regulations, distributed transactions, and security architecture.
> Know the production stack, why each technology was chosen, and how our plain Java simulation maps to it.

---

## Table of Contents

| Technology | Why It's Here | Interview Relevance |
|------------|--------------|---------------------|
| Payment Processors (Stripe, Razorpay, PayPal) | Gateway integration for card/wallet payments | HIGH -- integration architecture |
| UPI / NPCI | India's real-time payment backbone | HIGH -- unique architecture |
| Card Networks (Visa, Mastercard, Amex) | Authorization flow for credit/debit cards | HIGH -- core payment flow |
| PostgreSQL | Ledger, payments, accounts (ACID transactions) | HIGH -- relational for financial data |
| Redis | Idempotency cache, exchange rate cache, webhook retry queue | HIGH -- caching layer |
| Kafka | Event streaming for async operations | HIGH -- async communication |
| PCI-DSS Vault | Tokenization, encryption at rest/transit | HIGH -- security architecture |
| Double-Entry Bookkeeping | Ledger architecture | HIGH -- financial correctness |
| Webhook Delivery | Reliable merchant notification | MEDIUM -- reliability patterns |
| Our Java Simulation | In-memory, synchronized, plain Java | HIGH -- interview code walkthrough |

---

## 1. Payment Processors: Stripe, Razorpay, PayPal

### Stripe API Architecture

```
  +----------------------------------------------------------------------+
  |                      STRIPE API FLOW                                  |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Merchant Server         Stripe API              Card Network        |
  |       |                     |                        |               |
  |       | (1) Create          |                        |               |
  |       |  PaymentIntent      |                        |               |
  |       |  amount: $100       |                        |               |
  |       |  currency: usd      |                        |               |
  |       |  idempotency_key:   |                        |               |
  |       |  "order_123"        |                        |               |
  |       |-------------------->|                        |               |
  |       |                     |                        |               |
  |       |  client_secret:     |                        |               |
  |       |  pi_xxx_secret_yyy  |                        |               |
  |       |<--------------------|                        |               |
  |       |                     |                        |               |
  |  Client (Browser/App)       |                        |               |
  |       |                     |                        |               |
  |       | (2) Confirm         |                        |               |
  |       |  PaymentIntent      |                        |               |
  |       |  payment_method:    |                        |               |
  |       |  pm_card_visa       |                        |               |
  |       |-------------------->|                        |               |
  |       |                     | (3) Authorize          |               |
  |       |                     |  card via network      |               |
  |       |                     |----------------------->|               |
  |       |                     |  auth_code: AUTH123    |               |
  |       |                     |<-----------------------|               |
  |       |                     |                        |               |
  |       |  status:            |                        |               |
  |       |  "succeeded"        |                        |               |
  |       |<--------------------|                        |               |
  |       |                     |                        |               |
  |       |                     | (4) Webhook:           |               |
  |       |  <-- POST /webhook--|  payment_intent.       |               |
  |       |      event data     |  succeeded             |               |
  |       |                     |                        |               |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  KEY STRIPE CONCEPTS:                                                |
  |  - PaymentIntent: represents a payment lifecycle                     |
  |  - PaymentMethod: tokenized card/bank (no raw card numbers)          |
  |  - Idempotency-Key header: prevents double-charge on retry           |
  |  - Webhooks: server-to-server notification of payment events         |
  |  - Two-step: create intent (server), confirm (client-side)           |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Stripe vs Razorpay vs PayPal

```
  +----------------------------------------------------------------------+
  |              PAYMENT PROCESSOR COMPARISON                             |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  +------------------+------------------+------------------+          |
  |  | Feature          | Stripe           | Razorpay         |          |
  |  +------------------+------------------+------------------+          |
  |  | Market           | Global           | India-focused    |          |
  |  | Card support     | Visa, MC, Amex   | Visa, MC, RuPay  |          |
  |  | UPI support      | No (via partner) | Native           |          |
  |  | Idempotency      | Header-based     | Header-based     |          |
  |  | Two-phase        | Auth + Capture   | Auth + Capture   |          |
  |  | Webhook          | POST + signature | POST + signature |          |
  |  | PCI scope        | SAQ-A (tokenized)| SAQ-A (tokenized)|          |
  |  | Settlement       | T+2 days         | T+2 days         |          |
  |  +------------------+------------------+------------------+          |
  |                                                                      |
  |  +------------------+------------------+                             |
  |  | Feature          | PayPal           |                             |
  |  +------------------+------------------+                             |
  |  | Market           | Global           |                             |
  |  | Model            | Wallet (account) |                             |
  |  | Notification     | IPN (webhook)    |                             |
  |  | Dispute handling | Built-in         |                             |
  |  | Two-sided        | Buyer + Seller   |                             |
  |  |                  | protection       |                             |
  |  | Settlement       | Instant (PayPal  |                             |
  |  |                  | balance)         |                             |
  |  +------------------+------------------+                             |
  |                                                                      |
  +----------------------------------------------------------------------+
```

---

## 2. UPI (Unified Payments Interface) -- NPCI Architecture

### UPI Payment Flow

```
  +----------------------------------------------------------------------+
  |                     UPI PAYMENT FLOW                                   |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Payer          Payer's PSP        NPCI          Payee's PSP   Payee |
  |  (User)         (Google Pay,       (Central      (Bank that    (Merch)|
  |                  PhonePe)          Switch)       holds funds)        |
  |    |                |                |               |            |  |
  |    | (1) Initiate   |                |               |            |  |
  |    |  pay $100 to   |                |               |            |  |
  |    |  merchant@bank |                |               |            |  |
  |    |--------------->|                |               |            |  |
  |    |                |                |               |            |  |
  |    | (2) Enter      |                |               |            |  |
  |    |  UPI PIN       |                |               |            |  |
  |    |--------------->|                |               |            |  |
  |    |                |                |               |            |  |
  |    |                | (3) Debit      |               |            |  |
  |    |                |  request       |               |            |  |
  |    |                |  (encrypted)   |               |            |  |
  |    |                |--------------->|               |            |  |
  |    |                |                |               |            |  |
  |    |                |                | (4) Route to  |            |  |
  |    |                |                |  payee's bank |            |  |
  |    |                |                |-------------->|            |  |
  |    |                |                |               |            |  |
  |    |                |                |               | (5) Credit |  |
  |    |                |                |               |  payee     |  |
  |    |                |                |               |  account   |  |
  |    |                |                |               |----------->|  |
  |    |                |                |               |            |  |
  |    |                |                | (6) Debit     |            |  |
  |    |                |                |  confirmation |            |  |
  |    |                |                |<--------------|            |  |
  |    |                |                |               |            |  |
  |    |                | (7) Success    |               |            |  |
  |    |                |  response      |               |            |  |
  |    |                |<---------------|               |            |  |
  |    |                |                |               |            |  |
  |    |  (8) Payment   |                |               |            |  |
  |    |  successful    |                |               |            |  |
  |    |  UPI ref: XYZ  |                |               |            |  |
  |    |<---------------|                |               |            |  |
  |                                                                      |
  |  KEY UPI CONCEPTS:                                                   |
  |  - VPA (Virtual Payment Address): user@bank (no account details)     |
  |  - PSP (Payment Service Provider): Google Pay, PhonePe, etc.         |
  |  - NPCI: Central switch routing between banks                        |
  |  - Real-time: money moves in < 30 seconds (or transaction expires)   |
  |  - Single-phase: no auth-then-capture, immediate debit               |
  |  - 2FA: device binding + UPI PIN                                     |
  |  - Interoperable: any bank to any bank (unlike Venmo/CashApp)        |
  +----------------------------------------------------------------------+
```

### UPI vs Card Network: Architectural Differences

```
  +----------------------------------------------------------------------+
  |              UPI vs CARD NETWORK ARCHITECTURE                         |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  UPI (Push Payment)                Card Network (Pull Payment)       |
  |  =====================             =============================      |
  |                                                                      |
  |  Payer PUSHES money                Merchant PULLS money              |
  |  to payee's VPA                    from cardholder's account         |
  |                                                                      |
  |  Single-phase:                     Two-phase:                        |
  |  Debit + credit in                 (1) Authorization (hold funds)    |
  |  one transaction                   (2) Capture (move funds)          |
  |                                    (3) Settlement (T+2)              |
  |                                                                      |
  |  Real-time settlement              Batch settlement (T+2)            |
  |                                                                      |
  |  Central switch (NPCI)             Multiple networks (Visa, MC)      |
  |  routes between banks              route through issuer/acquirer     |
  |                                                                      |
  |  No card number needed             Card number, expiry, CVV needed   |
  |  Just VPA (user@bank)              (tokenized in modern flows)       |
  |                                                                      |
  |  2FA: device + PIN                 3D Secure: password/OTP           |
  |                                                                      |
  |  Fee: $0 (govt. subsidized)        Fee: 2-3% interchange            |
  |                                                                      |
  +----------------------------------------------------------------------+
```

---

## 3. Card Networks: Visa, Mastercard, Amex Authorization Flow

### The 4-Party Model

```
  +----------------------------------------------------------------------+
  |              CARD AUTHORIZATION: 4-PARTY MODEL                        |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Cardholder      Merchant        Acquirer         Card Network       |
  |  (Customer)      (Shop)          (Merch. bank)    (Visa/MC)          |
  |      |              |               |                |               |
  |      |              |               |                |     Issuer    |
  |      |              |               |                |     (Card-    |
  |      |              |               |                |     holder's  |
  |      |              |               |                |     bank)     |
  |      |              |               |                |        |      |
  |      | (1) Swipe    |               |                |        |      |
  |      |  card / tap  |               |                |        |      |
  |      |  / enter     |               |                |        |      |
  |      |  online      |               |                |        |      |
  |      |------------->|               |                |        |      |
  |      |              |               |                |        |      |
  |      |              | (2) Auth      |                |        |      |
  |      |              |  request      |                |        |      |
  |      |              |  ($100, card  |                |        |      |
  |      |              |   token)      |                |        |      |
  |      |              |-------------->|                |        |      |
  |      |              |               |                |        |      |
  |      |              |               | (3) Route via  |        |      |
  |      |              |               |  network       |        |      |
  |      |              |               |--------------->|        |      |
  |      |              |               |                |        |      |
  |      |              |               |                | (4) Forward   |
  |      |              |               |                |  to issuer    |
  |      |              |               |                |------->|      |
  |      |              |               |                |        |      |
  |      |              |               |                |        | (5)  |
  |      |              |               |                |        | Check|
  |      |              |               |                |        | bal, |
  |      |              |               |                |        | fraud|
  |      |              |               |                |        | limit|
  |      |              |               |                |        |      |
  |      |              |               |                | (6) Approve   |
  |      |              |               |                |  auth code    |
  |      |              |               |                |<-------|      |
  |      |              |               |                |        |      |
  |      |              |               | (7) Auth       |        |      |
  |      |              |               |  approved      |        |      |
  |      |              |               |<---------------|        |      |
  |      |              |               |                |        |      |
  |      |              | (8) Approved  |                |        |      |
  |      |              |  AUTH-123     |                |        |      |
  |      |              |<--------------|                |        |      |
  |      |              |               |                |        |      |
  |      | (9) Receipt  |               |                |        |      |
  |      |<-------------|               |                |        |      |
  |                                                                      |
  |  LATER (batch settlement, T+2):                                      |
  |  - Acquirer submits capture to network                               |
  |  - Network moves funds: Issuer -> Network -> Acquirer -> Merchant    |
  |  - Interchange fee deducted (1.5-3%)                                 |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Interchange Fee Breakdown

```
  +----------------------------------------------------------------------+
  |                    FEE STRUCTURE                                       |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Customer pays $100 to Merchant via Visa credit card:                |
  |                                                                      |
  |  +-------------------+------------------------------------------+    |
  |  | Party             | Amount            | Why                  |    |
  |  +-------------------+-------------------+----------------------+    |
  |  | Issuing Bank      | $1.50 (1.5%)      | Interchange fee     |    |
  |  | (cardholder's)    |                   | (credit risk, fraud) |    |
  |  +-------------------+-------------------+----------------------+    |
  |  | Card Network      | $0.15 (0.15%)     | Network/assessment  |    |
  |  | (Visa)            |                   | fee (routing infra)  |    |
  |  +-------------------+-------------------+----------------------+    |
  |  | Acquirer Bank     | $0.10 (0.10%)     | Processing fee      |    |
  |  | (merchant's)      |                   | (settlement, risk)   |    |
  |  +-------------------+-------------------+----------------------+    |
  |  | Payment Processor | $0.25 + 2.9%      | Stripe/Razorpay fee |    |
  |  | (Stripe)          | (includes above)  | (all-in pricing)     |    |
  |  +-------------------+-------------------+----------------------+    |
  |  | Merchant receives | $97.10            | After all fees       |    |
  |  +-------------------+-------------------+----------------------+    |
  |                                                                      |
  |  For UPI: ZERO transaction fee (govt. subsidized in India).          |
  |  This is why UPI adoption is massive -- merchants save 2-3%.        |
  +----------------------------------------------------------------------+
```

---

## 4. Databases: PostgreSQL, Redis, Kafka

### PostgreSQL -- The Ledger Database (ACID)

```
  +----------------------------------------------------------------------+
  |                  POSTGRESQL FOR PAYMENT LEDGER                        |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  WHY PostgreSQL:                                                     |
  |  - ACID transactions for double-entry bookkeeping                    |
  |  - Serializable isolation for balance checks                         |
  |  - Constraints: CHECK (balance >= 0), UNIQUE (idempotency_key)       |
  |  - Triggers: audit trail on every update                             |
  |  - Proven: Stripe, Square, Adyen all use PostgreSQL for ledger       |
  |                                                                      |
  |  SCHEMA:                                                             |
  |                                                                      |
  |  payments                           ledger_entries                   |
  |  +----------------------------+     +----------------------------+   |
  |  | payment_id     PK         |     | entry_id       PK         |   |
  |  | merchant_id    FK         |     | payment_id     FK         |   |
  |  | amount         NUMERIC    |     | account_id     FK         |   |
  |  | currency       VARCHAR(3) |     | entry_type     ENUM       |   |
  |  | method         ENUM       |     |   (DEBIT/CREDIT)          |   |
  |  | status         ENUM       |     | amount         NUMERIC    |   |
  |  | idempotency_key UNIQUE    |     | created_at     TIMESTAMP  |   |
  |  | created_at     TIMESTAMP  |     +----------------------------+   |
  |  | updated_at     TIMESTAMP  |                                      |
  |  +----------------------------+     accounts                        |
  |                                     +----------------------------+   |
  |  merchants                          | account_id     PK         |   |
  |  +----------------------------+     | account_type   ENUM       |   |
  |  | merchant_id    PK         |     |   (CUSTOMER/MERCHANT/      |   |
  |  | name           VARCHAR    |     |    PLATFORM)               |   |
  |  | webhook_url    VARCHAR    |     | balance        NUMERIC    |   |
  |  | api_key_hash   VARCHAR    |     |   CHECK (>= 0)            |   |
  |  | status         ENUM       |     | currency       VARCHAR(3) |   |
  |  | created_at     TIMESTAMP  |     | created_at     TIMESTAMP  |   |
  |  +----------------------------+     +----------------------------+   |
  |                                                                      |
  |  CRITICAL CONSTRAINTS:                                               |
  |  - balance CHECK (>= 0): database-level overdraft prevention         |
  |  - idempotency_key UNIQUE: database-level duplicate prevention       |
  |  - entry_type ENUM: only DEBIT or CREDIT (no typos)                  |
  |  - Foreign keys: referential integrity between all tables            |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Redis -- Idempotency Cache and More

```
  +----------------------------------------------------------------------+
  |                   REDIS IN PAYMENT SYSTEMS                            |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  USE CASE 1: Idempotency Cache (CRITICAL)                           |
  |  +---------------------------------------------------------------+  |
  |  | Command: SET idempotency:{key} "PENDING" NX EX 86400          |  |
  |  | NX: Only set if key does not exist (atomic check-and-set)     |  |
  |  | EX 86400: TTL of 24 hours (auto-cleanup)                      |  |
  |  |                                                               |  |
  |  | Flow:                                                         |  |
  |  | (1) SET NX returns OK -> first request, proceed               |  |
  |  | (2) SET NX returns NIL -> duplicate, return cached result     |  |
  |  | (3) After processing: SET key "{result_json}" XX EX 86400     |  |
  |  |     XX: only set if key exists (update PENDING -> result)     |  |
  |  +---------------------------------------------------------------+  |
  |                                                                      |
  |  USE CASE 2: Exchange Rate Cache                                     |
  |  +---------------------------------------------------------------+  |
  |  | Command: SET exchange:USD_INR "83.50" EX 300                  |  |
  |  | TTL: 5 minutes (rates don't change by the second)             |  |
  |  | Pattern: Cache-aside                                          |  |
  |  |                                                               |  |
  |  | Flow:                                                         |  |
  |  | (1) GET exchange:USD_INR                                      |  |
  |  | (2) If miss: call Forex API, SET result with TTL              |  |
  |  | (3) If hit: return cached rate                                |  |
  |  +---------------------------------------------------------------+  |
  |                                                                      |
  |  USE CASE 3: Webhook Retry Queue                                     |
  |  +---------------------------------------------------------------+  |
  |  | Data structure: Sorted Set (ZSET)                             |  |
  |  | Score: next retry timestamp (epoch seconds)                   |  |
  |  | Member: webhook delivery ID                                   |  |
  |  |                                                               |  |
  |  | Commands:                                                     |  |
  |  | ZADD webhook:retry {next_retry_ts} {delivery_id}              |  |
  |  | ZRANGEBYSCORE webhook:retry 0 {now} LIMIT 0 100               |  |
  |  |   -> fetch up to 100 deliveries ready for retry               |  |
  |  | ZREM webhook:retry {delivery_id}                              |  |
  |  |   -> remove after successful delivery                         |  |
  |  +---------------------------------------------------------------+  |
  |                                                                      |
  |  USE CASE 4: Merchant Config Cache                                   |
  |  +---------------------------------------------------------------+  |
  |  | Pattern: Read-through cache                                   |  |
  |  | TTL: 10 minutes                                               |  |
  |  | Key: merchant:{merchant_id}                                   |  |
  |  | Value: JSON of merchant config (webhook URL, API version)     |  |
  |  +---------------------------------------------------------------+  |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Kafka -- Event Streaming

```
  +----------------------------------------------------------------------+
  |                   KAFKA IN PAYMENT SYSTEMS                            |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  TOPICS:                                                             |
  |  +---------------------------------------------------------------+  |
  |  | Topic                    | Producer        | Consumer(s)      |  |
  |  +--------------------------+-----------------+------------------+  |
  |  | payment.initiated        | PaymentService  | FraudService     |  |
  |  | payment.authorized       | PaymentService  | LedgerService,   |  |
  |  |                          |                 | WebhookService   |  |
  |  | payment.captured         | PaymentService  | SettlementSvc    |  |
  |  | payment.declined         | PaymentService  | WebhookService,  |  |
  |  |                          |                 | AnalyticsService |  |
  |  | payment.refunded         | RefundService   | LedgerService,   |  |
  |  |                          |                 | WebhookService   |  |
  |  | webhook.delivery.failed  | WebhookService  | RetryService     |  |
  |  +--------------------------+-----------------+------------------+  |
  |                                                                      |
  |  WHY KAFKA (not RabbitMQ, SQS):                                     |
  |  - Ordered per partition (payment events must be in order)           |
  |  - Replay: can reprocess events for reconciliation                   |
  |  - Durability: persisted to disk, replicated across brokers          |
  |  - High throughput: millions of payment events per second            |
  |  - Consumer groups: multiple services process same event stream      |
  |                                                                      |
  |  PARTITION KEY: payment_id                                           |
  |  - All events for one payment go to the same partition               |
  |  - Guarantees ordered processing per payment                         |
  |  - Different payments processed in parallel across partitions        |
  |                                                                      |
  |  +---------------------------------------------------------------+  |
  |  | Partition 0: PAY-001 events (initiated, authorized, captured) |  |
  |  | Partition 1: PAY-002 events (initiated, declined)             |  |
  |  | Partition 2: PAY-003 events (initiated, authorized, refunded) |  |
  |  +---------------------------------------------------------------+  |
  |                                                                      |
  +----------------------------------------------------------------------+
```

---

## 5. PCI-DSS: Tokenization, Encryption, Vault

### Tokenization Architecture

```
  +----------------------------------------------------------------------+
  |                  PCI-DSS TOKENIZATION                                  |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  THE PROBLEM: Storing card numbers is expensive and dangerous.       |
  |  PCI Level 1 audit: $50K-$500K/year. Data breach: $M+ fines.        |
  |                                                                      |
  |  THE SOLUTION: Replace card numbers with tokens immediately.         |
  |                                                                      |
  |  +-------------------------------------------------------------------+
  |  |  Without Tokenization (BAD)      With Tokenization (GOOD)        |
  |  +-----------------------------------+------------------------------+
  |  |                                   |                              |
  |  |  Browser -> API Gateway           |  Browser -> Stripe.js       |
  |  |  card: 4111111111111111           |  card: 4111111111111111     |
  |  |                                   |       |                     |
  |  |  API Gateway -> Payment Svc       |       v                     |
  |  |  card: 4111111111111111           |  Stripe Vault (PCI L1)     |
  |  |  (now payment svc is in PCI       |  token: tok_1234           |
  |  |   scope!)                         |       |                     |
  |  |                                   |       v                     |
  |  |  Payment Svc -> Database          |  Browser -> API Gateway    |
  |  |  card: 4111111111111111           |  token: tok_1234           |
  |  |  (now DB is in PCI scope!)        |  (card number NEVER        |
  |  |                                   |   touches your servers!)    |
  |  |  PCI scope: EVERYTHING            |                              |
  |  |  Audit cost: $500K/year           |  API Gateway -> Payment Svc |
  |  |                                   |  token: tok_1234            |
  |  |                                   |  (payment svc NEVER sees    |
  |  |                                   |   card number)              |
  |  |                                   |                              |
  |  |                                   |  PCI scope: just Stripe.js  |
  |  |                                   |  Audit cost: SAQ-A ($5K)    |
  |  +-----------------------------------+------------------------------+
  |                                                                      |
  |  ENCRYPTION LAYERS:                                                  |
  |  +---------------------------------------------------------------+  |
  |  | Layer              | Mechanism        | Standard              |  |
  |  +--------------------+------------------+-----------------------+  |
  |  | Browser to Server  | TLS 1.3          | PCI-DSS Req. 4       |  |
  |  | Service to Service | mTLS             | PCI-DSS Req. 4       |  |
  |  | Data at rest (DB)  | AES-256          | PCI-DSS Req. 3       |  |
  |  | Vault storage      | HSM-backed keys  | PCI-DSS Req. 3       |  |
  |  | Log files          | Mask card data   | PCI-DSS Req. 3       |  |
  |  |                    | (show last 4)    |                       |  |
  |  +--------------------+------------------+-----------------------+  |
  |                                                                      |
  +----------------------------------------------------------------------+
```

---

## 6. Double-Entry Bookkeeping Systems

### How Financial Ledgers Work

```
  +----------------------------------------------------------------------+
  |              DOUBLE-ENTRY BOOKKEEPING                                  |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  RULE: Every transaction has TWO entries that MUST balance.          |
  |        Total Debits = Total Credits (always)                         |
  |                                                                      |
  |  PAYMENT: Customer pays $100 to Merchant                             |
  |  +---------------------------------------------------------------+  |
  |  | Entry    | Account          | Debit    | Credit   |           |  |
  |  +----------+------------------+----------+----------+-----------+  |
  |  | 1        | Customer Wallet  | $100.00  |          | money out |  |
  |  | 2        | Merchant Account |          | $100.00  | money in  |  |
  |  +----------+------------------+----------+----------+-----------+  |
  |  | TOTAL    |                  | $100.00  | $100.00  | BALANCED  |  |
  |  +---------------------------------------------------------------+  |
  |                                                                      |
  |  REFUND: Merchant refunds $30 to Customer                            |
  |  +---------------------------------------------------------------+  |
  |  | Entry    | Account          | Debit    | Credit   |           |  |
  |  +----------+------------------+----------+----------+-----------+  |
  |  | 3        | Merchant Account | $30.00   |          | money out |  |
  |  | 4        | Customer Wallet  |          | $30.00   | money in  |  |
  |  +----------+------------------+----------+----------+-----------+  |
  |  | TOTAL    |                  | $30.00   | $30.00   | BALANCED  |  |
  |  +---------------------------------------------------------------+  |
  |                                                                      |
  |  PLATFORM FEE: Platform takes 2% of $100 = $2                       |
  |  +---------------------------------------------------------------+  |
  |  | Entry    | Account          | Debit    | Credit   |           |  |
  |  +----------+------------------+----------+----------+-----------+  |
  |  | 1        | Customer Wallet  | $100.00  |          |           |  |
  |  | 2        | Platform Revenue |          | $2.00    | 2% fee    |  |
  |  | 3        | Merchant Account |          | $98.00   | net amount|  |
  |  +----------+------------------+----------+----------+-----------+  |
  |  | TOTAL    |                  | $100.00  | $100.00  | BALANCED  |  |
  |  +---------------------------------------------------------------+  |
  |                                                                      |
  |  WHY DOUBLE-ENTRY:                                                   |
  |  - Invariant: SUM(debits) = SUM(credits) at ALL times               |
  |  - If they don't balance: BUG or FRAUD                               |
  |  - Audit: every dollar is traceable from source to destination       |
  |  - Regulatory: required for financial services (SOX, PCI)            |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Implementation in Java

```java
public class LedgerService {
    private final LedgerRepository ledgerRepo;
    private final AccountRepository accountRepo;

    public void recordPayment(Payment payment) {
        String paymentId = payment.getPaymentId();
        BigDecimal amount = payment.getAmount();

        // (1) Create DEBIT entry (money leaves customer)
        LedgerEntry debit = new LedgerEntry(
            UUID.randomUUID().toString(),
            paymentId,
            payment.getCustomerAccountId(),
            EntryType.DEBIT,
            amount,
            Instant.now()
        );

        // (2) Create CREDIT entry (money enters merchant)
        LedgerEntry credit = new LedgerEntry(
            UUID.randomUUID().toString(),
            paymentId,
            payment.getMerchantAccountId(),
            EntryType.CREDIT,
            amount,
            Instant.now()
        );

        // (3) In production: all within ONE database transaction
        // BEGIN TRANSACTION
        ledgerRepo.save(debit);
        ledgerRepo.save(credit);
        accountRepo.debit(payment.getCustomerAccountId(), amount);
        accountRepo.credit(payment.getMerchantAccountId(), amount);
        // COMMIT

        // If any step fails, ALL roll back (ACID atomicity).
        // Books ALWAYS balance.
    }
}
```

---

## 7. Webhook Delivery: Reliability Patterns

### Webhook Delivery Architecture

```
  +----------------------------------------------------------------------+
  |              WEBHOOK DELIVERY RELIABILITY                              |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  CHALLENGE: Merchant servers may be down, slow, or buggy.            |
  |  We MUST deliver payment events reliably despite failures.           |
  |                                                                      |
  |  Payment        Webhook          Redis             Merchant          |
  |  Service        Service          (Retry Queue)     Server            |
  |     |              |                |                 |              |
  |     | (1) payment  |                |                 |              |
  |     |  authorized  |                |                 |              |
  |     |------------->|                |                 |              |
  |     |              |                |                 |              |
  |     |              | (2) POST       |                 |              |
  |     |              |  /webhook      |                 |              |
  |     |              |--------------------------------->|              |
  |     |              |                |                 |              |
  |     |              |  [TIMEOUT or 5xx]                |              |
  |     |              |<---------------------------------|              |
  |     |              |                |                 |              |
  |     |              | (3) ZADD       |                 |              |
  |     |              |  webhook:retry |                 |              |
  |     |              |  {next_ts}     |                 |              |
  |     |              |  {delivery_id} |                 |              |
  |     |              |--------------->|                 |              |
  |     |              |                |                 |              |
  |     |              |                |                 |              |
  |     |  [Retry worker runs every 10 seconds]          |              |
  |     |              |                |                 |              |
  |     |              | (4) ZRANGEBYSCORE               |              |
  |     |              |  0 to NOW()    |                 |              |
  |     |              |--------------->|                 |              |
  |     |              |  [delivery_id] |                 |              |
  |     |              |<---------------|                 |              |
  |     |              |                |                 |              |
  |     |              | (5) POST       |                 |              |
  |     |              |  /webhook      |                 |              |
  |     |              |  (retry #1)    |                 |              |
  |     |              |--------------------------------->|              |
  |     |              |                |                 |              |
  |     |              |  200 OK        |                 |              |
  |     |              |<---------------------------------|              |
  |     |              |                |                 |              |
  |     |              | (6) ZREM       |                 |              |
  |     |              |  webhook:retry |                 |              |
  |     |              |  {delivery_id} |                 |              |
  |     |              |--------------->|                 |              |
  |                                                                      |
  |  RETRY SCHEDULE (Exponential Backoff):                               |
  |  +---------------------------------------------------------------+  |
  |  | Attempt | Delay After Failure | Cumulative Wait               |  |
  |  +---------+--------------------+-------------------------------+  |
  |  | 1       | Immediate          | 0                             |  |
  |  | 2       | 1 minute           | 1 min                        |  |
  |  | 3       | 5 minutes          | 6 min                        |  |
  |  | 4       | 30 minutes         | 36 min                       |  |
  |  | 5       | 2 hours            | 2h 36min                     |  |
  |  | 6       | 8 hours            | 10h 36min                    |  |
  |  | 7       | 24 hours           | 34h 36min                    |  |
  |  | 8       | GIVE UP            | Alert merchant dashboard     |  |
  |  +---------+--------------------+-------------------------------+  |
  |                                                                      |
  |  WEBHOOK SECURITY:                                                   |
  |  - HMAC-SHA256 signature in header (X-Webhook-Signature)             |
  |  - Merchant verifies signature using shared secret                   |
  |  - Prevents spoofed webhook events from attackers                    |
  |  - Timestamp in payload to prevent replay attacks                    |
  |                                                                      |
  +----------------------------------------------------------------------+
```

---

## 8. Our Java Simulation vs Production

### Mapping Table

```
  +----------------------------------------------------------------------+
  |              OUR SIMULATION vs PRODUCTION                              |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  +------------------------------+----------------------------------+ |
  |  | Our Simulation               | Production Equivalent             | |
  |  +------------------------------+----------------------------------+ |
  |  | ConcurrentHashMap            | PostgreSQL (payments, ledger,     | |
  |  | (InMemoryPaymentRepo, etc.)  |  accounts)                       | |
  |  +------------------------------+----------------------------------+ |
  |  | ConcurrentHashMap.putIfAbsent| Redis SET NX EX 86400            | |
  |  | (InMemoryIdempotencyRepo)    | (idempotency cache)              | |
  |  +------------------------------+----------------------------------+ |
  |  | ArrayList<PaymentEventListener>| Kafka topics                   | |
  |  | (Observer pattern)           | (payment.authorized, etc.)       | |
  |  +------------------------------+----------------------------------+ |
  |  | System.out.println           | ELK Stack (Elasticsearch,        | |
  |  | (audit logging)              |  Logstash, Kibana)               | |
  |  +------------------------------+----------------------------------+ |
  |  | synchronized blocks          | PostgreSQL row-level locks       | |
  |  | (thread safety)              | + Redis distributed locks        | |
  |  +------------------------------+----------------------------------+ |
  |  | AppConfig (Factory)          | Spring Boot @Configuration       | |
  |  |                              | + @Bean methods                  | |
  |  +------------------------------+----------------------------------+ |
  |  | PaymentProcessor interface   | Stripe SDK / Razorpay SDK /      | |
  |  | (CreditCard, UPI, Wallet)    |  PayPal SDK                     | |
  |  +------------------------------+----------------------------------+ |
  |  | CurrencyService (hardcoded)  | Open Exchange Rates API          | |
  |  |                              | + Redis cache (5-min TTL)        | |
  |  +------------------------------+----------------------------------+ |
  |  | WebhookService (direct call) | Kafka -> SQS -> HTTP POST       | |
  |  |                              | + exponential backoff retry      | |
  |  +------------------------------+----------------------------------+ |
  |  | String token = "tok_" +      | AWS KMS / HashiCorp Vault       | |
  |  |  UUID (simulated)            | (HSM-backed tokenization)       | |
  |  +------------------------------+----------------------------------+ |
  |                                                                      |
  |  THE POINT: Same design patterns, same interfaces, same separation   |
  |  of concerns. The patterns don't change when you swap infrastructure.|
  |  That's the whole value of Repository + Strategy + Factory.          |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Production Architecture Diagram

```
  +----------------------------------------------------------------------+
  |                    PRODUCTION PAYMENT STACK                            |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  +--------+    +-----------+    +-----------+    +-----------+       |
  |  | Mobile  |    | Web       |    | Merchant  |    | Webhook   |       |
  |  | App     |    | Checkout  |    | Dashboard |    | Receiver  |       |
  |  +----+----+    +-----+-----+    +-----+-----+    +-----+-----+     |
  |       |              |                |                  |            |
  |       v              v                v                  ^            |
  |  +----+--------------------------------------------+    |            |
  |  |              API Gateway (Kong/AWS ALB)          |    |            |
  |  |  - Rate limiting                                 |    |            |
  |  |  - Authentication (API key / JWT)                |    |            |
  |  |  - TLS termination                              |    |            |
  |  +-----+------------------------------------------+    |            |
  |        |                                                |            |
  |        v                                                |            |
  |  +-----+------------------------------------------+    |            |
  |  |           Payment Service (Spring Boot)          |    |            |
  |  |  - Facade: orchestrates all subsystems           |    |            |
  |  |  - Strategy: selects payment processor           |    |            |
  |  |  - State machine: payment lifecycle              |    |            |
  |  +-----+------+------+------+------+-----------+   |    |            |
  |        |      |      |      |      |           |   |    |            |
  |        v      v      v      v      v           v   |    |            |
  |  +----+ +----+ +----+ +----+ +--------+ +-----+-+ |    |            |
  |  |Redis| |PG  | |PG  | |PG  | |Stripe  | |Webhook| |    |            |
  |  |idem | |pay | |ldgr| |acct| |Razorpay| |Svc    |-|--->|            |
  |  |cache| |ment| |    | |    | |NPCI    | |(Kafka) | |                |
  |  +-----+ +----+ +----+ +----+ +--------+ +--------+ |               |
  |                                                       |               |
  |  +---------------------------------------------------+               |
  |  |              Kafka Event Bus                       |               |
  |  |  payment.initiated | payment.authorized |          |               |
  |  |  payment.captured  | payment.declined   |          |               |
  |  +---------------------------------------------------+               |
  |                                                                      |
  +----------------------------------------------------------------------+
```

---

## Interview Q&A

### Q1: "Why PostgreSQL and not DynamoDB for the ledger?"

> "Double-entry bookkeeping requires debit and credit entries to be atomic -- if one fails, both must rollback. PostgreSQL's ACID transactions guarantee this. DynamoDB has no cross-partition transactions. You'd need a saga or outbox pattern, adding complexity and losing atomicity. For a ledger, ACID is non-negotiable. Even Amazon uses Aurora (PostgreSQL-compatible) for payment data."

### Q2: "How do you use Redis in a payment system without violating consistency?"

> "Redis is used for three things, none of which are the source of truth. (1) Idempotency cache: SET NX for atomic check-and-set, but the final result is also in PostgreSQL. (2) Exchange rate cache: 5-minute TTL, stale rate acceptable for display but checkout uses the rate locked at authorization time. (3) Webhook retry queue: Redis sorted set for scheduling retries, but delivery status is in PostgreSQL. If Redis dies, we fall back to PostgreSQL for all three -- slower but correct."

### Q3: "Why Kafka and not RabbitMQ for payment events?"

> "Three reasons. (1) Ordering: Kafka guarantees order within a partition. We partition by payment_id, so all events for one payment are in order. RabbitMQ doesn't guarantee this without single-consumer queues. (2) Replay: Kafka retains events for days. If the ledger service was down, it can replay events to catch up. RabbitMQ deletes messages once consumed. (3) Multiple consumers: both LedgerService and WebhookService need the same 'payment.authorized' event. Kafka supports this natively via consumer groups."

### Q4: "Explain the card authorization flow in an interview."

> "Four parties: cardholder, merchant, acquirer (merchant's bank), and issuer (cardholder's bank), connected by the card network (Visa/MC). (1) Customer swipes card. (2) Merchant sends auth request to acquirer. (3) Acquirer forwards to card network. (4) Network routes to issuing bank. (5) Issuer checks balance, fraud, limits, and approves. (6) Auth code flows back the same path. Later, in batch settlement (T+2), funds actually move and interchange fees are deducted."
