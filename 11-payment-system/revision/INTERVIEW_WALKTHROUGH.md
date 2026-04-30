# Interview Walkthrough -- Payment System (Stripe/UPI)

> **Total time: ~35 minutes. The Idempotency & Exactly-Once deep dive is 50% of this interview.**
> This problem tests idempotent payment processing (preventing double-charges), double-entry ledger accounting (every payment = balanced debit/credit), payment lifecycle state machines, fraud detection pipelines, webhook delivery with retries, reconciliation, and PCI-DSS compliance. This is the ultimate "money path" system design problem -- correctness is non-negotiable.

---

## Phase 1: Clarify Requirements (2-3 min)

### Questions to Ask

- "What's the scale? 1M transactions/month or 1B? This determines whether we need a distributed system or a single DB can handle the ledger."
- "Do we need two-step payments (authorize then capture) or single-step (charge immediately)? This affects the payment lifecycle."
- "What payment methods? Credit cards only, or also debit, ACH, UPI, wallets? Each has different settlement timelines."
- "Do we need to send webhooks to merchants? What's the delivery guarantee -- at-least-once or exactly-once?"
- "Is PCI-DSS compliance in scope? That fundamentally changes how we handle card data (tokenization, HSM, network isolation)."
- "Multi-currency? If so, when do we lock the exchange rate -- at authorization or settlement?"

### Clarified Scope

```
In scope:   Payment initiation, idempotency, authorization, capture,
            double-entry ledger, fraud detection, webhook delivery,
            reconciliation, refunds, settlement, merchant payouts
Out of scope: Merchant onboarding/KYC, dispute/chargeback management,
              subscription/recurring billing, physical POS terminals,
              regulatory reporting (mention only)
```

### What This Signals

You understand this is a **financial correctness problem** where the hard part is **preventing double-charges and maintaining an auditable ledger** (not CRUD). You're probing for the consistency and compliance requirements that drive the architecture.

**Common follow-up:** "Why does the two-step (auth/capture) question matter?"

**Answer:** "It changes the payment lifecycle entirely. In a single-step flow, money moves immediately -- simpler but no cancel window. In two-step, authorization holds funds on the card for up to 7 days, and capture charges later. This is critical for e-commerce (ship first, capture on shipment) and ride-sharing (authorize estimated fare, capture actual fare). The ledger entries are different too -- auth creates a hold entry, capture converts it to a charge."

---

## Phase 2: High-Level Architecture (5-7 min)

### What to Say

> "I'll design this as a payment processing pipeline with five core services: Payment Service (orchestrates the flow), Ledger Service (double-entry bookkeeping), Fraud Service (rule + ML chain), Webhook Service (merchant notifications), and Reconciliation Service (daily bank matching). The Payment Service enforces idempotency at two layers -- Redis cache for speed, PostgreSQL unique constraint as source of truth. Every operation writes balanced ledger entries (debit + credit = 0). The system is CP -- consistency is non-negotiable for money."

### Draw This Diagram

```
                  +---------------------------+
                  |      Merchant Server      |
                  |  POST /v1/payments        |
                  |  X-Idempotency-Key: uuid  |
                  +------------+--------------+
                               |
              1. HTTPS + API key + HMAC signature
                               |
                  +------------v--------------+
                  |   WAF + API Gateway        |
                  |  Rate limit per merchant  |
                  |  Auth: API key validation  |
                  +------------+--------------+
                               |
              2. Validated request -> Payment Service
                               |
                  +------------v--------------+
                  |    Payment Service (ECS)   |
                  |                            |
                  | 3. Idempotency check:      |
                  |    Redis GET key           |
                  |    -> HIT: return cached   |
                  |    -> MISS: continue       |
                  |                            |
                  | 4. Fraud check:            |
                  |    -> FraudService.evaluate |
                  |                            |
                  | 5. Authorize + Capture:    |
                  |    -> Bank/Card Network    |
                  |                            |
                  | 6. Ledger entries:         |
                  |    -> LedgerService.record |
                  |                            |
                  | 7. Cache + respond:        |
                  |    Redis SET key=response  |
                  |    Return HTTP 200         |
                  +------+-----+--------------+
                         |     |
            +------------+     +------------+
            |                               |
            v                               v
    +---------------+              +----------------+
    | Aurora PG     |              | ElastiCache    |
    | (Ledger +     |              | Redis          |
    |  Payments +   |              | (Idempotency   |
    |  Idempotency) |              |  cache, rate   |
    +---------------+              |  limiting)     |
                                   +----------------+
                  |
     8. Publish "payment.captured" -> SNS
              |            |           |
              v            v           v
        +-----------+ +-----------+ +------------+
        | SQS:      | | SQS:      | | SQS:       |
        | Webhook   | | Reconcil. | | Analytics  |
        | Queue     | | Queue     | | Queue      |
        +-----------+ +-----------+ +------------+
              |
              v
    +-------------------+
    | Webhook Service   |
    | (ECS)             |
    | 9. POST to        |
    |    merchant URL   |
    |    + HMAC sign    |
    |    Retry: 1s,2s,  |
    |    4s,8s,16s      |
    +-------------------+

    10. Daily: Reconciliation Service (Step Functions + Lambda)
        Match internal ledger vs bank statement CSV
        Report: matched / mismatched / missing

    11. Daily: Settlement batch (Step Functions)
        Group captured payments by bank
        Submit to acquiring bank for settlement
        Ledger: DEBIT merchant_balance, CREDIT merchant_settled
```

### Components to Name

| Component | Role | CAP |
|-----------|------|-----|
| Payment Service | Orchestrates payment flow: idempotency -> fraud -> authorize -> capture -> ledger. State machine for lifecycle. | CP (double-charging is catastrophic) |
| Ledger Service | Double-entry bookkeeping. Every operation = balanced debit + credit entries. Append-only, immutable. Sum = 0. | CP (financial record of truth) |
| Fraud Detection Service | Chain of responsibility: velocity -> amount -> geo -> ML risk score. Approve / 3D Secure / decline. | CP (false negative = fraud loss) |
| Webhook Service | Delivers payment events to merchants. HMAC-SHA256 signature. Exponential backoff retry. DLQ for failures. | AP (delayed delivery acceptable, missed delivery not) |
| Reconciliation Service | Daily batch: match ledger entries vs bank statement. Find missing/mismatched transactions. | CP (must be 100% accurate) |
| Idempotency Layer | Two-layer: Redis (sub-ms, TTL 24h) + DB unique constraint (source of truth). Prevents double-processing. | CP (the whole point is consistency) |

### What This Signals

You lead with **idempotency** (the #1 concern for payments) and **double-entry ledger** (the #1 data model for financial systems). You don't treat this as a generic CRUD API -- you understand the financial domain.

**Common follow-up:** "Why PostgreSQL instead of DynamoDB for the ledger?"

**Answer:** "The ledger needs ACID transactions -- when I record a payment, I must atomically insert the payment record, the debit entry, and the credit entry in a single transaction. If any one fails, none should persist. PostgreSQL gives me that with BEGIN/COMMIT. DynamoDB transactions are limited to 25 items and don't support the SUM aggregate queries I need for balance validation. Also, for reconciliation I need complex joins between ledger entries and bank statements -- relational queries are natural in SQL, awkward in DynamoDB."

---

## Phase 3: Idempotency & Exactly-Once (8-10 min)

**This is the star of the interview. Spend the most time here.**

### Part A: Why Idempotency? The Double-Charge Problem

> "In a payment system, the network between the client and server is unreliable. The client sends a payment request, the server processes it and charges the card, but the response is lost due to a timeout. The client doesn't know if the payment succeeded, so it retries. Without idempotency, the card gets charged twice. Idempotency guarantees that processing the same request multiple times produces the same result as processing it once."

```
THE DOUBLE-CHARGE PROBLEM:

  Merchant Server                Payment System              Bank
       |                              |                        |
       |--- POST /v1/payments ------->|                        |
       |    { amount: 4999,           |--- authorize $49.99 -->|
       |      idempotency_key:        |<-- AUTH-567 (success) -|
       |      "uuid-12345" }          |                        |
       |                              |--- capture $49.99 ---->|
       |                              |<-- CAP-890 (success) --|
       |                              |                        |
       |<-- HTTP 200 (response) ------| <-- NETWORK TIMEOUT!   |
       |    *** RESPONSE LOST ***     |    (response never     |
       |                              |     reaches merchant)  |
       |                              |                        |
       |--- POST /v1/payments ------->|  <-- MERCHANT RETRIES  |
       |    { amount: 4999,           |                        |
       |      idempotency_key:        |                        |
       |      "uuid-12345" }          |                        |
       |                              |                        |

  WITHOUT IDEMPOTENCY:
    Server processes again -> charges card AGAIN -> customer charged $99.98!

  WITH IDEMPOTENCY:
    Server checks: "uuid-12345 already processed?"
    YES -> return cached response from first attempt
    Customer charged exactly $49.99. Merchant gets same response.
```

### Part B: Two-Layer Idempotency Implementation

> "I implement idempotency at two layers. Layer 1: Redis cache for speed -- sub-millisecond lookup, 99% of retries caught here. Layer 2: PostgreSQL unique constraint on idempotency_key -- survives Redis failures and restarts, is the authoritative source of truth. The DB constraint is the safety net; Redis is the performance optimization."

```
TWO-LAYER IDEMPOTENCY (Numbered):

  Merchant sends: POST /v1/payments
  Headers: X-Idempotency-Key: uuid-12345
  Body: { amount: 4999, currency: "USD" }
      |
      v
  1. LAYER 1: Redis check (sub-ms)
     GET idempotency:uuid-12345
     |
     +-- HIT: return cached response immediately
     |        (no DB query, no bank call, sub-ms response)
     |        This handles 99% of retries.
     |
     +-- MISS: continue to step 2
     |
     v
  2. LAYER 2: DB check (1-5ms)
     SELECT * FROM payments WHERE idempotency_key = 'uuid-12345';
     |
     +-- FOUND: return stored response
     |          (Redis was evicted or restarted, but DB has the record)
     |          Also re-populate Redis: SET idempotency:uuid-12345 = response
     |
     +-- NOT FOUND: this is a genuinely new request. Continue to step 3.
     |
     v
  3. FRAUD CHECK
     Evaluate payment through fraud pipeline
     (velocity, amount, geo, ML -- see Phase 5)
     |
     +-- DECLINED: store result in DB + Redis, return 402
     +-- APPROVED: continue to step 4
     |
     v
  4. AUTHORIZE (hold funds)
     Call bank: "Hold $49.99 on card ending 4242"
     Bank returns: { auth_code: "AUTH-567", status: "AUTHORIZED" }
     |
     v
  5. CAPTURE (charge the card)
     Call bank: "Capture $49.99 against AUTH-567"
     Bank returns: { capture_ref: "CAP-890", status: "CAPTURED" }
     |
     v
  6. PERSIST (single ACID transaction):
     BEGIN;
       INSERT INTO payments (id, idempotency_key, amount, status, auth_code, ...)
         VALUES ('pay_001', 'uuid-12345', 4999, 'CAPTURED', 'AUTH-567', ...);
       INSERT INTO ledger (payment_id, account, type, amount)
         VALUES ('pay_001', 'customer_hold', 'DEBIT', 4999);
       INSERT INTO ledger (payment_id, account, type, amount)
         VALUES ('pay_001', 'merchant_balance', 'CREDIT', 4999);
     COMMIT;

     The UNIQUE constraint on idempotency_key means if two threads
     reach this point simultaneously, only ONE succeeds.
     The other gets a unique violation -> return first result.
     |
     v
  7. CACHE RESULT
     Redis: SET idempotency:uuid-12345 = { paymentId: "pay_001", status: "CAPTURED" }
     TTL: 24 hours (idempotency keys expire after 24h)
     |
     v
  8. RESPOND
     HTTP 200 { "id": "pay_001", "status": "captured", "amount": 4999 }
     |
     v
  9. ASYNC: Publish event, deliver webhook (non-blocking)
```

### Part C: The Crash-Between-Bank-And-DB Problem

> "The hardest edge case: what if we charge the card (step 5) but crash before persisting to the database (step 6)? The customer is charged, but we have no record."

```
THE CRASH SCENARIO:

  Payment Service             Bank              Database
       |                       |                    |
       |-- authorize $49.99 -->|                    |
       |<-- AUTH-567 (ok) -----|                    |
       |                       |                    |
       |-- capture $49.99 ---->|                    |
       |<-- CAP-890 (ok) -----|                    |
       |                       |                    |
       |--- INSERT payment ... |      ** CRASH **   |
       |        X--------------X                    |
       |   (process dies before DB commit)          |
       |                                            |
       | Customer is charged $49.99                 |
       | But no payment record exists in our DB!    |

  SOLUTION 1: Bank-side idempotency (primary defense)
    Pass OUR idempotency key to the bank:
      Bank.authorize(amount, card, bankIdempotencyKey="uuid-12345")
    When we restart and merchant retries:
      - Our DB: no record (crash lost it)
      - Our Redis: no record (crash lost it)
      - We call bank again with same bankIdempotencyKey
      - Bank says: "Already processed, here's the original result"
      - We persist to our DB, cache in Redis, return to merchant
    Net result: customer charged exactly once.

  SOLUTION 2: Reconciliation (secondary defense)
    Daily job compares our ledger vs bank statement.
    Bank statement shows CAP-890 for $49.99.
    Our ledger has no matching entry.
    Action: back-fill the payment record from bank data.
    This catches anything Solution 1 misses.

  SOLUTION 3: Transactional outbox (prevent event loss)
    Instead of: (1) write DB, (2) publish event
    Do: (1) write DB + outbox event in same transaction
        (2) separate poller reads outbox, publishes event
    Guarantees: if payment is in DB, event will be published.
```

### Part D: Concurrent Request Race Condition

```
SCENARIO: Two identical requests arrive at the same millisecond.
          Both pass Redis check (MISS), both reach the DB.

  Thread A                              Thread B
     |                                      |
     | Redis GET uuid-12345: MISS           | Redis GET uuid-12345: MISS
     |                                      |
     | DB SELECT: NOT FOUND                 | DB SELECT: NOT FOUND
     |                                      |
     | Fraud check: PASS                    | Fraud check: PASS
     |                                      |
     | Bank authorize: SUCCESS (AUTH-567)   | Bank authorize: SUCCESS (AUTH-568)
     |   (two different auths!)             |   (PROBLEM: two charges!)
     |                                      |
     | BEGIN; INSERT payment                | BEGIN; INSERT payment
     |   (idempotency_key='uuid-12345')    |   (idempotency_key='uuid-12345')
     | COMMIT; -> SUCCESS                   | COMMIT; -> UNIQUE VIOLATION!
     |                                      |
     |                                      | Thread B catches UniqueViolation:
     |                                      |   1. Void AUTH-568 (release hold)
     |                                      |   2. SELECT result from Thread A
     |                                      |   3. Return Thread A's result
     |                                      |
     | Both threads return same response.   |
     | Customer charged once. AUTH-568 voided.

  KEY INSIGHT: The DB unique constraint is the LAST LINE OF DEFENSE.
  Even if two threads both pass Redis and both call the bank,
  only one thread's INSERT succeeds. The other voids its auth
  and returns the first thread's result.

  This is why we need DB unique constraint AND bank-side void/reversal.
  Redis alone is not sufficient for correctness.
```

**Common follow-up:** "What if the void of AUTH-568 fails?"

**Answer:** "The void is retried with exponential backoff. If it keeps failing, the auth hold expires naturally in 7 days (bank auto-releases). We also catch it in daily reconciliation -- our ledger shows one payment, the bank shows two auths. The reconciliation job flags it and an automated process voids the orphaned auth. In the absolute worst case, the customer sees a temporary hold for 7 days that auto-releases. Annoying, but not a double-charge."

---

## Phase 4: Ledger & Reconciliation (5-7 min)

### Part A: Double-Entry Ledger -- Why Append-Only

> "Every payment operation creates exactly two ledger entries: a debit and a credit for the same amount. The sum of all entries across all accounts must always equal zero. This is the same principle banks have used for 500 years. The key property: entries are append-only. I never UPDATE or DELETE a row. A refund doesn't delete the original charge -- it creates new reverse entries. This gives me a complete, immutable audit trail and the ability to reconstruct the system state at any point in time."

```
DOUBLE-ENTRY LEDGER: Payment Lifecycle

  === AUTHORIZATION (hold funds) ===
  Customer buys $49.99 item:

    | entry_id | payment_id | account             | type   | amount | created_at          |
    |----------|-----------|---------------------|--------|--------|---------------------|
    | E001     | pay_001   | customer_funds_held | DEBIT  | 4999   | 2026-04-26 10:30:00 |
    | E002     | pay_001   | auth_pending        | CREDIT | 4999   | 2026-04-26 10:30:00 |

    Sum check: +4999 + (-4999) = 0  (BALANCED)

  === CAPTURE (charge the card) ===
  Convert hold to actual charge:

    | E003     | pay_001   | auth_pending        | DEBIT  | 4999   | 2026-04-26 10:30:05 |
    | E004     | pay_001   | merchant_balance    | CREDIT | 4999   | 2026-04-26 10:30:05 |

    Sum check: +4999 -4999 +4999 -4999 = 0  (BALANCED)
    auth_pending net = -4999 + 4999 = 0  (hold released)
    merchant_balance net = -4999  (merchant owed $49.99)

  === SETTLEMENT (T+1, bank transfers funds) ===
  Bank settles captured payments:

    | E005     | pay_001   | merchant_balance    | DEBIT  | 4999   | 2026-04-27 06:00:00 |
    | E006     | pay_001   | merchant_settled    | CREDIT | 4999   | 2026-04-27 06:00:00 |

    merchant_balance net = -4999 + 4999 = 0  (balance cleared)
    merchant_settled net = -4999  (ready for payout)

  === PAYOUT (merchant withdrawal) ===
  Platform fee: 2.9% + $0.30 = $1.75

    | E007     | pay_001   | merchant_settled    | DEBIT  | 4999   | 2026-04-28 12:00:00 |
    | E008     | pay_001   | platform_revenue    | CREDIT | 175    | 2026-04-28 12:00:00 |
    | E009     | pay_001   | merchant_bank_acct  | CREDIT | 4824   | 2026-04-28 12:00:00 |

    Sum of ALL entries (E001-E009): 0  (ALWAYS BALANCED)

  === REFUND (reverse the charge) ===
  Customer requests refund:

    | E010     | pay_001   | merchant_balance    | DEBIT  | 4999   | 2026-04-30 09:00:00 |
    | E011     | pay_001   | customer_refund     | CREDIT | 4999   | 2026-04-30 09:00:00 |

    Note: we do NOT delete E001-E004. We add NEW entries.
    The original charge and the refund are both in the ledger.
    Complete audit trail preserved.

WHY APPEND-ONLY?
  1. Audit trail: regulators can see every state the system was in
  2. Debugging: reconstruct balance at any point in time
     "What was merchant M001's balance at 3:47 PM yesterday?"
     SELECT SUM(CASE WHEN type='CREDIT' THEN -amount ELSE amount END)
     FROM ledger WHERE account='merchant_balance' AND created_at <= '...'
  3. No accidental data loss: can't UPDATE a wrong amount (would be hidden)
  4. Reconciliation: compare full entry history vs bank records
  5. Compliance: PCI-DSS and SOX require immutable financial records
```

### Part B: Why the Sum Must Always Equal Zero

```
BALANCE VALIDATION:

  Every N minutes, run:
    SELECT SUM(CASE WHEN type='DEBIT' THEN amount ELSE -amount END) FROM ledger;

  Result MUST be 0. If not, something is broken:
    - A bug created a debit without a matching credit
    - A partial transaction committed (should be impossible with ACID)
    - Data corruption

  This is the financial system's "unit test."
  If balance != 0, HALT all processing and alert.

  In practice, also validate per-account:
    SELECT account,
           SUM(CASE WHEN type='DEBIT' THEN amount ELSE -amount END) as balance
    FROM ledger
    GROUP BY account;

  Expected:
    customer_funds_held:  0 (all holds released or captured)
    auth_pending:         0 (all auths captured or voided)
    merchant_balance:     negative (money owed to merchants)
    merchant_settled:     negative (money ready for payout)
    platform_revenue:     negative (our revenue, accumulated)
    merchant_bank_acct:   negative (money transferred out)

  Grand total: always 0.
```

### Part C: Reconciliation Deep Dive

> "Reconciliation is the financial system's integration test. Every day, I compare our internal ledger against the bank's statement. Every transaction in our ledger should have a matching row in the bank statement, and vice versa. Discrepancies mean something went wrong -- a crash, a bug, a fraud, or a bank error."

```
DAILY RECONCILIATION FLOW (Numbered):

    1. Step Functions triggers at 2:00 AM (after T+1 settlement window)
    |
    v
    2. Download bank statement (CSV/SFTP):
       Bank provides: { reference, amount, date, status }
       for all transactions settled in the previous 24 hours
    |
    v
    3. Pull internal ledger entries for same 24-hour window:
       SELECT payment_id, amount, status, auth_code, capture_ref
       FROM payments WHERE captured_at BETWEEN '...' AND '...'
    |
    v
    4. MATCH: Join on capture_ref (our record) = reference (bank record)
       |
       +-- MATCHED (99.9% of transactions):
       |     Our amount = bank amount? YES -> green
       |     Our amount != bank amount? -> AMOUNT MISMATCH (alert)
       |
       +-- MISSING IN BANK (we have record, bank doesn't):
       |     Possible causes:
       |       - Settlement delayed (wait for next day)
       |       - Bank rejected the capture (refund customer)
       |       - Our record is wrong (investigate)
       |     Action: auto-retry next day, escalate if still missing
       |
       +-- MISSING IN LEDGER (bank has record, we don't):
             Possible causes:
               - Crash between bank charge and DB persist (Phase 3C)
               - Bug in our system
             Action: back-fill payment record from bank data
                     (create payment + ledger entries to match bank)
    |
    v
    5. Generate reconciliation report:
       {
         date: "2026-04-25",
         total_transactions: 33000000,
         matched: 32996500 (99.99%),
         amount_mismatch: 12,
         missing_in_bank: 2480,
         missing_in_ledger: 8,
         total_discrepancy: "$1,247.50"
       }
    |
    v
    6. Actions:
       - Amount mismatch: create adjustment ledger entries
       - Missing in bank: retry next day, escalate after 3 days
       - Missing in ledger: back-fill from bank data
       - Alert finance team if discrepancy > $10,000
```

**Common follow-up:** "What if reconciliation finds a systemic issue -- hundreds of mismatches?"

**Answer:** "That's a P0 incident. Immediately halt settlement (don't pay out money we're not sure about), alert the engineering and finance teams, and investigate. Common root causes: a deployment bug in the payment service (check deploy timeline vs mismatch start time), a bank API change (check bank error logs), or an infrastructure issue (check Aurora failover events). The reconciliation report has timestamps for every mismatch, so we can correlate with deploy/incident timelines."

---

## Phase 5: Scaling & Edge Cases (5-8 min)

### Fraud Detection Pipeline

> "Fraud detection is a chain of responsibility. Each check either declines the payment or passes it to the next check. Rules catch 80% of fraud (fast, cheap, explainable). ML catches the remaining 20% (novel patterns, but slower and more expensive)."

```
FRAUD DETECTION CHAIN (Numbered):

  Payment request arrives: $4,999, card ending 4242, IP: 45.33.12.99
      |
      1. VELOCITY CHECK (Redis counter)
         "Has this card been used 5+ times in the last hour?"
         Redis: INCR fraud:velocity:card_4242 (TTL 1 hour)
         |
         +-- count >= 5: DECLINE (velocity limit exceeded)
         +-- count < 5: PASS -> continue
      |
      v
      2. AMOUNT THRESHOLD CHECK
         "Is the amount above $10,000?"
         |
         +-- amount > 10000: DECLINE (requires manual review)
         +-- amount <= 10000: PASS -> continue
      |
      v
      3. GEOLOCATION CHECK
         "Does the IP country match the card issuer country?"
         IP 45.33.12.99 -> GeoIP lookup -> Russia
         Card issuer country -> United States
         |
         +-- mismatch: FLAG (pass with elevated risk)
         +-- match: PASS -> continue
      |
      v
      4. ML RISK SCORE (SageMaker real-time endpoint)
         Features: amount, velocity, geo_match, time_of_day,
                   merchant_category, card_age, historical_pattern
         Model returns: risk_score = 0.73
         |
         +-- score > 0.8: DECLINE (auto-reject)
         +-- score 0.5-0.8: 3D SECURE (step-up authentication)
         |     Customer must enter OTP from bank SMS
         |     If OTP valid: continue. If not: DECLINE.
         +-- score < 0.5: APPROVE (continue to authorization)
      |
      v
      5. RESULT: 3D_SECURE_REQUIRED
         (geo mismatch + $4,999 amount elevated risk to 0.73)
         Customer completes 3D Secure -> payment proceeds

  WHY CHAIN OF RESPONSIBILITY?
    - Rules (steps 1-3) are sub-ms, catch 80% of fraud, cost nothing
    - ML (step 4) is 10-50ms, catches remaining 20%, costs compute
    - If rules decline, we never invoke ML (save compute cost)
    - New rules added easily: just add a new link in the chain
    - Each rule is independently testable and explainable to regulators
```

### Webhook Delivery with Retry

```
WEBHOOK DELIVERY FLOW (Numbered):

  Payment captured -> SNS event -> SQS webhook queue
      |
      1. Webhook Service pulls from SQS:
         { event: "payment.captured", payment_id: "pay_001",
           merchant_id: "M001", amount: 4999, currency: "USD" }
      |
      v
      2. Build webhook payload:
         {
           "id": "evt_abc123",
           "type": "payment.captured",
           "data": {
             "payment_id": "pay_001",
             "amount": 4999,
             "currency": "USD",
             "status": "captured"
           },
           "created_at": "2026-04-26T10:30:00Z"
         }
      |
      v
      3. Sign payload with HMAC:
         signature = HMAC-SHA256(payload_json, merchant_webhook_secret)
         Header: X-Signature: sha256=abc123def456...
      |
      v
      4. POST to merchant webhook URL:
         POST https://acme.com/webhooks/payments
         Headers:
           Content-Type: application/json
           X-Signature: sha256=abc123def456...
           X-Webhook-Id: evt_abc123
           X-Timestamp: 1745658600
         Body: (payload from step 2)
      |
      v
      5. RETRY SCHEDULE (exponential backoff):
         |
         +-- Attempt 1 (T+0s):    FAILED (timeout)
         +-- Attempt 2 (T+1s):    FAILED (500 error)
         +-- Attempt 3 (T+3s):    FAILED (500 error)
         +-- Attempt 4 (T+7s):    FAILED (connection refused)
         +-- Attempt 5 (T+15s):   FAILED (timeout)
         |
         v
      6. ALL RETRIES EXHAUSTED:
         Move message to Dead Letter Queue (DLQ)
         Send email alert to merchant: "Webhook delivery failing"
         Merchant can manually poll: GET /v1/events?since=...
      |
      v
      7. MERCHANT VERIFICATION:
         Merchant receives webhook:
           1. Compute: expected = HMAC-SHA256(body, their_webhook_secret)
           2. Compare: expected == X-Signature header?
           3. Check timestamp: within 5 minutes? (prevent replay attacks)
           4. If valid: process event, return 200
           5. If invalid: ignore (possible tampering), return 401

  WHY HMAC AND NOT JWT?
    - HMAC is symmetric: only we and the merchant have the secret
    - Simpler than JWT (no token parsing, no expiry logic)
    - Standard: Stripe, GitHub, Shopify all use HMAC for webhooks
    - Merchant can verify in 1 line of code (compute hash, compare)
```

### Multi-Currency Support

```
MULTI-CURRENCY FLOW:

  Merchant in Japan charges customer in the US:
    - Customer pays in USD ($49.99)
    - Merchant receives in JPY

  1. Authorization (in customer's currency):
     Authorize $49.99 USD on customer's card

  2. Exchange rate lock (at authorization time):
     GET /exchange-rates?from=USD&to=JPY
     Rate: 1 USD = 155.50 JPY (cached in Redis, refreshed every 60s)
     Converted: $49.99 * 155.50 = 7,773.45 JPY

  3. Ledger entries (both currencies recorded):
     DEBIT  customer_hold        $49.99 USD
     CREDIT merchant_balance     7,773 JPY  (+ exchange rate metadata)

  4. Settlement:
     Bank settles in merchant's currency (JPY)
     FX difference handled by platform (we eat the spread or charge a fee)

  KEY DECISIONS:
    - Lock rate at auth time (not capture time): customer sees exact amount
    - Store amounts in smallest unit: 4999 cents, 777345 sen
    - Store original currency AND converted currency in ledger
    - Platform absorbs FX risk OR charges 1-2% FX fee (like Stripe)
    - Exchange rate source: ECB, Visa rates, or XE API
```

---

## Phase 6: Tradeoffs (3-5 min)

### CP vs AP: Why CP for Payments

| Aspect | CP (This Design) | AP (Alternative) |
|--------|-----------------|------------------|
| Consistency | Every read sees latest state | Reads may be stale |
| Availability | May reject requests during partition | Always accepts requests |
| Double-charge risk | Zero (DB unique constraint enforced) | Possible (stale cache could miss duplicate) |
| Latency | Higher (synchronous DB write) | Lower (async, eventual) |
| Best for | Payment ledger, idempotency | Merchant dashboard, analytics |

**Say:** "Payments are CP, non-negotiable. If there's a network partition between my app and the database, I reject the payment with a 503 rather than risk processing without the idempotency check. The merchant retries in a few seconds. A brief unavailability is far better than a double-charge. However, the merchant dashboard and analytics endpoints are AP -- showing a payment that settled 5 seconds ago as 'processing' is a minor UX issue, not a financial error."

### SQL vs NoSQL for the Ledger

| Aspect | SQL/PostgreSQL (This Design) | NoSQL/DynamoDB |
|--------|-------------------------------|----------------|
| ACID transactions | Native: BEGIN/COMMIT across payment + ledger | Limited: 25 items max, no cross-table joins |
| Aggregate queries | SUM, GROUP BY for balance validation | Scans required (expensive at scale) |
| Schema enforcement | Strict: amount NOT NULL, type IN ('DEBIT','CREDIT') | Flexible: schema errors possible |
| Reconciliation joins | Natural: JOIN ledger ON bank_statement | Awkward: application-level joins |
| Write throughput | ~50K TPS (Aurora) | ~100K+ TPS |
| Best for | Financial records, audit, compliance | High-throughput non-financial data |

**Say:** "I choose PostgreSQL for the ledger because financial records need ACID transactions (payment + debit + credit in one atomic commit), aggregate queries (SUM to validate balance = 0), and schema enforcement (the DB itself prevents a ledger entry without a type). DynamoDB's throughput advantage doesn't matter here -- 50K TPS on Aurora is more than enough for payment writes. Where I would use DynamoDB: merchant API key lookup, rate limiting counters, session data -- high-throughput, non-financial data."

### Synchronous vs Async Payment Processing

| Aspect | Synchronous (This Design) | Fully Async (Queue-Based) |
|--------|--------------------------|--------------------------|
| User experience | Instant response: "Payment captured" | "Payment queued, check status later" |
| Complexity | Simpler: request -> process -> respond | More complex: queue, workers, status polling |
| Failure handling | Return error immediately | Error discovered asynchronously (harder for merchant) |
| Throughput | Limited by bank API latency (~200ms) | Higher (decouple from bank latency) |
| Best for | Card payments (merchants expect instant) | ACH, bank transfers (inherently async, T+1) |

**Say:** "Card payments are synchronous -- merchants and customers expect an instant 'approved' or 'declined.' The entire flow (idempotency check, fraud check, authorize, capture, persist) completes in under 200ms. But settlement and payouts are inherently async -- they happen in daily batches via Step Functions. ACH and bank transfers are also async (T+1 to T+3). So the system is synchronous for the customer-facing path and async for the back-office path."

### Tokenization: Our HSM vs Third-Party (Stripe/Adyen)

| Aspect | Own CloudHSM | Third-Party Tokenizer |
|--------|-------------|----------------------|
| PCI scope | Large: we handle raw card data, full PCI-DSS audit | Small: card data never touches our servers |
| Control | Full: own keys, own tokenization logic | Limited: vendor controls token format |
| Cost | ~$3,200/month + PCI audit ($50K-$200K/year) | Per-transaction fee (~$0.01) |
| Compliance effort | 6-12 months to certify, annual re-audit | Inherit vendor's PCI certification |
| Best for | Payment platforms (Stripe itself, banks) | Merchants building on top of payment platforms |

**Say:** "If we're building a Stripe competitor, we own the HSM and handle card data directly -- that's the core product. If we're a merchant building payments, we use Stripe's tokenization and never touch card data. The PCI audit alone costs $50K-$200K annually and takes 6 months. Only take on that scope if payment processing IS the business."

---

## Red Flags (What NOT to Do)

- No idempotency key -- network retries cause double-charging
- Using floating point for money -- $0.1 + $0.2 != $0.3 in IEEE 754
- Mutable ledger (UPDATE/DELETE) -- destroys audit trail, compliance failure
- Single-entry bookkeeping (just a "balance" field) -- can't reconcile, can't audit
- No reconciliation -- "trust the code" is not a financial strategy
- Ignoring the crash-between-bank-and-DB scenario -- the #1 follow-up question
- Making the whole system AP -- "eventual consistency" for money is unacceptable
- No fraud checks -- shows you've never worked on a real payment system
- Webhooks without signatures -- merchants can't verify authenticity (spoofing risk)

## Green Flags (What Interviewers Want to Hear)

- Lead with idempotency: "The first thing I design is the idempotency layer"
- Two-layer idempotency: Redis for speed, DB unique constraint for correctness
- Double-entry ledger: every operation = debit + credit, sum = 0, append-only
- Payment lifecycle state machine: INITIATED -> AUTHORIZED -> CAPTURED -> SETTLED
- Explain the crash scenario and how bank-side idempotency + reconciliation solve it
- Fraud as chain of responsibility: rules (cheap, fast) then ML (expensive, catches novel)
- Webhooks with HMAC: "Merchants verify the signature before processing"
- Reconciliation: "This is the financial system's integration test"
- Integer cents for money: "Never floating point for financial amounts"
- CP for the money path, AP only for dashboards and analytics

---

## 30-Second Elevator Pitch

> "For a Stripe-scale payment system, I'd build an **idempotent payment pipeline** with a **two-layer check**: Redis cache for sub-ms dedup, PostgreSQL unique constraint as source of truth. Every payment writes to a **double-entry ledger** -- debit + credit entries that always sum to zero, append-only, immutable. The payment lifecycle follows a **state machine**: INITIATED -> AUTHORIZED -> CAPTURED -> SETTLED, with guards on every transition. **Fraud detection** is a chain of responsibility: velocity checks, amount thresholds, geo matching, and ML risk scoring -- rules catch 80%, ML catches 20%. **Webhooks** notify merchants with HMAC-SHA256 signatures and exponential backoff retry. Daily **reconciliation** matches our ledger against bank statements to catch any discrepancy. The system is **CP** -- consistency is non-negotiable for money. PCI compliance via **CloudHSM** for tokenization and an isolated VPC."

**Time: Under 30 seconds. Covers: Idempotency, ledger, state machine, fraud, webhooks, reconciliation, CP, PCI.**

---

## Phase-by-Phase Timing Cheat Sheet

```
Phase 1:  Clarify Requirements            2-3 min   (scale, auth/capture, payment methods, PCI)
Phase 2:  High-Level Architecture          5-7 min   (pipeline, CP, double-entry, components)
Phase 3:  Idempotency & Exactly-Once       8-10 min  (two-layer, crash scenario, race condition)
Phase 4:  Ledger & Reconciliation          5-7 min   (double-entry, append-only, daily matching)
Phase 5:  Scaling & Edge Cases             5-8 min   (fraud chain, webhooks, multi-currency)
Phase 6:  Tradeoffs Discussion             3-5 min   (CP vs AP, SQL vs NoSQL, sync vs async)
-----------------------------------------------------------------------------------
Total:                                     ~35 min
```

If short on time, shorten Phase 5 (scaling) and Phase 6 (tradeoffs). Never skip Phase 3 (idempotency deep dive) -- that's the core of the interview.
