# CAP Theorem & Distributed Tradeoffs in the Payment System (Stripe/UPI)

> Interview-ready reference for a Senior Java developer.
> A payment system is STRICTLY CP -- consistency is NON-NEGOTIABLE when real money is involved.
> Unlike e-commerce (split CAP), payments cannot afford eventual consistency for ANY component.

---

## Table of Contents

| Section | Key Point |
|---------|-----------|
| CP -- Consistency is Non-Negotiable | Why payments MUST choose CP over AP |
| Why AP is Dangerous | Double-charge, lost transactions, incorrect balances |
| ACID for Ledger | PostgreSQL, not NoSQL, for financial data |
| Exactly-Once Processing | Idempotency + DB transactions |
| Network Partition Handling | Queue payments, don't process until consistency restored |
| Industry Comparison | Stripe, PayPal, UPI architecture choices |
| PCI-DSS Implications | How compliance shapes architecture |
| PACELC Analysis | When no partition: latency vs consistency |
| Interview Q&A | Ready-to-use answers |

---

## CP -- Consistency is Non-Negotiable for Financial Systems

### The Core Argument

```
  +----------------------------------------------------------------------+
  |  THE KEY INSIGHT: A payment system does NOT have a CAP choice.        |
  |  It MUST be CP. There is no component where AP is acceptable.         |
  +----------------------------------------------------------------------+
  |                                                                      |
  |         Consistency (C)                                              |
  |            /\                                                        |
  |           /  \                                                       |
  |          / CP \                                                      |
  |         /      \     <--- Payment Processing (no double-charge)      |
  |        / LEDGER \    <--- Ledger (books MUST balance)                |
  |       / BALANCE  \   <--- Account Balance (no overdraft)             |
  |      / IDEMPOT.   \  <--- Idempotency (exactly-once)                |
  |     /______________\                                                 |
  |  Availability (A) --- Partition Tolerance (P)                        |
  |                                                                      |
  |  E-commerce can tolerate a stale product price for 5 seconds.        |
  |  A payment system CANNOT tolerate a stale balance for 1 millisecond. |
  |                                                                      |
  |  +------------------------------------------------------------------+|
  |  |  Component            | CAP  | Why                               ||
  |  +------------------------+------+----------------------------------+|
  |  | Payment Processing     | CP   | Double-charge = chargeback +     ||
  |  |                        |      | customer rage + regulatory fine  ||
  |  +------------------------+------+----------------------------------+|
  |  | Ledger (Double-Entry)  | CP   | Unbalanced books = audit failure ||
  |  |                        |      | + potential fraud undetected      ||
  |  +------------------------+------+----------------------------------+|
  |  | Account Balance        | CP   | Stale balance = overdraft or     ||
  |  |                        |      | insufficient funds not caught    ||
  |  +------------------------+------+----------------------------------+|
  |  | Idempotency Store      | CP   | Stale idem key = double-charge   ||
  |  +------------------------+------+----------------------------------+|
  |  | Merchant Config        | AP*  | Stale webhook URL is tolerable   ||
  |  |                        |      | (retry fixes it). ONLY exception.||
  |  +------------------------+------+----------------------------------+|
  |  | Exchange Rates         | AP*  | 5-second stale rate is OK for    ||
  |  |                        |      | display, but checkout MUST use   ||
  |  |                        |      | rate locked at authorization time||
  |  +------------------------+------+----------------------------------+|
  |                                                                      |
  |  * These are the ONLY components where AP is even considered,        |
  |    and even they have caveats.                                       |
  +----------------------------------------------------------------------+
```

### Why CP and Not AP

```
  +----------------------------------------------------------------------+
  |  THE COST OF GETTING IT WRONG                                         |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  IF YOU CHOOSE AP FOR PAYMENTS:                                      |
  |                                                                      |
  |  Scenario 1: Double-Charge                                           |
  |  +-----------------------------------------------------------------+ |
  |  | User pays $100. Network hiccup. Client retries.                 | |
  |  | AP system: "I'll accept both requests -- I'll reconcile later"  | |
  |  | Result: Customer charged $200. "Reconcile later" = lawsuit.     | |
  |  +-----------------------------------------------------------------+ |
  |                                                                      |
  |  Scenario 2: Lost Transaction                                        |
  |  +-----------------------------------------------------------------+ |
  |  | Partition between payment service and ledger service.            | |
  |  | AP system: "I'll accept the payment, record ledger later"       | |
  |  | Result: Money moved, no ledger entry. Auditor: "Where's $100?"  | |
  |  +-----------------------------------------------------------------+ |
  |                                                                      |
  |  Scenario 3: Incorrect Balance                                       |
  |  +-----------------------------------------------------------------+ |
  |  | Account balance cached (eventually consistent).                 | |
  |  | Balance shows $500 (stale). Actual balance: $50.                | |
  |  | System approves $400 payment. Account now -$350.                | |
  |  | Result: Who covers the $350 loss?                               | |
  |  +-----------------------------------------------------------------+ |
  |                                                                      |
  |  Scenario 4: Inconsistent Ledger                                     |
  |  +-----------------------------------------------------------------+ |
  |  | Debit recorded, credit not (partition during double-entry).     | |
  |  | AP system: "Credit will propagate eventually"                   | |
  |  | Result: Books don't balance. Regulatory audit failure.          | |
  |  |         Potential $M fines in regulated markets.                | |
  |  +-----------------------------------------------------------------+ |
  |                                                                      |
  |  THE HARD TRUTH:                                                     |
  |  "Eventually consistent" is unacceptable when the inconsistency     |
  |  window means someone loses real money.                              |
  +----------------------------------------------------------------------+
```

---

## ACID for Ledger: PostgreSQL, Not NoSQL

### Why Relational Databases for Financial Data

```
  +----------------------------------------------------------------------+
  |                    ACID vs BASE for Payments                          |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  ACID (PostgreSQL)                  BASE (DynamoDB/Cassandra)        |
  |  =================                 =========================         |
  |                                                                      |
  |  A - Atomicity                      BA - Basically Available         |
  |  "Debit AND credit happen           "System is usually up"           |
  |   together, or neither does"        (but may be inconsistent)        |
  |                                                                      |
  |  C - Consistency                    S - Soft state                   |
  |  "Books always balance.             "State may change without        |
  |   Sum(debits) = Sum(credits)"        input (propagation delay)"      |
  |                                                                      |
  |  I - Isolation                      E - Eventual consistency         |
  |  "Concurrent payments don't         "Reads may return stale data     |
  |   see each other's partial state"    until all replicas converge"    |
  |                                                                      |
  |  D - Durability                                                      |
  |  "Once committed, survives                                           |
  |   crash/restart"                                                     |
  |                                                                      |
  |  VERDICT: Financial systems REQUIRE ACID.                            |
  |           NoSQL "eventual consistency" = unbalanced books.            |
  +----------------------------------------------------------------------+
```

### Double-Entry Bookkeeping with ACID Transactions

```
  +----------------------------------------------------------------------+
  |           DOUBLE-ENTRY BOOKKEEPING IN A SINGLE TRANSACTION            |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Payment: Customer pays Merchant $100                                |
  |                                                                      |
  |  BEGIN TRANSACTION;                                                  |
  |                                                                      |
  |  -- Step 1: Debit the customer's account                            |
  |  INSERT INTO ledger_entries                                          |
  |    (entry_id, payment_id, account_id, type, amount, created_at)     |
  |  VALUES                                                              |
  |    ('LE-001', 'PAY-001', 'CUST-ACC', 'DEBIT', 100.00, NOW());      |
  |                                                                      |
  |  -- Step 2: Credit the merchant's account                           |
  |  INSERT INTO ledger_entries                                          |
  |    (entry_id, payment_id, account_id, type, amount, created_at)     |
  |  VALUES                                                              |
  |    ('LE-002', 'PAY-001', 'MERCH-ACC', 'CREDIT', 100.00, NOW());    |
  |                                                                      |
  |  -- Step 3: Update customer balance                                  |
  |  UPDATE accounts SET balance = balance - 100.00                     |
  |  WHERE account_id = 'CUST-ACC'                                      |
  |  AND balance >= 100.00;   -- prevents overdraft!                    |
  |                                                                      |
  |  -- Step 4: Update merchant balance                                  |
  |  UPDATE accounts SET balance = balance + 100.00                     |
  |  WHERE account_id = 'MERCH-ACC';                                    |
  |                                                                      |
  |  -- If ANY step fails, ALL steps roll back.                          |
  |  COMMIT;                                                             |
  |                                                                      |
  |  +--------------------+    +--------------------+                    |
  |  | Customer Account   |    | Merchant Account   |                    |
  |  | Before: $500       |    | Before: $1000      |                    |
  |  | After:  $400       |    | After:  $1100      |                    |
  |  +--------------------+    +--------------------+                    |
  |                                                                      |
  |  Ledger invariant: SUM(debits) = SUM(credits) = $100                 |
  |  This invariant is GUARANTEED by the ACID transaction.               |
  +----------------------------------------------------------------------+
```

### What Happens with NoSQL (The Horror)

```
  +----------------------------------------------------------------------+
  |  WHAT GOES WRONG WITH EVENTUAL CONSISTENCY FOR LEDGER                 |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  DynamoDB / Cassandra (eventually consistent):                       |
  |                                                                      |
  |  Time T1: Write DEBIT to Node A    --> succeeds                      |
  |  Time T1: Write CREDIT to Node B   --> network timeout!              |
  |                                                                      |
  |  State at T1:                                                        |
  |    Node A: DEBIT -$100 (recorded)                                    |
  |    Node B: CREDIT +$100 (MISSING)                                    |
  |                                                                      |
  |  SUM(debits) = $100, SUM(credits) = $0                               |
  |  BOOKS DON'T BALANCE.                                                |
  |                                                                      |
  |  "But it'll eventually converge!" -- In 5ms? 5 seconds? 5 minutes?  |
  |  During that window:                                                 |
  |    - Audit query returns wrong balances                              |
  |    - Another payment against the merchant may overdraw               |
  |    - Refund calculation uses stale total                             |
  |                                                                      |
  |  NoSQL cannot enforce: "IF debit insert fails, rollback credit"      |
  |  There is no cross-partition transaction in DynamoDB/Cassandra.       |
  +----------------------------------------------------------------------+
```

---

## Exactly-Once Payment Processing: Idempotency + DB Transactions

### The Three Delivery Guarantees

```
  +----------------------------------------------------------------------+
  |             DELIVERY GUARANTEES IN PAYMENT SYSTEMS                    |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  AT-MOST-ONCE (fire and forget):                                     |
  |  +---------------------------------------------------------------+  |
  |  | Send payment request once. If it fails, don't retry.          |  |
  |  | Result: Payment may be LOST. Customer paid, merchant didn't   |  |
  |  |         receive.                                               |  |
  |  | Acceptable for: logging, analytics. NOT for payments.         |  |
  |  +---------------------------------------------------------------+  |
  |                                                                      |
  |  AT-LEAST-ONCE (retry until success):                                |
  |  +---------------------------------------------------------------+  |
  |  | Send payment request. If no response, retry.                  |  |
  |  | Result: Payment may be DUPLICATED. Customer charged twice.    |  |
  |  | Acceptable for: notifications (double SMS is OK). NOT for     |  |
  |  |                 payments.                                      |  |
  |  +---------------------------------------------------------------+  |
  |                                                                      |
  |  EXACTLY-ONCE (idempotency + transactions):                          |
  |  +---------------------------------------------------------------+  |
  |  | Send payment with idempotency key. Service ensures:           |  |
  |  |   - First request: process and store result                   |  |
  |  |   - Retry: return stored result (no reprocessing)             |  |
  |  | Result: Payment processed EXACTLY ONCE regardless of retries. |  |
  |  | This is what payment systems MUST achieve.                    |  |
  |  +---------------------------------------------------------------+  |
  |                                                                      |
  |  HOW WE ACHIEVE EXACTLY-ONCE:                                        |
  |  1. Client sends idempotency key with every payment request          |
  |  2. Redis SET NX acquires a lock atomically                          |
  |  3. Payment processed within a DB transaction (ACID)                 |
  |  4. Result stored against the idempotency key                        |
  |  5. Retry with same key? Return cached result. No reprocessing.     |
  +----------------------------------------------------------------------+
```

### Idempotency + ACID: The Complete Flow

```
  CLIENT           API GATEWAY       PAYMENT SERVICE         REDIS         POSTGRESQL
    |                  |                   |                   |               |
    | (1) POST /pay    |                   |                   |               |
    |  idemKey: K1     |                   |                   |               |
    |  amount: $100    |                   |                   |               |
    |----------------->|                   |                   |               |
    |                  |                   |                   |               |
    |                  | (2) processPayment|                   |               |
    |                  |------------------>|                   |               |
    |                  |                   |                   |               |
    |                  |                   | (3) SET K1        |               |
    |                  |                   |  "PENDING" NX     |               |
    |                  |                   |  EX 86400         |               |
    |                  |                   |------------------>|               |
    |                  |                   |  OK (acquired)    |               |
    |                  |                   |<------------------|               |
    |                  |                   |                   |               |
    |                  |                   | (4) BEGIN TRANSACTION             |
    |                  |                   |-------------------------------------->|
    |                  |                   |                   |               |
    |                  |                   | (5) INSERT payment record         |
    |                  |                   |-------------------------------------->|
    |                  |                   |                   |               |
    |                  |                   | (6) INSERT debit ledger entry     |
    |                  |                   |-------------------------------------->|
    |                  |                   |                   |               |
    |                  |                   | (7) INSERT credit ledger entry    |
    |                  |                   |-------------------------------------->|
    |                  |                   |                   |               |
    |                  |                   | (8) UPDATE account balances       |
    |                  |                   |-------------------------------------->|
    |                  |                   |                   |               |
    |                  |                   | (9) COMMIT                        |
    |                  |                   |-------------------------------------->|
    |                  |                   |                   |               |
    |                  |                   | (10) SET K1       |               |
    |                  |                   |  "SUCCESS:AUTH-X" |               |
    |                  |                   |------------------>|               |
    |                  |                   |                   |               |
    |                  |  PaymentResult    |                   |               |
    |                  |  (SUCCESS)        |                   |               |
    |                  |<------------------|                   |               |
    |  200 OK          |                   |                   |               |
    |  (AUTH-X)        |                   |                   |               |
    |<-----------------|                   |                   |               |
```

### What If the Transaction Fails?

```
  +----------------------------------------------------------------------+
  |                  FAILURE SCENARIOS AND HANDLING                        |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  SCENARIO 1: DB transaction fails (e.g., insufficient balance)       |
  |  +---------------------------------------------------------------+  |
  |  | (1) Redis SET NX succeeds (lock acquired)                     |  |
  |  | (2) BEGIN TRANSACTION                                         |  |
  |  | (3) UPDATE balance WHERE balance >= 100 --> 0 rows affected   |  |
  |  | (4) ROLLBACK                                                  |  |
  |  | (5) Redis DEL K1 (release lock so client can retry)           |  |
  |  | (6) Return DECLINED (insufficient funds)                      |  |
  |  +---------------------------------------------------------------+  |
  |                                                                      |
  |  SCENARIO 2: Service crashes mid-transaction                         |
  |  +---------------------------------------------------------------+  |
  |  | (1) Redis SET NX succeeds (lock acquired, TTL 24h)            |  |
  |  | (2) BEGIN TRANSACTION                                         |  |
  |  | (3) INSERT payment record                                     |  |
  |  | (4) *CRASH* -- service dies                                   |  |
  |  |                                                               |  |
  |  | PostgreSQL: uncommitted transaction auto-rolls back.          |  |
  |  | Redis: key K1 exists with "PENDING" (TTL expires in 24h)      |  |
  |  |                                                               |  |
  |  | Client retries: sees K1 = PENDING.                            |  |
  |  | Options:                                                      |  |
  |  |   a) Wait and poll (K1 might resolve)                         |  |
  |  |   b) If PENDING for > 30s, assume crash, DEL K1, retry        |  |
  |  |                                                               |  |
  |  | Key insight: PostgreSQL guarantees no partial writes.          |  |
  |  | Either the full transaction committed, or nothing did.        |  |
  |  +---------------------------------------------------------------+  |
  |                                                                      |
  |  SCENARIO 3: Redis fails (cannot check idempotency)                  |
  |  +---------------------------------------------------------------+  |
  |  | (1) Redis SET NX --> CONNECTION REFUSED                       |  |
  |  | (2) FAIL FAST. Do NOT process without idempotency.            |  |
  |  | (3) Return 503 Service Unavailable                            |  |
  |  | (4) Client retries with backoff                               |  |
  |  |                                                               |  |
  |  | NEVER process a payment without idempotency check.            |  |
  |  | Better to be unavailable than to double-charge.               |  |
  |  | This is the CP choice in action.                              |  |
  |  +---------------------------------------------------------------+  |
  |                                                                      |
  +----------------------------------------------------------------------+
```

---

## Network Partition Handling: Queue, Don't Process

### What Happens During a Partition

```
  +----------------------------------------------------------------------+
  |             NETWORK PARTITION HANDLING STRATEGY                        |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  PARTITION: Payment service cannot reach PostgreSQL                   |
  |                                                                      |
  |  +-------------------+         X X X        +-------------------+    |
  |  | Payment Service   | -----X PARTITION X---| PostgreSQL        |    |
  |  | (can receive      |         X X X        | (ledger, balances)|    |
  |  |  API requests)    |                      +-------------------+    |
  |  +-------------------+                                               |
  |                                                                      |
  |  WRONG APPROACH (AP):                                                |
  |  +---------------------------------------------------------------+  |
  |  | "Accept the payment, write to local queue, reconcile later"   |  |
  |  |                                                               |  |
  |  | Problems:                                                     |  |
  |  | - Cannot check balance (might overdraft)                      |  |
  |  | - Cannot check idempotency (might double-charge)              |  |
  |  | - Cannot record ledger entry (books unbalanced)               |  |
  |  | - "Reconcile later" with money = lawsuits                     |  |
  |  +---------------------------------------------------------------+  |
  |                                                                      |
  |  CORRECT APPROACH (CP):                                              |
  |  +---------------------------------------------------------------+  |
  |  | (1) Detect partition (DB health check fails)                  |  |
  |  | (2) Stop accepting new payment PROCESSING                     |  |
  |  | (3) Queue incoming requests in Kafka (durable, ordered)       |  |
  |  | (4) Return 503 "Payment service temporarily unavailable"      |  |
  |  | (5) Client retries with exponential backoff                   |  |
  |  | (6) When partition heals: drain queue, process in order        |  |
  |  | (7) Idempotency keys prevent duplicates during drain          |  |
  |  |                                                               |  |
  |  | Result: Payments DELAYED but never INCORRECT.                 |  |
  |  | Users see "Please try again in a moment" (not double-charge). |  |
  |  +---------------------------------------------------------------+  |
  |                                                                      |
  |  PARTITION: Payment service cannot reach card network (Visa)         |
  |  +---------------------------------------------------------------+  |
  |  | (1) Authorization request to Visa times out                   |  |
  |  | (2) Payment stays in PROCESSING state                         |  |
  |  | (3) Return PENDING to client (not SUCCESS, not DECLINE)       |  |
  |  | (4) Background job retries authorization with backoff         |  |
  |  | (5) If still failing after 30 min: expire payment             |  |
  |  | (6) Webhook: "payment.expired" sent to merchant               |  |
  |  |                                                               |  |
  |  | Key: We NEVER tell the customer "payment successful"          |  |
  |  | until the card network confirms authorization.                |  |
  |  +---------------------------------------------------------------+  |
  |                                                                      |
  +----------------------------------------------------------------------+
```

---

## Industry Comparison: Stripe, PayPal, UPI

### Architecture Choices Side-by-Side

```
  +----------------------------------------------------------------------+
  |              INDUSTRY ARCHITECTURE COMPARISON                         |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  STRIPE:                                                             |
  |  +---------------------------------------------------------------+  |
  |  | - CP for payment processing (strong consistency)              |  |
  |  | - PostgreSQL for ledger (ACID transactions)                   |  |
  |  | - Redis for idempotency keys (SET NX with TTL)                |  |
  |  | - Idempotency-Key header on every API request                 |  |
  |  | - Two-phase: authorize first, capture separately              |  |
  |  | - Webhook delivery with exponential backoff retry             |  |
  |  | - PCI Level 1 compliance (tokenization, vault)                |  |
  |  | - Ruby (monolith) -> Go/Java (microservices) migration        |  |
  |  +---------------------------------------------------------------+  |
  |                                                                      |
  |  PAYPAL:                                                             |
  |  +---------------------------------------------------------------+  |
  |  | - CP for payment, AP for non-financial features               |  |
  |  | - Oracle DB for financial ledger (ACID)                       |  |
  |  | - Two-sided network: buyer protection + seller protection     |  |
  |  | - IPN (Instant Payment Notification) = webhook equivalent     |  |
  |  | - Holds and reserves for risk management                      |  |
  |  | - Dispute resolution built into state machine                 |  |
  |  | - PCI Level 1, SOX compliance for public company              |  |
  |  +---------------------------------------------------------------+  |
  |                                                                      |
  |  UPI (India's Unified Payments Interface):                           |
  |  +---------------------------------------------------------------+  |
  |  | - NPCI (National Payments Corporation of India) backbone       |  |
  |  | - CP with strict 30-second timeout per transaction            |  |
  |  | - Real-time gross settlement (no batch processing)            |  |
  |  | - Two-factor auth: device binding + UPI PIN                   |  |
  |  | - Collect flow: merchant requests, user approves on app       |  |
  |  | - Pay flow: user initiates push payment                       |  |
  |  | - Interoperability: any bank to any bank via VPA              |  |
  |  | - 10B+ transactions/month (2024)                              |  |
  |  | - If response not received in 30s: mark PENDING, resolve via  |  |
  |  |   reconciliation (not retry -- prevents double debit)         |  |
  |  +---------------------------------------------------------------+  |
  |                                                                      |
  |  COMMON THREAD: All three use CP for financial operations.           |
  |  None use eventual consistency for ledger or payment state.          |
  |  All use idempotency mechanisms to prevent double-processing.        |
  +----------------------------------------------------------------------+
```

### Authorize vs Capture: Two-Phase Payment

```
  +----------------------------------------------------------------------+
  |            AUTHORIZE THEN CAPTURE (Stripe/PayPal Model)               |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  WHY TWO PHASES?                                                     |
  |  - Authorization: "Can this card/account pay $100?" (reserve funds)  |
  |  - Capture: "Actually charge $100" (move funds)                      |
  |  - Gap: Merchant ships product, THEN captures payment               |
  |  - If product out of stock: VOID authorization (no charge)           |
  |                                                                      |
  |  Customer       Merchant        Payment System       Card Network    |
  |     |              |                 |                     |         |
  |     | (1) Buy      |                 |                     |         |
  |     |  product     |                 |                     |         |
  |     |------------->|                 |                     |         |
  |     |              |                 |                     |         |
  |     |              | (2) Authorize   |                     |         |
  |     |              |  $100           |                     |         |
  |     |              |---------------->|                     |         |
  |     |              |                 | (3) Auth request    |         |
  |     |              |                 |-------------------->|         |
  |     |              |                 |  AUTH-OK, hold $100 |         |
  |     |              |                 |<--------------------|         |
  |     |              |  AUTHORIZED     |                     |         |
  |     |              |<----------------|                     |         |
  |     |              |                 |                     |         |
  |     |  [2 days later: merchant ships product]              |         |
  |     |              |                 |                     |         |
  |     |              | (4) Capture     |                     |         |
  |     |              |  $100           |                     |         |
  |     |              |---------------->|                     |         |
  |     |              |                 | (5) Capture request |         |
  |     |              |                 |-------------------->|         |
  |     |              |                 |  CAPTURED, $100     |         |
  |     |              |                 |  moved              |         |
  |     |              |                 |<--------------------|         |
  |     |              |  CAPTURED       |                     |         |
  |     |              |<----------------|                     |         |
  |     |              |                 |                     |         |
  |  vs UPI: Single-phase (authorize + capture in one step)    |         |
  |  UPI does NOT support auth-then-capture.                   |         |
  |  UPI is immediate debit -- money moves instantly.          |         |
  +----------------------------------------------------------------------+
```

---

## PCI-DSS Compliance Implications on Architecture

### How PCI-DSS Shapes System Design

```
  +----------------------------------------------------------------------+
  |              PCI-DSS COMPLIANCE AND ARCHITECTURE                      |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  PCI-DSS = Payment Card Industry Data Security Standard              |
  |  12 requirements that FUNDAMENTALLY change how you architect.        |
  |                                                                      |
  |  +---------------------------------------------------------------+  |
  |  | REQUIREMENT          | ARCHITECTURAL IMPACT                   |  |
  |  +----------------------+----------------------------------------+  |
  |  | Encrypt cardholder   | Card numbers NEVER stored in plaintext.|  |
  |  | data at rest         | Tokenization: replace card with token. |  |
  |  |                      | Only the vault has real card numbers.   |  |
  |  +----------------------+----------------------------------------+  |
  |  | Encrypt data in      | TLS 1.2+ everywhere. No exceptions.    |  |
  |  | transit              | Internal service-to-service = mTLS.    |  |
  |  +----------------------+----------------------------------------+  |
  |  | Restrict access to   | Microservice isolation: only payment   |  |
  |  | cardholder data      | service can access the vault.          |  |
  |  |                      | No other service sees card numbers.    |  |
  |  +----------------------+----------------------------------------+  |
  |  | Track and monitor    | Every access to payment data logged.   |  |
  |  | all access           | Audit trail = immutable append-only.   |  |
  |  +----------------------+----------------------------------------+  |
  |  | Regularly test       | Penetration testing, vulnerability     |  |
  |  | security             | scanning on every release.             |  |
  |  +----------------------+----------------------------------------+  |
  |                                                                      |
  |  TOKENIZATION ARCHITECTURE:                                          |
  |                                                                      |
  |  Client             API Gateway         Payment Service      Vault   |
  |    |                    |                     |                |     |
  |    | card: 4111...1111  |                     |                |     |
  |    |------------------->|                     |                |     |
  |    |                    | (1) tokenize        |                |     |
  |    |                    | card: 4111...1111   |                |     |
  |    |                    |-------------------------------------------->|
  |    |                    |  token: tok_abc123  |                |     |
  |    |                    |<--------------------------------------------|
  |    |                    |                     |                |     |
  |    |                    | (2) process payment |                |     |
  |    |                    | token: tok_abc123   |                |     |
  |    |                    |-------------------->|                |     |
  |    |                    |                     |                |     |
  |    |                    |                     | (3) detokenize |     |
  |    |                    |                     | (for gateway)  |     |
  |    |                    |                     |--------------->|     |
  |    |                    |                     | card:4111...1111    |
  |    |                    |                     |<---------------|     |
  |    |                    |                     |                |     |
  |    |                    |                     | (4) charge via |     |
  |    |                    |                     |  card network  |     |
  |    |                    |                     |                |     |
  |                                                                      |
  |  KEY INSIGHT: The payment service NEVER stores the real card number. |
  |  It works with tokens. Only the PCI-certified vault holds card data. |
  |  This massively reduces PCI scope -- fewer systems to audit.         |
  +----------------------------------------------------------------------+
```

---

## PACELC Analysis: When No Partition

```
  +----------------------------------------------------------------------+
  |                       PACELC ANALYSIS                                 |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  PACELC: If Partition, choose A or C.                                |
  |          Else (no partition), choose Latency or Consistency.          |
  |                                                                      |
  |  +---------------------------------------------------------------+  |
  |  |  Component         | Partition? | No Partition?               |  |
  |  +--------------------+------------+-----------------------------+  |
  |  | Payment Processing | PC         | EC (consistency over        |  |
  |  |                    | (refuse,   |  latency -- synchronous     |  |
  |  |                    |  queue)    |  DB write before response)  |  |
  |  +--------------------+------------+-----------------------------+  |
  |  | Ledger             | PC         | EC (double-entry MUST be    |  |
  |  |                    | (fail)     |  consistent before commit)  |  |
  |  +--------------------+------------+-----------------------------+  |
  |  | Idempotency        | PC         | EC (check MUST be           |  |
  |  |                    | (fail)     |  consistent or double-charge)|  |
  |  +--------------------+------------+-----------------------------+  |
  |  | Webhook Delivery   | PA         | EL (fire-and-forget, async; |  |
  |  |                    | (queue for |  latency matters more than  |  |
  |  |                    |  later)    |  guaranteed order)          |  |
  |  +--------------------+------------+-----------------------------+  |
  |  | Exchange Rates     | PA         | EL (cached, 5-min stale OK) |  |
  |  +--------------------+------------+-----------------------------+  |
  |                                                                      |
  |  SUMMARY: PC/EC for all financial components.                        |
  |           PA/EL only for non-financial (webhooks, exchange rates).   |
  +----------------------------------------------------------------------+
```

---

## Interview Q&A

### Q1: "Why not use DynamoDB for the payment ledger?"

> "DynamoDB is BASE, not ACID. A payment ledger requires double-entry bookkeeping where debit and credit MUST be atomic -- if the debit succeeds but the credit fails, the books don't balance. PostgreSQL's ACID transactions guarantee both entries commit or neither does. DynamoDB has no cross-partition transactions -- you can't atomically update two items in different partition keys. For a ledger, this is a dealbreaker."

### Q2: "How do you handle exactly-once payment processing?"

> "Three layers: (1) Client sends an idempotency key with every request. (2) Redis SET NX atomically checks-and-locks the key. If it exists, we return the cached result. (3) The actual payment processing happens inside a PostgreSQL transaction -- debit, credit, and balance update are atomic. If the transaction fails, we release the Redis lock. If the service crashes, PostgreSQL auto-rolls back uncommitted transactions, and the Redis key expires after 24h."

### Q3: "What happens during a network partition between your service and the database?"

> "We choose consistency over availability. We stop processing payments and return 503. Incoming requests are queued in Kafka for ordered replay when the partition heals. We NEVER process a payment without being able to record it in the ledger and check idempotency. Better to be temporarily unavailable than to double-charge customers or create unbalanced ledger entries."

### Q4: "Why is CP the right choice? Amazon's DynamoDB paper argues for AP."

> "DynamoDB was designed for Amazon's shopping cart, where losing an item is annoying but not catastrophic -- the user re-adds it. Payment is fundamentally different. A lost payment means someone loses real money. A double-charge means regulatory fines and customer lawsuits. The cost of inconsistency in payments is orders of magnitude higher than in a shopping cart. Even Amazon uses PostgreSQL (Aurora) for its payment service, not DynamoDB."

### Q5: "How does UPI differ from Stripe in its consistency model?"

> "Both are CP, but with different mechanisms. Stripe uses authorize-then-capture (two-phase): hold funds, then move them later. UPI is single-phase: money moves instantly on authorization. UPI has a hard 30-second timeout -- if no response in 30s, the transaction is marked PENDING and resolved via end-of-day reconciliation between banks. Stripe allows capture up to 7 days after authorization. Both guarantee exactly-once via idempotency, but UPI's real-time nature requires NPCI's central switch to be CP with extremely low latency."

### Q6: "How does PCI-DSS affect your architecture?"

> "PCI-DSS forces tokenization: real card numbers only live in a certified vault. The payment service works with tokens, massively reducing PCI scope. Only the vault needs Level 1 certification. All data encrypted at rest (AES-256) and in transit (TLS 1.2+). Internal communication uses mTLS. Every access to payment data is audited in an immutable log. This means more services, more encryption overhead, more latency -- but it's non-negotiable for handling card data."
