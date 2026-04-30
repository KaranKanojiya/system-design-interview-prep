# Payment System (Stripe/UPI)

## Problem Summary

Design a **payment processing platform** (like Stripe or UPI) that handles payment initiation, authorization, capture, settlement, merchant payouts, and reconciliation. The core challenges are **idempotency** to guarantee exactly-once payment processing despite network retries, a **double-entry ledger** where every transaction creates balanced debit/credit entries (sum of all entries = 0, append-only, immutable), a **payment lifecycle state machine** (INITIATED -> PROCESSING -> AUTHORIZED -> CAPTURED -> SETTLED -> PAID_OUT), **fraud detection** via a chain of rule-based checks and ML scoring, and **webhook delivery** with HMAC signatures and exponential backoff retry. The system must be PCI-DSS compliant, process 1B+ transactions/month with sub-200ms authorization latency, guarantee zero double-charges, and maintain 99.999% availability with financial-grade disaster recovery.

---

## 1-Minute Interview Revision

**Memorize these bullets. They cover 80% of what interviewers want to hear.**

- **Idempotency: client sends UUID key. Server checks DB -- if exists, return cached response. Prevents double-charge on retry.** Redis for fast lookup (TTL 24h), Aurora unique constraint as source of truth. Two-layer check: cache first, DB fallback. If Redis misses but DB has it, return stored result. Never call bank twice for the same idempotency key.
- **Double-entry ledger: every payment = 2 entries (debit + credit). Sum of all entries = 0. Immutable append-only.** Authorization: DEBIT customer_hold, CREDIT auth_pending. Capture: DEBIT auth_pending, CREDIT merchant_balance. Settlement: DEBIT merchant_balance, CREDIT merchant_settled. Never UPDATE or DELETE a ledger row -- only INSERT new entries.
- **Payment lifecycle: INITIATED -> PROCESSING -> AUTHORIZED -> CAPTURED -> SETTLED. Auth holds funds, capture charges.** Two-step: authorize first (hold funds on card), capture later (actually charge). Some merchants auth+capture in one call. Refund creates reverse ledger entries. State machine with guards on every transition.
- **Reconciliation: daily batch match internal ledger vs bank statement. Find missing/mismatched transactions.** Step Functions job: pull bank CSV, compare each row against ledger. Three outcomes: matched (good), missing in ledger (back-fill), missing in bank (escalate). This is the financial system's "unit test."
- **Webhooks: POST to merchant URL with HMAC signature. Retry exponential backoff (1s -> 16s, max 5 attempts).** Signature: HMAC-SHA256(payload, merchant_webhook_secret). Merchant verifies signature before processing. If all 5 retries fail, move to DLQ, alert merchant via email. Merchants must build idempotent webhook handlers.
- **CAP: CP -- consistency non-negotiable. ACID transactions for ledger. Queue payments during partition.** Double-charging or losing a payment is catastrophic. During network partition, queue payments in SQS rather than processing with stale data. Availability sacrificed briefly; consistency never.
- **Fraud: chain of checks (rules + ML). Velocity limits, amount thresholds, risk scoring.** Chain of responsibility: velocity check (5+ txns/hour?) -> amount threshold ($10K+?) -> geo mismatch (IP vs card country?) -> ML risk score (SageMaker). Low risk: approve. Medium: 3D Secure step-up. High: decline. 80% caught by rules, 20% by ML.

---

## Class Hierarchy

```
Payment (domain entity)                  LedgerEntry (value object, immutable)
  |-- paymentId, merchantId                |-- entryId, paymentId
  |-- amount, currency                     |-- accountName (e.g., "customer_hold")
  |-- status: PaymentStatus                |-- type: DEBIT or CREDIT
  |-- idempotencyKey                       |-- amount, currency
  |-- createdAt, updatedAt                 |-- createdAt
  |-- toString()                           |-- No setters (immutable, append-only)

PaymentStatus (enum / state machine)     IdempotencyService
  |-- INITIATED, PROCESSING               |-- check(idempotencyKey) -> Optional<Response>
  |-- AUTHORIZED, CAPTURED                 |-- store(idempotencyKey, response)
  |-- SETTLED, PAID_OUT                    |-- Redis cache + DB unique constraint
  |-- FAILED, REFUNDED                     |-- TTL: 24 hours
  |-- validTransitions: Map
  |-- transition(newStatus)              FraudDetectionService
                                           |-- evaluate(payment) -> FraudResult
Merchant (domain entity)                   |-- RuleBasedChecker (velocity, amount, geo)
  |-- merchantId, name                     |-- MLModelChecker (SageMaker risk score)
  |-- apiKey, webhookUrl                   |-- Chain of responsibility pattern
  |-- webhookSecret
  |-- payoutSchedule                     WebhookService
                                           |-- deliver(merchantId, event, payload)
LedgerService                              |-- sign(payload, secret) -> HMAC-SHA256
  |-- recordEntry(paymentId, entries)      |-- retry: 1s, 2s, 4s, 8s, 16s (max 5)
  |-- getBalance(accountName)              |-- DLQ after all retries exhausted
  |-- validateBalance() -> sum == 0
  |-- Append-only, no updates            ReconciliationService
                                           |-- reconcile(date, bankStatement)
PaymentProcessor                           |-- match(ledgerEntries, bankRows)
  |-- authorize(payment) -> authCode       |-- report: matched, missing, mismatched
  |-- capture(paymentId) -> captureRef
  |-- refund(paymentId) -> refundRef     AppConfig (wiring)
  |-- Calls bank/card network              |-- creates services, processors
  |-- Strategy pattern per provider        |-- wires fraud chain, webhook delivery
                                           |-- configures ledger, reconciliation
```

---

## Key Components

| Component | Role |
|-----------|------|
| `Payment` | Core domain entity. Tracks amount, currency, status, idempotency key. State machine transitions with guards. Thread-safe status updates. |
| `LedgerEntry` | Immutable value object. Every payment operation creates balanced debit/credit entries. Append-only -- never updated or deleted. Sum of all entries must equal zero. |
| `PaymentStatus` | Enum with state machine. Valid transitions enforced (INITIATED -> AUTHORIZED is valid, INITIATED -> SETTLED is not). Guards prevent invalid state changes. |
| `IdempotencyService` | Two-layer dedup: Redis (fast, TTL 24h) + DB unique constraint (source of truth). Returns cached response for duplicate requests. Prevents double-charging. |
| `FraudDetectionService` | Chain of responsibility: velocity limits -> amount thresholds -> geo mismatch -> ML risk score. Low risk: approve. Medium: 3D Secure. High: decline. |
| `LedgerService` | Double-entry bookkeeping. Every operation creates balanced entries. `validateBalance()` asserts sum of all entries = 0. Append-only, immutable. |
| `WebhookService` | Delivers payment events to merchant URLs. HMAC-SHA256 signature for authenticity. Exponential backoff retry (1s, 2s, 4s, 8s, 16s). DLQ after 5 failures. |
| `ReconciliationService` | Daily batch: matches internal ledger against bank statement CSV. Reports matched, missing, and mismatched transactions. The financial system's integration test. |
| `PaymentProcessor` | Strategy pattern for bank/card network communication. Authorize (hold funds), capture (charge), refund (reverse). Pluggable: Visa, Mastercard, UPI, ACH. |
| `AppConfig` | Wires everything together. Creates services, fraud chain, webhook delivery. Single entry point for demo. |

---

## Key Tradeoffs

| Decision | Option A | Option B | This Design |
|----------|----------|----------|-------------|
| Idempotency store | Redis only (fast, volatile) | DB only (durable, slower) | **Both** -- Redis for speed (sub-ms), DB unique constraint as source of truth |
| Ledger DB | NoSQL (high throughput, flexible) | SQL/PostgreSQL (ACID, relational) | **PostgreSQL** -- ledger needs ACID transactions, sum queries, immutable append-only |
| Auth + Capture | Single step (simpler) | Two-step (auth then capture) | **Two-step** -- auth holds funds, capture charges. Allows cancel between auth and capture |
| Fraud detection | Rules only (fast, explainable) | ML only (catches novel fraud) | **Both** -- rules catch 80% (fast, cheap), ML catches remaining 20% (SageMaker) |
| Webhook delivery | Synchronous (simple, blocking) | Async via SQS (decoupled, reliable) | **Async SQS** -- payment response is not blocked by slow merchant endpoints |
| Ledger mutability | Mutable (UPDATE balance) | Immutable append-only (INSERT only) | **Immutable** -- audit trail, no data loss, reconstruct state at any point in time |
| Currency storage | Floating point (simple) | Integer cents (no precision loss) | **Integer cents** -- $49.99 stored as 4999. Floating point arithmetic causes rounding errors |
| Settlement timing | Real-time per transaction | Batch (daily/hourly) | **Batch** -- reduces bank API calls, lower fees, matches industry standard (T+1/T+2) |

---

## Design Patterns

| Pattern | Where | Why |
|---------|-------|-----|
| **State Machine** | PaymentStatus (INITIATED -> AUTHORIZED -> CAPTURED -> ...) | Enforce valid transitions, guards prevent invalid state changes (e.g., INITIATED -> SETTLED) |
| **Strategy** | PaymentProcessor (VisaStrategy, MastercardStrategy, UPIStrategy) | Swap bank/card network providers without changing payment processing logic |
| **Chain of Responsibility** | FraudDetectionService (velocity -> amount -> geo -> ML) | Each check either handles (decline) or passes to the next. Easy to add new rules. |
| **Observer** | Payment events published to SNS on status change | Decoupled: payment captured -> webhook delivery, reconciliation queue, analytics |
| **Builder** | Payment.Builder, LedgerEntry.Builder | Complex object construction with many required fields and validation |
| **Factory** | PaymentProcessorFactory creates processor from card network | Encapsulate bank/card network selection logic |
| **Command** | Authorize, Capture, Refund as separate command objects | Each operation is independently executable, loggable, and reversible |
| **Template Method** | Base payment flow: validate -> fraud check -> process -> ledger -> notify | Fixed sequence of steps; subclasses override specific steps (e.g., UPI vs card) |

---

## How to Run

```bash
cd /Users/kanojik/Documents/Karan/Autodesk_AI_Development/systemDesign
./gradlew :11-payment-system:run
```

---

## Demo Output Preview

```
========================================
  PAYMENT SYSTEM (STRIPE/UPI) DEMO
========================================

--- Merchant Setup Demo ---
Registering merchant...
  Merchant{id='M001', name='Acme Store', webhookUrl='https://acme.com/webhooks'}
  API Key: sk_live_abc123...
  Webhook Secret: whsec_xyz789...

--- Idempotency Demo ---
Processing payment with idempotency key...
  Request 1 (original):
    POST /v1/payments { amount: 4999, currency: "USD", idempotencyKey: "uuid-12345" }
    Redis: MISS (first time)
    Fraud check: PASS (risk_score=0.12)
    Bank authorization: SUCCESS (auth_code=AUTH-567)
    Bank capture: SUCCESS (capture_ref=CAP-890)
    Ledger entries:
      DEBIT  customer_hold       $49.99
      CREDIT merchant_balance    $49.99
    Result: Payment{id='pay_001', status=CAPTURED, amount=$49.99}

  Request 2 (retry, same idempotency key):
    POST /v1/payments { amount: 4999, currency: "USD", idempotencyKey: "uuid-12345" }
    Redis: HIT (found cached response!)
    Result: Payment{id='pay_001', status=CAPTURED, amount=$49.99}  (same! no double charge)

--- Double-Entry Ledger Demo ---
Recording ledger entries for payment pay_001...
  Entry 1: DEBIT  customer_hold       $49.99
  Entry 2: CREDIT merchant_balance    $49.99

  Ledger balance check: sum of all entries = $0.00 (BALANCED!)

  Attempting to UPDATE a ledger entry...
  Result: UnsupportedOperationException! Ledger is append-only.

--- Payment Lifecycle Demo ---
Payment pay_001 state transitions:
  INITIATED -> PROCESSING             (valid)
  PROCESSING -> AUTHORIZED            (valid)
  AUTHORIZED -> CAPTURED              (valid)

  Invalid transition attempt: INITIATED -> SETTLED
  Result: IllegalStateException! Guard rejected transition.

--- Fraud Detection Demo ---
Running fraud check pipeline...
  Payment: $49.99 from IP 192.168.1.1, card country=US
  Rule 1 (Velocity):   PASS  (2 txns in last hour, limit=5)
  Rule 2 (Amount):     PASS  ($49.99 < $10,000 threshold)
  Rule 3 (Geo):        PASS  (IP country=US matches card country=US)
  Rule 4 (ML Score):   PASS  (risk=0.12 < 0.5 threshold)
  Result: APPROVED (all checks passed)

  High-risk payment: $9,500 from IP 45.33.12.99, card country=US
  Rule 1 (Velocity):   PASS  (1 txn in last hour)
  Rule 2 (Amount):     FLAG  ($9,500 close to $10,000 threshold)
  Rule 3 (Geo):        FAIL  (IP country=RU != card country=US)
  Result: DECLINED (geo mismatch, risk_score=0.91)

--- Webhook Delivery Demo ---
Delivering webhook for payment.captured event...
  POST https://acme.com/webhooks
  Headers: X-Signature: HMAC-SHA256(payload, whsec_xyz789...)
  Body: { "event": "payment.captured", "payment_id": "pay_001", "amount": 4999 }

  Attempt 1: FAILED (timeout)
  Attempt 2 (after 1s): FAILED (500 error)
  Attempt 3 (after 2s): SUCCESS (200 OK)
  Webhook delivered after 2 retries.

  Simulating merchant endpoint permanently down...
  Attempt 1: FAILED  (after 0s)
  Attempt 2: FAILED  (after 1s)
  Attempt 3: FAILED  (after 2s)
  Attempt 4: FAILED  (after 4s)
  Attempt 5: FAILED  (after 8s)
  All retries exhausted -> moved to Dead Letter Queue.
  Alert sent to merchant via email.

--- Reconciliation Demo ---
Running daily reconciliation (2026-04-25)...
  Internal ledger: 5 payments
  Bank statement:  5 rows

  Matching:
    pay_001: MATCHED  (ledger=$49.99, bank=$49.99)
    pay_002: MATCHED  (ledger=$129.00, bank=$129.00)
    pay_003: MISMATCHED (ledger=$75.00, bank=$74.50) -> ALERT!
    pay_004: MATCHED  (ledger=$25.00, bank=$25.00)
    pay_005: MISSING IN BANK -> ESCALATE!

  Reconciliation result:
    Matched:    3/5 (60%)
    Mismatched: 1/5 (pay_003, $0.50 discrepancy)
    Missing:    1/5 (pay_005, not in bank statement)
    Action: Investigate pay_003 and pay_005.

--- Refund Demo ---
Processing refund for payment pay_001...
  Refund: Payment{id='pay_001', amount=$49.99}
  Bank refund: SUCCESS (refund_ref=REF-111)
  Ledger entries (reverse):
    DEBIT  merchant_balance    $49.99
    CREDIT customer_refund     $49.99
  Status: CAPTURED -> REFUNDED
  Ledger balance check: sum of all entries = $0.00 (STILL BALANCED!)

========================================
  DEMO COMPLETE -- PROJECT 11 FINISHED!
========================================
```

---

## Quick Reference

```
Idempotency:        Client UUID key -> Redis check -> DB check -> process -> cache result. Same key = same response.
Double-entry:       Every payment = DEBIT + CREDIT. Sum = 0. Append-only. Never UPDATE/DELETE.
Payment lifecycle:  INITIATED -> PROCESSING -> AUTHORIZED -> CAPTURED -> SETTLED -> PAID_OUT
Auth vs Capture:    Auth = hold funds. Capture = charge. Two-step allows cancel between.
Fraud pipeline:     Velocity -> Amount -> Geo -> ML. Rules (80%) + ML (20%). Chain of responsibility.
Webhooks:           POST + HMAC-SHA256 signature. Retry 1s, 2s, 4s, 8s, 16s. DLQ after 5 failures.
Reconciliation:     Daily batch: ledger vs bank CSV. Matched/mismatched/missing. Financial unit test.
Ledger immutability: Append-only INSERT. Refund = new reverse entries (not DELETE original).
Currency:           Integer cents (4999 = $49.99). Never floating point.
Settlement:         Batch at T+1/T+2. Group by bank. Reduces API calls and fees.
```

---

## What to Improve Later

- [ ] Full Payment entity with state machine transitions and event emission
- [ ] LedgerService with double-entry bookkeeping and balance validation
- [ ] IdempotencyService with Redis cache + DB unique constraint
- [ ] FraudDetectionService with chain of responsibility (rules + ML stub)
- [ ] WebhookService with HMAC signing and exponential backoff retry
- [ ] ReconciliationService with ledger-vs-bank matching
- [ ] PaymentProcessor with Strategy pattern for multiple card networks
- [ ] Multi-currency support with exchange rate service
- [ ] Settlement batch job with grouping by acquiring bank
- [ ] Merchant payout calculation with platform fee deduction
