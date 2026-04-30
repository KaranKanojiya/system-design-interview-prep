# High-Level Design: Payment System (Stripe / UPI)

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
10. [Idempotency Deep Dive](#10-idempotency-deep-dive)
11. [Double-Entry Ledger](#11-double-entry-ledger)
12. [Payment Lifecycle](#12-payment-lifecycle)
13. [Reconciliation](#13-reconciliation)
14. [Webhooks](#14-webhooks)
15. [Concurrency](#15-concurrency)
16. [Scaling](#16-scaling)
17. [Database Choice](#17-database-choice)
18. [CAP Theorem](#18-cap-theorem)
19. [Cloud Services](#19-cloud-services)
20. [Tradeoffs Summary](#20-tradeoffs-summary)
21. [Interview Talking Points](#21-interview-talking-points)

---

## 1. Problem Statement

Design a **Payment System** (like Stripe or UPI) that allows merchants to accept payments from customers via credit/debit cards, bank transfers, and digital wallets. The system must process billions of daily transactions, guarantee exactly-once payment execution, maintain an immutable double-entry ledger for every financial movement, reconcile internal records with external bank statements, and notify merchants of payment events via webhooks. Every dollar must be accounted for -- a single bug that causes double-charges or lost funds can destroy trust and invite regulatory action.

**Why is it needed?**

- Payments are the financial backbone of the internet -- Stripe alone processes over $1 trillion in annual volume across millions of merchants.
- A single duplicate charge erodes customer trust and triggers chargebacks, costing merchants 2-3x the original transaction amount.
- Financial regulations (PCI-DSS, SOX, RBI for UPI) mandate immutable audit trails, exact reconciliation, and provable data integrity.
- Network failures between the payment gateway and card networks are inevitable at scale. The system MUST handle partial failures without losing money or double-charging.
- At 1 billion transactions per day, even a 0.001% error rate means 10,000 incorrect transactions daily -- each one a potential legal liability.

**Core Workflow:**

```
Customer pays merchant $100 via credit card

(1) Merchant Backend --POST /payments {amount:100, currency:USD, card_token:tok_xxx}-->
        Payment API Gateway
(2) Payment API Gateway: authenticate merchant (API key), rate limit, check idempotency key
(3) Payment API Gateway --> Payment Service: validate request, create payment record (INITIATED)
(4) Payment Service --> Fraud Detection Service: score transaction risk (rule-based + ML)
(5) Fraud Detection Service --> Payment Service: risk_score=0.12, APPROVED
(6) Payment Service --> Payment Processor: send authorization request to card network
(7) Payment Processor --> Visa/Mastercard Network: authorize $100 on card
(8) Visa/Mastercard Network --> Payment Processor: authorization_code=AUTH_789, APPROVED
(9) Payment Processor --> Payment Service: authorized, auth_code=AUTH_789
(10) Payment Service: update payment record (AUTHORIZED)
(11) Payment Service --> Ledger Service: record double-entry
        DEBIT  customer_funding_source  $100
        CREDIT merchant_pending_balance $100
(12) Payment Service --> Payment Processor: capture authorized payment
(13) Payment Processor --> Visa/Mastercard Network: capture $100 with AUTH_789
(14) Visa/Mastercard Network --> Payment Processor: captured, settlement_ref=STL_456
(15) Payment Service: update payment record (CAPTURED)
(16) Payment Service --> Kafka: publish event "payment.succeeded"
(17) Webhook Service (consuming Kafka): POST to merchant webhook URL
        {event: "payment.succeeded", payment_id: "pay_abc", amount: 100}
        signed with HMAC-SHA256
(18) Merchant Backend: receives webhook, fulfills order

Settlement (async, T+2):
(19) Reconciliation Service: match internal ledger entries with bank settlement file
(20) Ledger Service: move funds from merchant_pending_balance to merchant_available_balance
(21) Merchant requests payout --> funds transferred to merchant bank account
```

### Why This Is Asked in Interviews

This is a **tier-1 system design** interview question, rated **Hard**. It appears at Stripe, PayPal, Google Pay, Amazon, Goldman Sachs, and every fintech company because it tests the deepest distributed systems concepts under the highest stakes -- real money:

| Skill Tested                     | What Interviewers Look For                                              |
|----------------------------------|-------------------------------------------------------------------------|
| **Idempotency**                  | How to guarantee exactly-once payment execution despite network retries |
| **Distributed Transactions**     | Why 2PC fails; how to use Saga or event sourcing for payment flows      |
| **Double-Entry Ledger**          | Every debit has a matching credit; funds never appear or disappear      |
| **Reconciliation**               | Matching internal records with external bank statements daily           |
| **Webhooks**                     | Reliable event delivery to merchants with retry and signature           |
| **Exactly-Once Semantics**       | Network failures between you and Visa -- did the charge go through?     |
| **State Machine Design**         | Payment lifecycle: INITIATED -> AUTHORIZED -> CAPTURED -> SETTLED       |
| **Concurrency Control**          | Two requests with the same idempotency key arriving simultaneously      |
| **Financial Consistency**         | Ledger must always balance; no phantom money                            |
| **Fault Tolerance**              | Card network timeout -- do you retry? Reverse? Wait?                    |

> **Interview tip**: Start by clarifying the scale and payment methods (cards only? UPI? wallets?). Draw the payment lifecycle state machine first -- interviewers love this. Then focus on idempotency and the double-entry ledger. The "aha moment" is explaining how you handle the case where the card network confirms the charge but your service crashes before recording it -- this is where idempotency keys and reconciliation save you.

---

## 2. Scope

### In Scope

| Feature                          | Description                                                              |
|----------------------------------|--------------------------------------------------------------------------|
| Payment Processing               | Accept payments via cards (Visa, Mastercard), UPI, and bank transfers    |
| Idempotency                      | Guarantee exactly-once payment execution with client-provided keys       |
| Double-Entry Ledger              | Immutable financial record of every debit and credit                     |
| Payment Lifecycle                | Full state machine: INITIATED -> AUTHORIZED -> CAPTURED -> SETTLED       |
| Auth + Capture (Two-Phase)       | Separate authorization (hold funds) and capture (collect funds)          |
| Refunds                          | Full and partial refunds with ledger reversal entries                    |
| Webhooks                         | Notify merchants of payment events with signed payloads and retry        |
| Reconciliation                   | Daily matching of internal ledger vs external bank settlement files      |
| Multi-Currency                   | Accept payments in 135+ currencies with real-time exchange rates         |
| Fraud Detection                  | Rule-based and ML-scored risk assessment before authorization            |
| Merchant Onboarding              | API key issuance, webhook URL registration, balance management           |
| Retry with Backoff               | Automatic retry for transient failures with exponential backoff          |

### Out of Scope

| Feature                          | Reason                                                                   |
|----------------------------------|--------------------------------------------------------------------------|
| User-Facing Payment UI (Checkout)| Frontend widget/SDK, not a backend system design concern                 |
| PCI-DSS Tokenization Vault       | Card data tokenization is a separate security-focused deep dive          |
| KYC/AML Compliance               | Regulatory onboarding is an ops/legal domain, not core payment flow      |
| Dispute / Chargeback Management  | Complex process involving card networks, separate workflow                |
| Subscription / Recurring Billing | Extension of payment system, adds scheduling complexity                  |
| Payout to Merchant Bank Account  | Bank transfer initiation is downstream of settlement                     |
| Tax Calculation                  | Business logic layer, not core payment infrastructure                    |
| Multi-Party / Marketplace Splits | Payment splitting among multiple recipients is an extension              |

---

## 3. Assumptions

### Platform Scale

| Parameter                        | Value                                    |
|----------------------------------|------------------------------------------|
| Total transactions per day       | 1 billion                                |
| Annual payment volume            | $10 trillion                             |
| Average transaction amount       | ~$27                                     |
| Peak transactions per second     | 1B / 86400 * 3 (peak factor) = ~35,000 TPS |
| Flash sale / holiday peak        | 5x normal peak = ~175,000 TPS           |
| Active merchants                 | 5 million                                |
| Webhook deliveries per day       | 2 billion (multiple events per payment)  |
| Supported currencies             | 135+                                     |
| Supported payment methods        | Cards (Visa, MC, Amex), UPI, Bank Transfer |
| Concurrent API connections       | 500,000                                  |

### Data Volume

| Parameter                        | Value                                    |
|----------------------------------|------------------------------------------|
| Payment record size              | ~1 KB (payment + metadata + status)      |
| Daily payment data               | 1B * 1 KB = ~1 TB/day                   |
| Ledger entry size                | ~200 bytes per entry                     |
| Daily ledger data                | 1B * 2 entries * 200 B = ~400 GB/day    |
| Idempotency key records          | 1B/day * 1 KB = ~1 TB/day (TTL: 48h, so ~2 TB active) |
| Webhook event records            | 2B * 500 B = ~1 TB/day                  |
| Kafka event throughput           | ~100K events/sec (peak)                  |
| Annual storage growth            | ~365 TB payments + ~146 TB ledger = ~500 TB/year |

### Back-of-the-Envelope: Latency Budget

```
Payment Processing (end-to-end):    Target p99 < 500 ms
  (1) Network RTT (merchant -> API):       10-20 ms
  (2) API Gateway (auth + rate limit):      5 ms
  (3) Idempotency check (Redis):            1-2 ms
  (4) Payment record creation (PostgreSQL): 5-10 ms
  (5) Fraud scoring (in-memory rules):     10-20 ms
  (6) Card network authorization:         100-300 ms  <-- bottleneck (external)
  (7) Ledger entry write (PostgreSQL):      5-10 ms
  (8) Kafka event publish (async):          0 ms (fire-and-forget)
  (9) Network RTT (API -> merchant):       10-20 ms
  ------------------------------------------------
  Total:                                  146-382 ms

Payment Status Query:               Target p99 < 50 ms
  (1) Redis cache lookup:                   1-2 ms
  (2) PostgreSQL fallback (cache miss):     5-10 ms
  (3) Response serialization:               1 ms
  ------------------------------------------------
  Total:                                    2-13 ms

Webhook Delivery:                    Target p99 < 2 seconds
  (1) Kafka consume:                        1-5 ms
  (2) Payload construction + HMAC signing:  1-2 ms
  (3) HTTP POST to merchant:              50-1500 ms  <-- varies by merchant
  (4) Retry scheduling (if failed):         1 ms
  ------------------------------------------------
  Total:                                  53-1508 ms

Reconciliation (daily batch):        Target < 4 hours
  (1) Fetch bank settlement file:           5-30 min
  (2) Stream internal ledger entries:      30-60 min
  (3) Match and flag discrepancies:        60-120 min
  (4) Generate reconciliation report:      10-20 min
  ------------------------------------------------
  Total:                                  105-230 min
```

---

## 4. Functional Requirements

### FR-1: Payment Processing
Merchants can submit a payment request with amount, currency, payment method (card token, UPI VPA, or bank account), and an idempotency key. The system authorizes the payment with the appropriate card network or payment rail, captures funds, and returns a payment object with status and a unique payment ID. The entire flow must be idempotent -- retrying the same request must return the same result without double-charging.

### FR-2: Refunds
Merchants can initiate a full or partial refund for a captured payment. The refund reverses the original ledger entries (creates new debit/credit entries -- never mutates the original). Refund processing is asynchronous; the merchant receives a webhook when the refund is confirmed by the card network. Multiple partial refunds are allowed up to the original amount.

### FR-3: Webhooks
The system notifies merchants of payment lifecycle events (payment.succeeded, payment.failed, payment.refunded, etc.) by sending signed HTTP POST requests to the merchant's registered webhook URL. Each webhook payload includes an HMAC-SHA256 signature so the merchant can verify authenticity. Failed deliveries are retried with exponential backoff up to 24 hours.

### FR-4: Double-Entry Ledger
Every financial movement creates exactly two ledger entries: one debit and one credit of equal amount. The ledger is append-only and immutable -- entries are never updated or deleted. The sum of all debits must always equal the sum of all credits (the ledger must always balance to zero). This is the single source of truth for all financial data.

### FR-5: Reconciliation
The system performs daily batch reconciliation to match internal ledger entries against external bank settlement files. Discrepancies (missing transactions, amount mismatches, duplicate entries) are flagged for manual review. Reconciliation reports are generated with match rates and exception details.

### FR-6: Multi-Currency Support
The system accepts payments in 135+ currencies. Exchange rates are fetched from a reliable provider and cached with a configurable TTL (default: 60 seconds). All internal accounting is done in the merchant's settlement currency. The ledger records both the original currency/amount and the converted currency/amount.

### FR-7: Retry with Backoff
When a card network or bank returns a transient error (timeout, 5xx), the system automatically retries with exponential backoff (1s, 2s, 4s, 8s, up to 60s max). Retries are idempotent -- the same authorization request uses the same idempotency key with the card network. Non-retryable errors (insufficient funds, card declined) are not retried.

### FR-8: Payment Status Query
Merchants can query the current status of any payment by payment ID. The response includes the full payment object with current state, timeline of state transitions, and any associated refunds. This endpoint serves as the source of truth when webhooks are delayed or missed.

### FR-9: Balance Query
Merchants can query their account balance, broken down into: pending (authorized but not settled), available (settled and ready for payout), and reserved (held for refunds or disputes). Balance is computed from the ledger in real time (or from a materialized view for performance).

### FR-10: Fraud Detection
Before authorizing a payment, the system scores transaction risk using rule-based checks (velocity limits, blocklists, geographic anomalies) and an ML model. High-risk transactions are declined or flagged for manual review. Fraud rules are configurable per merchant.

---

## 5. Non-Functional Requirements

| Requirement                 | Target                              | Rationale                                                           |
|-----------------------------|-------------------------------------|---------------------------------------------------------------------|
| **Payment Latency**         | p99 < 500 ms                        | Merchants expect fast checkout; card network is the bottleneck      |
| **Status Query Latency**    | p99 < 50 ms                         | Merchants poll for status; must be fast                             |
| **Availability**            | 99.999% (5.26 min/year downtime)    | Payment downtime = direct revenue loss for every merchant           |
| **Transaction Throughput**  | 175K TPS (peak)                     | Holiday peaks; horizontal scaling required                          |
| **Data Durability**         | Zero financial data loss             | Every cent must be accounted for; synchronous replication            |
| **Consistency**             | Strong for ledger and payments       | Financial data cannot be eventually consistent                      |
| **Idempotency**             | 100% of payment writes idempotent   | Network retries must never cause double-charges                     |
| **Ledger Integrity**        | Sum of debits = sum of credits always| Fundamental accounting invariant; verified continuously              |
| **Webhook Delivery**        | 99.95% within 1 hour                | Merchants depend on webhooks for order fulfillment                  |
| **Reconciliation Accuracy** | 99.99% auto-matched                 | Manual review is expensive; minimize exceptions                     |
| **Scalability**             | Linear horizontal scaling            | Each service scales independently by merchant/region                |
| **Audit Trail**             | Immutable, append-only              | Regulatory requirement; no record can ever be modified or deleted   |
| **Encryption**              | TLS 1.3 in transit, AES-256 at rest | PCI-DSS compliance for card data handling                           |

---

## 6. API Design

### 6.1 Create Payment

```
POST /api/v1/payments
Authorization: Bearer <merchant_api_key>
Idempotency-Key: idem_8f14e45f-ceea-4b8a-9c7e-7a71b2e3d8f2
Content-Type: application/json
```

**Request Body:**

```json
{
  "amount": 10000,
  "currency": "USD",
  "payment_method": {
    "type": "card",
    "token": "tok_visa_4242424242424242"
  },
  "description": "Order #ORD-98765",
  "metadata": {
    "order_id": "ORD-98765",
    "customer_email": "john@example.com"
  },
  "capture": true,
  "return_url": "https://merchant.com/payment/complete"
}
```

**Request Parameters:**

| Parameter         | Type     | Required | Description                                               |
|-------------------|----------|----------|-----------------------------------------------------------|
| `amount`          | Long     | Yes      | Amount in smallest currency unit (cents for USD)          |
| `currency`        | String   | Yes      | ISO 4217 currency code (USD, EUR, INR)                    |
| `payment_method`  | Object   | Yes      | Payment method object (card token, UPI VPA, bank account) |
| `description`     | String   | No       | Human-readable description for the merchant's records     |
| `metadata`        | Object   | No       | Arbitrary key-value pairs (max 50 keys, 500 chars each)   |
| `capture`         | Boolean  | No       | If false, authorize only (capture later). Default: true   |
| `return_url`      | String   | No       | Redirect URL for 3D Secure or UPI flows                   |

**Response (201 Created):**

```json
{
  "id": "pay_1NqR2e2eZvKYlo2CdQxWz3P4",
  "object": "payment",
  "amount": 10000,
  "amount_received": 10000,
  "currency": "USD",
  "status": "CAPTURED",
  "payment_method": {
    "type": "card",
    "last4": "4242",
    "brand": "visa",
    "exp_month": 12,
    "exp_year": 2027
  },
  "description": "Order #ORD-98765",
  "metadata": {
    "order_id": "ORD-98765",
    "customer_email": "john@example.com"
  },
  "authorization_code": "AUTH_789XYZ",
  "created_at": "2026-04-26T10:30:00Z",
  "updated_at": "2026-04-26T10:30:02Z",
  "captured_at": "2026-04-26T10:30:02Z",
  "merchant_id": "merch_abc123",
  "idempotency_key": "idem_8f14e45f-ceea-4b8a-9c7e-7a71b2e3d8f2",
  "refunded": false,
  "refunded_amount": 0,
  "failure_reason": null,
  "receipt_url": "https://pay.example.com/receipts/pay_1NqR2e2eZvKYlo2CdQxWz3P4"
}
```

**Response (409 Conflict -- idempotency key reuse with different parameters):**

```json
{
  "error": {
    "type": "idempotency_error",
    "code": "idempotency_key_reuse",
    "message": "Idempotency key 'idem_8f14e45f...' has already been used with different request parameters.",
    "doc_url": "https://docs.example.com/errors#idempotency-key-reuse"
  }
}
```

**Response (402 Payment Required -- card declined):**

```json
{
  "error": {
    "type": "card_error",
    "code": "card_declined",
    "decline_code": "insufficient_funds",
    "message": "Your card has insufficient funds.",
    "payment_id": "pay_1NqR2e2eZvKYlo2CdQxWz3P4"
  }
}
```

### 6.2 Get Payment

```
GET /api/v1/payments/{paymentId}
Authorization: Bearer <merchant_api_key>
```

**Path Parameters:**

| Parameter    | Type   | Required | Description               |
|--------------|--------|----------|---------------------------|
| `paymentId`  | String | Yes      | Unique payment identifier |

**Response (200 OK):**

```json
{
  "id": "pay_1NqR2e2eZvKYlo2CdQxWz3P4",
  "object": "payment",
  "amount": 10000,
  "amount_received": 10000,
  "currency": "USD",
  "status": "CAPTURED",
  "payment_method": {
    "type": "card",
    "last4": "4242",
    "brand": "visa"
  },
  "timeline": [
    {"status": "INITIATED",  "timestamp": "2026-04-26T10:30:00.000Z"},
    {"status": "AUTHORIZED", "timestamp": "2026-04-26T10:30:00.250Z"},
    {"status": "CAPTURED",   "timestamp": "2026-04-26T10:30:02.100Z"}
  ],
  "refunds": [],
  "created_at": "2026-04-26T10:30:00Z",
  "merchant_id": "merch_abc123"
}
```

**Response (404 Not Found):**

```json
{
  "error": {
    "type": "invalid_request_error",
    "code": "payment_not_found",
    "message": "No payment found with ID 'pay_nonexistent'"
  }
}
```

### 6.3 Create Refund

```
POST /api/v1/refunds
Authorization: Bearer <merchant_api_key>
Idempotency-Key: idem_refund_abc123
Content-Type: application/json
```

**Request Body:**

```json
{
  "payment_id": "pay_1NqR2e2eZvKYlo2CdQxWz3P4",
  "amount": 5000,
  "reason": "customer_request",
  "metadata": {
    "support_ticket": "TKT-12345"
  }
}
```

**Request Parameters:**

| Parameter    | Type   | Required | Description                                               |
|--------------|--------|----------|-----------------------------------------------------------|
| `payment_id` | String | Yes     | The payment to refund                                     |
| `amount`     | Long   | No      | Partial refund amount (cents). Omit for full refund       |
| `reason`     | String | No      | Reason: customer_request, duplicate, fraudulent           |
| `metadata`   | Object | No      | Arbitrary key-value pairs                                 |

**Response (201 Created):**

```json
{
  "id": "ref_1NqS3f3fAaLZmp3DeRyXa4Q5",
  "object": "refund",
  "amount": 5000,
  "currency": "USD",
  "status": "PENDING",
  "payment_id": "pay_1NqR2e2eZvKYlo2CdQxWz3P4",
  "reason": "customer_request",
  "created_at": "2026-04-26T11:00:00Z",
  "estimated_arrival": "2026-04-28T00:00:00Z"
}
```

**Response (400 Bad Request -- refund exceeds original):**

```json
{
  "error": {
    "type": "invalid_request_error",
    "code": "refund_amount_exceeds_payment",
    "message": "Total refunds ($75.00) would exceed the original payment amount ($100.00)."
  }
}
```

### 6.4 Register / Update Webhook

```
POST /api/v1/webhooks
Authorization: Bearer <merchant_api_key>
Content-Type: application/json
```

**Request Body:**

```json
{
  "url": "https://merchant.com/webhooks/payments",
  "events": [
    "payment.succeeded",
    "payment.failed",
    "payment.refunded",
    "refund.created",
    "refund.succeeded",
    "refund.failed"
  ],
  "secret": "whsec_auto_generated"
}
```

**Response (201 Created):**

```json
{
  "id": "wh_endpoint_abc123",
  "url": "https://merchant.com/webhooks/payments",
  "events": ["payment.succeeded", "payment.failed", "payment.refunded",
             "refund.created", "refund.succeeded", "refund.failed"],
  "secret": "whsec_xK9v2mN3pQ7rT1wY4zA6bC8dE0fG",
  "status": "ACTIVE",
  "created_at": "2026-04-26T09:00:00Z"
}
```

### 6.5 Get Merchant Balance

```
GET /api/v1/balance
Authorization: Bearer <merchant_api_key>
```

**Response (200 OK):**

```json
{
  "object": "balance",
  "merchant_id": "merch_abc123",
  "available": [
    {"amount": 5000000, "currency": "USD"},
    {"amount": 1200000, "currency": "EUR"}
  ],
  "pending": [
    {"amount": 750000, "currency": "USD"},
    {"amount": 300000, "currency": "EUR"}
  ],
  "reserved": [
    {"amount": 50000, "currency": "USD"}
  ],
  "last_updated_at": "2026-04-26T10:45:00Z"
}
```

**Balance Breakdown:**

| Type          | Description                                                          |
|---------------|----------------------------------------------------------------------|
| `available`   | Settled funds ready for payout to merchant bank account              |
| `pending`     | Captured but not yet settled (waiting for T+2 bank settlement)       |
| `reserved`    | Held back for potential refunds, disputes, or chargebacks            |

---

## 7. Data Model

### 7.1 Payment

```
Table: payments
+------------------------+------------------+-----------------------------------------------+
| Column                 | Type             | Description                                   |
+------------------------+------------------+-----------------------------------------------+
| id                     | VARCHAR(36) PK   | Unique payment ID (pay_xxx)                   |
| merchant_id            | VARCHAR(36) FK   | Merchant who initiated the payment            |
| idempotency_key        | VARCHAR(255) UQ  | Client-provided idempotency key               |
| amount                 | BIGINT           | Amount in smallest currency unit (cents)      |
| currency               | VARCHAR(3)       | ISO 4217 currency code                        |
| status                 | VARCHAR(20)      | INITIATED/AUTHORIZED/CAPTURED/SETTLED/FAILED  |
| payment_method_type    | VARCHAR(20)      | card / upi / bank_transfer                    |
| payment_method_details | JSONB            | Card last4, brand, UPI VPA, etc.              |
| authorization_code     | VARCHAR(50)      | Auth code from card network                   |
| capture_id             | VARCHAR(50)      | Capture reference from card network           |
| settlement_id          | VARCHAR(50)      | Settlement batch reference                    |
| failure_reason         | VARCHAR(255)     | Decline code or error message                 |
| description            | VARCHAR(500)     | Merchant-provided description                 |
| metadata               | JSONB            | Arbitrary merchant key-value pairs            |
| refunded_amount        | BIGINT           | Total amount refunded so far                  |
| captured_at            | TIMESTAMP        | When payment was captured                     |
| settled_at             | TIMESTAMP        | When settlement was confirmed                 |
| created_at             | TIMESTAMP        | Payment creation time                         |
| updated_at             | TIMESTAMP        | Last status update time                       |
+------------------------+------------------+-----------------------------------------------+
Indexes:
  - PRIMARY KEY (id)
  - UNIQUE INDEX (idempotency_key) -- critical for idempotency enforcement
  - INDEX (merchant_id, created_at DESC) -- merchant payment history
  - INDEX (status, created_at) -- batch processing queries
  - INDEX (settlement_id) -- reconciliation lookups
  - PARTITION BY RANGE (created_at) -- monthly partitions for performance
```

### 7.2 Transaction (State Transitions)

```
Table: payment_transitions
+------------------------+------------------+-----------------------------------------------+
| Column                 | Type             | Description                                   |
+------------------------+------------------+-----------------------------------------------+
| id                     | BIGSERIAL PK     | Auto-increment ID                             |
| payment_id             | VARCHAR(36) FK   | References payments.id                        |
| from_status            | VARCHAR(20)      | Previous status (null for first transition)   |
| to_status              | VARCHAR(20)      | New status                                    |
| reason                 | VARCHAR(255)     | Reason for transition (e.g., "card_declined") |
| external_reference     | VARCHAR(100)     | Card network reference for this step          |
| created_at             | TIMESTAMP        | When transition occurred                      |
+------------------------+------------------+-----------------------------------------------+
Indexes:
  - PRIMARY KEY (id)
  - INDEX (payment_id, created_at) -- payment timeline
  - PARTITION BY RANGE (created_at) -- monthly partitions
```

### 7.3 Ledger Entry

```
Table: ledger_entries
+------------------------+------------------+-----------------------------------------------+
| Column                 | Type             | Description                                   |
+------------------------+------------------+-----------------------------------------------+
| id                     | BIGSERIAL PK     | Auto-increment ID (global sequence)           |
| transaction_id         | VARCHAR(36)      | Groups debit+credit pair (same for both)      |
| payment_id             | VARCHAR(36) FK   | References payments.id                        |
| account_id             | VARCHAR(36) FK   | The account being debited or credited         |
| account_type           | VARCHAR(30)      | MERCHANT / PLATFORM_FEE / BANK_SETTLEMENT     |
| entry_type             | VARCHAR(10)      | DEBIT or CREDIT                               |
| amount                 | BIGINT           | Amount in smallest currency unit (always > 0) |
| currency               | VARCHAR(3)       | ISO 4217 currency code                        |
| original_amount        | BIGINT           | Amount in original transaction currency       |
| original_currency      | VARCHAR(3)       | Original transaction currency (if converted)  |
| exchange_rate          | DECIMAL(18,8)    | Exchange rate used (1.0 if same currency)     |
| description            | VARCHAR(255)     | Human-readable entry description              |
| created_at             | TIMESTAMP        | Entry creation time (immutable)               |
+------------------------+------------------+-----------------------------------------------+
Indexes:
  - PRIMARY KEY (id)
  - INDEX (transaction_id) -- find paired entries
  - INDEX (payment_id) -- all entries for a payment
  - INDEX (account_id, created_at) -- account statement
  - INDEX (created_at) -- reconciliation range scans
  - PARTITION BY RANGE (created_at) -- daily partitions (high volume)

CRITICAL CONSTRAINTS:
  - This table is APPEND-ONLY. No UPDATE or DELETE operations allowed.
  - Every transaction_id must have exactly 2 entries: one DEBIT + one CREDIT.
  - SUM(DEBIT amounts) must ALWAYS equal SUM(CREDIT amounts) globally.
  - Database trigger enforces: for any transaction_id,
    SUM(CASE WHEN entry_type='DEBIT' THEN amount ELSE 0 END) =
    SUM(CASE WHEN entry_type='CREDIT' THEN amount ELSE 0 END)
```

### 7.4 Merchant

```
Table: merchants
+------------------------+------------------+-----------------------------------------------+
| Column                 | Type             | Description                                   |
+------------------------+------------------+-----------------------------------------------+
| id                     | VARCHAR(36) PK   | Unique merchant ID (merch_xxx)                |
| name                   | VARCHAR(255)     | Business name                                 |
| email                  | VARCHAR(255)     | Primary contact email                         |
| api_key_hash           | VARCHAR(64)      | SHA-256 hash of API key (never store raw)     |
| settlement_currency    | VARCHAR(3)       | Currency for settlement (USD, EUR, INR)       |
| webhook_url            | VARCHAR(500)     | Default webhook endpoint URL                  |
| webhook_secret         | VARCHAR(64)      | HMAC-SHA256 signing secret for webhooks       |
| status                 | VARCHAR(20)      | ACTIVE / SUSPENDED / PENDING_REVIEW           |
| risk_level             | VARCHAR(10)      | LOW / MEDIUM / HIGH (affects fraud thresholds)|
| created_at             | TIMESTAMP        | Merchant registration time                    |
| updated_at             | TIMESTAMP        | Last profile update                           |
+------------------------+------------------+-----------------------------------------------+
Indexes:
  - PRIMARY KEY (id)
  - UNIQUE INDEX (api_key_hash) -- fast API key lookup for auth
  - INDEX (status) -- filter active merchants
```

### 7.5 Webhook Delivery

```
Table: webhook_deliveries
+------------------------+------------------+-----------------------------------------------+
| Column                 | Type             | Description                                   |
+------------------------+------------------+-----------------------------------------------+
| id                     | VARCHAR(36) PK   | Unique delivery ID                            |
| webhook_endpoint_id    | VARCHAR(36) FK   | References webhook_endpoints.id               |
| event_type             | VARCHAR(50)      | payment.succeeded, payment.failed, etc.       |
| event_id               | VARCHAR(36)      | Unique event ID (for merchant dedup)          |
| payment_id             | VARCHAR(36) FK   | Related payment                               |
| payload                | JSONB            | Full webhook payload (signed)                 |
| status                 | VARCHAR(20)      | PENDING / DELIVERED / FAILED / EXHAUSTED      |
| attempt_count          | INT              | Number of delivery attempts so far            |
| max_attempts           | INT              | Maximum attempts before giving up (default 15)|
| next_retry_at          | TIMESTAMP        | When to attempt next delivery                 |
| last_response_code     | INT              | HTTP status code of last attempt              |
| last_response_body     | TEXT             | Response body of last attempt (truncated)     |
| delivered_at           | TIMESTAMP        | When successfully delivered (null if pending)  |
| created_at             | TIMESTAMP        | Event creation time                           |
+------------------------+------------------+-----------------------------------------------+
Indexes:
  - PRIMARY KEY (id)
  - INDEX (status, next_retry_at) -- retry queue polling
  - INDEX (payment_id) -- all webhooks for a payment
  - INDEX (webhook_endpoint_id, created_at DESC) -- endpoint delivery history
  - PARTITION BY RANGE (created_at) -- daily partitions
```

### 7.6 Idempotency Key

```
Table: idempotency_keys (Redis + PostgreSQL backup)
+------------------------+------------------+-----------------------------------------------+
| Column                 | Type             | Description                                   |
+------------------------+------------------+-----------------------------------------------+
| key                    | VARCHAR(255) PK  | Client-provided idempotency key               |
| merchant_id            | VARCHAR(36)      | Merchant who owns this key                    |
| request_hash           | VARCHAR(64)      | SHA-256 of the request body (detect misuse)   |
| response_code          | INT              | HTTP status code of the original response     |
| response_body          | JSONB            | Cached response to return on replay           |
| payment_id             | VARCHAR(36)      | Associated payment (if created)               |
| status                 | VARCHAR(20)      | PROCESSING / COMPLETED / FAILED               |
| created_at             | TIMESTAMP        | When key was first seen                       |
| expires_at             | TIMESTAMP        | TTL: created_at + 48 hours                    |
+------------------------+------------------+-----------------------------------------------+
Indexes:
  - PRIMARY KEY (key)
  - INDEX (merchant_id, created_at) -- merchant key history
  - INDEX (expires_at) -- TTL cleanup job

Note: Primary storage is Redis (with 48h TTL) for sub-millisecond lookups.
      PostgreSQL serves as durable backup and is used for the PROCESSING lock
      (Redis key + PostgreSQL row inserted in a transaction to handle races).
```

### 7.7 Entity Relationship Diagram

```
+----------------+       +------------------------+       +------------------+
|   merchants    |       |       payments         |       | payment_transitions|
|----------------|       |------------------------|       |------------------|
| id (PK)        |<------| merchant_id (FK)       |------>| payment_id (FK)  |
| name           |       | idempotency_key (UQ)   |       | from_status      |
| api_key_hash   |       | amount                 |       | to_status        |
| webhook_url    |       | currency               |       | reason           |
| webhook_secret |       | status                 |       | created_at       |
+-------+--------+       | payment_method_type    |       +------------------+
        |                 | authorization_code     |
        |                 | created_at             |
        |                 +----------+-------------+
        |                            |
        |                            | 1:N
        |                            v
        |                 +------------------------+
        |                 |    ledger_entries       |
        |                 |------------------------|
        |                 | id (PK)                |
        |                 | transaction_id         |
        |                 | payment_id (FK)        |
        |                 | account_id (FK)        |
        |                 | entry_type (DEBIT/CR)  |
        |                 | amount                 |
        |                 | currency               |
        |                 | created_at             |
        |                 +------------------------+
        |
        | 1:N
        v
+------------------------+       +------------------------+
| webhook_endpoints      |       | webhook_deliveries     |
|------------------------|       |------------------------|
| id (PK)                |<------| webhook_endpoint_id(FK)|
| merchant_id (FK)       |       | event_type             |
| url                    |       | event_id               |
| events[] (subscribed)  |       | payment_id (FK)        |
| secret                 |       | payload (JSONB)        |
| status                 |       | status                 |
+------------------------+       | attempt_count          |
                                 | next_retry_at          |
                                 +------------------------+

+------------------------+
| idempotency_keys       |
|------------------------|
| key (PK)               |
| merchant_id            |
| request_hash           |
| response_body (JSONB)  |
| payment_id (FK)        |
| status                 |
| expires_at (TTL: 48h)  |
+------------------------+
```

---

## 8. High-Level Architecture

```
                                    PAYMENT SYSTEM ARCHITECTURE
                                    ===========================

    Merchant                                                              External
    Backend                                                               Systems
    +------+                                                          +-------------+
    |      |  (1) POST /payments                                      |    Visa     |
    |      |  {amount, currency, card_token, idempotency_key}         |  Mastercard |
    |      |---+                                                      |    Amex     |
    |      |   |                                                      |   UPI/NPCI  |
    |      |   |                                                      +------+------+
    +--+---+   |                                                             |
       ^       |                                                             |
       |       v                                                             |
       |  +----+-------------------------------------------------------+    |
       |  |                    LOAD BALANCER (L7)                       |    |
       |  |  TLS termination, health checks, sticky sessions           |    |
       |  +----+-------------------------------------------------------+    |
       |       |                                                             |
       |       v                                                             |
       |  +----+-------------------------------------------------------+    |
       |  |                PAYMENT API GATEWAY                          |    |
       |  |                                                             |    |
       |  | (2) Authenticate merchant (API key verification)            |    |
       |  | (3) Rate limiting (per merchant: 1000 req/sec)              |    |
       |  | (4) Idempotency check (Redis lookup)                        |    |
       |  |     - Key exists + COMPLETED? Return cached response        |    |
       |  |     - Key exists + PROCESSING? Return 409 (in progress)     |    |
       |  |     - Key not found? Set key=PROCESSING, continue           |    |
       |  +----+-------------------------------------------------------+    |
       |       |                                                             |
       |       v                                                             |
       |  +----+-------------------------------------------------------+    |
       |  |                  PAYMENT SERVICE                            |    |
       |  |                                                             |    |
       |  | (5) Validate request (amount > 0, currency valid, etc.)     |    |
       |  | (6) Create payment record in DB (status: INITIATED)         |    |
       |  | (7) Call Fraud Detection Service                            |    |
       |  | (8) If approved: call Payment Processor                     |    |
       |  | (9) Update payment record (AUTHORIZED -> CAPTURED)          |    |
       |  | (10) Write ledger entries (double-entry)                    |    |
       |  | (11) Publish event to Kafka                                 |    |
       |  | (12) Update idempotency key (COMPLETED + cached response)   |    |
       |  | (13) Return response to merchant                            |    |
       |  +--+------+-----+-----+------+------+------------------------+    |
       |     |      |     |     |      |      |                             |
       |     v      v     v     v      v      v                             |
       | +---+--+ +-+--+ +-+---++ +---++  +--+---+ +----+                  |
       | |Fraud | |Led-| |Recon | |Cur-|  |Web-  | |Pay-|                  |
       | |Det.  | |ger | |cili- | |ren-|  |hook  | |ment|                  |
       | |Svc   | |Svc | |ation | |cy  |  |Svc   | |Proc|                  |
       | |      | |    | |Svc   | |Svc |  |      | |ess-|                  |
       | +------+ +--+-+ +---+--+ +----+  +--+---+ |or  |                  |
       |              |      |                |     +--+-+                  |
       |              v      v                |        |                    |
       |         +----+------+----+           |        | (8) authorize /   |
       |         |   PostgreSQL   |           |        |     capture        |
       |         |                |           |        |                    |
       |         | - payments     |           |        +--------------------+
       |         | - ledger_entries|          |
       |         | - transitions  |           v
       |         | - merchants    |    +------+------+
       |         +----------------+    |    Kafka    |
       |                               |             |
       |  +----------+                 | Events:     |
       |  |  Redis   |                 | payment.*   |
       |  |          |                 | refund.*    |
       |  | - idempotency keys  |      +------+------+
       |  | - rate limit counters|            |
       |  | - payment cache     |             v
       |  +----------+                 +------+------+
       |                               | Webhook Svc |
       |                               | (consumer)  |
       |  (17) Webhook POST            |             |
       +-------------------------------+ POST to     |
         {event, payment_id, amount}   | merchant URL|
         + HMAC-SHA256 signature       | with retry  |
                                       +-------------+
```

### Architecture Flow Summary

```
HAPPY PATH (Card Payment with Auto-Capture):

(1)  Merchant --> API Gateway:     POST /payments (idempotency_key, amount, card_token)
(2)  API Gateway --> Redis:        Check idempotency key
(3)  Redis --> API Gateway:        Key not found (first request)
(4)  API Gateway --> Redis:        SET idempotency_key = PROCESSING (with NX flag)
(5)  API Gateway --> Payment Svc:  Forward validated request
(6)  Payment Svc --> PostgreSQL:   INSERT payment (status=INITIATED)
(7)  Payment Svc --> Fraud Svc:    Score transaction
(8)  Fraud Svc --> Payment Svc:    risk_score=0.12, APPROVED
(9)  Payment Svc --> Processor:    Authorize $100 on card
(10) Processor --> Visa Network:   Authorization request
(11) Visa Network --> Processor:   AUTH_789, APPROVED
(12) Processor --> Payment Svc:    Authorized
(13) Payment Svc --> PostgreSQL:   UPDATE payment (status=AUTHORIZED)
(14) Payment Svc --> Processor:    Capture AUTH_789
(15) Processor --> Visa Network:   Capture request
(16) Visa Network --> Processor:   Captured, STL_456
(17) Processor --> Payment Svc:    Captured
(18) Payment Svc --> PostgreSQL:   UPDATE payment (status=CAPTURED)
(19) Payment Svc --> Ledger Svc:   Double-entry: DEBIT customer, CREDIT merchant
(20) Ledger Svc --> PostgreSQL:    INSERT 2 ledger entries (in single transaction)
(21) Payment Svc --> Kafka:        Publish "payment.succeeded" event
(22) Payment Svc --> Redis:        SET idempotency_key = COMPLETED + cached response
(23) Payment Svc --> API Gateway:  Return payment object
(24) API Gateway --> Merchant:     201 Created (payment object)
(25) Kafka --> Webhook Svc:        Consume "payment.succeeded" event
(26) Webhook Svc --> Merchant:     POST webhook (signed with HMAC-SHA256)

RETRY SCENARIO (Merchant retries same request):

(1)  Merchant --> API Gateway:     POST /payments (SAME idempotency_key)
(2)  API Gateway --> Redis:        Check idempotency key
(3)  Redis --> API Gateway:        Key found, status=COMPLETED, cached_response exists
(4)  API Gateway --> Merchant:     Return cached response (no reprocessing)
     Total time: ~2ms (Redis lookup only)

FAILURE SCENARIO (Card declined):

(1-8)  Same as happy path through fraud check
(9)  Payment Svc --> Processor:    Authorize $100 on card
(10) Processor --> Visa Network:   Authorization request
(11) Visa Network --> Processor:   DECLINED, reason: insufficient_funds
(12) Processor --> Payment Svc:    Declined
(13) Payment Svc --> PostgreSQL:   UPDATE payment (status=FAILED, reason=insufficient_funds)
(14) Payment Svc --> Kafka:        Publish "payment.failed" event
(15) Payment Svc --> Redis:        SET idempotency_key = COMPLETED + error response
(16) Payment Svc --> API Gateway:  Return error
(17) API Gateway --> Merchant:     402 Payment Required (decline details)

CRASH SCENARIO (Service crashes after card network confirms):

(1-16) Payment Svc sends capture to Visa, Visa confirms...
(17) Payment Svc CRASHES before writing to DB
(18) On restart: Recovery job scans for payments in AUTHORIZED state
(19) Recovery job --> Processor:   Query capture status for AUTH_789
(20) Processor --> Visa Network:   Status inquiry
(21) Visa Network --> Processor:   Captured, STL_456
(22) Recovery job --> PostgreSQL:  UPDATE payment (status=CAPTURED)
(23) Recovery job --> Ledger Svc:  Write ledger entries
(24) Recovery job --> Kafka:       Publish delayed "payment.succeeded"
(25) ALSO: Daily reconciliation catches any remaining gaps
```

---

## 9. Component Deep Dive

### 9.1 Payment API Gateway

The API Gateway is the front door for all payment requests. It handles authentication, rate limiting, and idempotency -- the three critical concerns that must be resolved BEFORE any business logic runs.

**Responsibilities:**

```
+----------------------------------------------------------------+
|                    PAYMENT API GATEWAY                          |
|----------------------------------------------------------------|
|                                                                |
|  (1) TLS TERMINATION                                           |
|      - All traffic over TLS 1.3                                |
|      - Certificate pinning for merchant SDKs                   |
|                                                                |
|  (2) AUTHENTICATION                                            |
|      - Extract API key from Authorization header               |
|      - SHA-256 hash the key, lookup in Redis (cached) or       |
|        PostgreSQL (fallback)                                   |
|      - Verify merchant is ACTIVE (not suspended)               |
|      - Attach merchant_id to request context                   |
|                                                                |
|  (3) RATE LIMITING                                             |
|      - Per-merchant: 1,000 requests/sec (configurable)         |
|      - Global: 200,000 requests/sec                            |
|      - Algorithm: Token bucket (Redis INCR + EXPIRE)           |
|      - Return 429 Too Many Requests when exceeded              |
|      - Include Retry-After header                              |
|                                                                |
|  (4) IDEMPOTENCY CHECK                                         |
|      - Extract Idempotency-Key header                          |
|      - Lookup in Redis:                                        |
|        - COMPLETED: return cached response immediately         |
|        - PROCESSING: return 409 (concurrent request in flight) |
|        - NOT FOUND: SET with NX flag + 48h TTL, continue       |
|      - Hash request body -> compare with stored hash           |
|        - Mismatch: return 409 (key reuse with different params)|
|                                                                |
|  (5) REQUEST VALIDATION                                        |
|      - JSON schema validation                                  |
|      - Amount > 0, valid currency code, valid payment method   |
|      - Request size limit: 64 KB                               |
|                                                                |
|  (6) ROUTING                                                   |
|      - Forward to Payment Service (gRPC or internal HTTP)      |
|      - Circuit breaker on downstream services                  |
|      - Timeout: 30 seconds (card networks can be slow)         |
+----------------------------------------------------------------+
```

**Rate Limiting Implementation:**

```
Rate Limit Check Flow:

(1) Request arrives with merchant API key
(2) Compute Redis key: "ratelimit:{merchant_id}:{current_second}"
(3) INCR key in Redis (atomic)
(4) If first INCR (returns 1): SET EXPIRE key 2 (2-second TTL for cleanup)
(5) If count > limit (1000):
      Return 429 Too Many Requests
      Header: Retry-After: 1
      Header: X-RateLimit-Limit: 1000
      Header: X-RateLimit-Remaining: 0
(6) If count <= limit:
      Header: X-RateLimit-Limit: 1000
      Header: X-RateLimit-Remaining: {1000 - count}
      Continue processing
```

### 9.2 Payment Service

The Payment Service is the central orchestrator of the payment lifecycle. It coordinates all other services and manages the payment state machine.

**Responsibilities:**

```
+----------------------------------------------------------------+
|                     PAYMENT SERVICE                             |
|----------------------------------------------------------------|
|                                                                |
|  PAYMENT LIFECYCLE ORCHESTRATION:                              |
|                                                                |
|  (1) Receive validated request from API Gateway                |
|  (2) Create payment record (PostgreSQL, status=INITIATED)      |
|  (3) Call Fraud Detection Service                              |
|      - If DECLINED: update status=FAILED, return error         |
|      - If REVIEW: queue for manual review, return PENDING      |
|      - If APPROVED: continue                                   |
|  (4) Determine payment processor (card type -> Visa/MC/UPI)    |
|  (5) Call Payment Processor for authorization                  |
|      - On timeout: DO NOT retry immediately                    |
|        (might have been authorized on network side)            |
|      - Query status first, then decide retry/fail              |
|  (6) On authorization success:                                 |
|      - Update payment status to AUTHORIZED                     |
|      - If auto-capture (capture=true): proceed to capture      |
|      - If manual capture: return AUTHORIZED, merchant captures |
|        later via POST /payments/{id}/capture                   |
|  (7) On capture success:                                       |
|      - Update payment status to CAPTURED                       |
|      - Write double-entry ledger entries                        |
|      - Publish "payment.succeeded" to Kafka                    |
|      - Update idempotency key to COMPLETED                     |
|  (8) Return response to API Gateway                            |
|                                                                |
|  REFUND ORCHESTRATION:                                         |
|                                                                |
|  (1) Validate: payment exists, status=CAPTURED or SETTLED      |
|  (2) Validate: refund amount <= (original - already_refunded)  |
|  (3) Call Payment Processor to initiate refund on card network |
|  (4) Create refund record (status=PENDING)                     |
|  (5) Write ledger entries (reverse the original entries)        |
|  (6) Publish "refund.created" to Kafka                         |
|  (7) Card network confirms refund asynchronously (callback)    |
|  (8) Update refund status to SUCCEEDED                         |
|  (9) Publish "refund.succeeded" to Kafka                       |
|                                                                |
|  STATE MACHINE ENFORCEMENT:                                    |
|                                                                |
|  Valid transitions (enforced in code AND database):            |
|    INITIATED  --> AUTHORIZED  (card network approved)          |
|    INITIATED  --> FAILED      (fraud check or decline)         |
|    AUTHORIZED --> CAPTURED    (funds collected)                 |
|    AUTHORIZED --> VOIDED      (merchant cancels before capture)|
|    AUTHORIZED --> FAILED      (capture failed)                 |
|    CAPTURED   --> SETTLED     (bank settlement confirmed)      |
|    CAPTURED   --> REFUNDED    (full refund processed)          |
|    CAPTURED   --> PARTIALLY_REFUNDED (partial refund)          |
|    SETTLED    --> REFUNDED    (post-settlement refund)         |
|    SETTLED    --> PARTIALLY_REFUNDED (post-settlement partial) |
|                                                                |
|  Any other transition is REJECTED with an error.               |
+----------------------------------------------------------------+
```

**Payment Service Recovery:**

```
Recovery Process (runs on startup and every 5 minutes):

(1) Query: SELECT * FROM payments
            WHERE status = 'AUTHORIZED'
            AND updated_at < NOW() - INTERVAL '5 minutes'

(2) For each stale AUTHORIZED payment:
    (a) Query Payment Processor for capture status
    (b) If captured on network:
        - Update to CAPTURED, write ledger, publish event
    (c) If not captured and authorization still valid:
        - Retry capture
    (d) If authorization expired:
        - Update to FAILED, notify merchant

(3) Query: SELECT * FROM payments
            WHERE status = 'INITIATED'
            AND updated_at < NOW() - INTERVAL '10 minutes'

(4) For each stale INITIATED payment:
    (a) Mark as FAILED (timed out)
    (b) Publish "payment.failed" event
```

### 9.3 Payment Processor

The Payment Processor abstracts the communication with external card networks and payment rails. Each network has its own protocol, timeout behavior, and error codes.

**Architecture:**

```
+----------------------------------------------------------------+
|                   PAYMENT PROCESSOR                             |
|----------------------------------------------------------------|
|                                                                |
|  +----------------------------+                                |
|  | Processor Router           |                                |
|  |                            |                                |
|  | Determines which adapter   |                                |
|  | to use based on:           |                                |
|  | - Card BIN (first 6 digits)|                                |
|  | - Payment method type      |                                |
|  | - Merchant configuration   |                                |
|  +-------+--------------------+                                |
|          |                                                     |
|    +-----+-----+-----+-----+-----+                            |
|    |     |     |     |     |     |                             |
|    v     v     v     v     v     v                             |
|  +----+ +--+ +----+ +---+ +----+ +------+                     |
|  |Visa| |MC| |Amex| |UPI| |Bank| |Wallet|                     |
|  |Adpt| |Ad| |Adpt| |Adp| |Xfer| |Adapt |                     |
|  |    | |pt| |    | |t  | |Adpt| |      |                     |
|  +-+--+ +-++ +--+-+ +-+-+ +--+-+ +--+---+                     |
|    |      |     |     |      |      |                          |
|    v      v     v     v      v      v                          |
|  +----+ +----+ +--+ +----+ +----+ +------+                    |
|  |Visa| | MC | |AX| |NPCI| |Bank| |Google|                    |
|  |Net | |Net | |NW| |UPI | |API | |Pay   |                    |
|  +----+ +----+ +--+ +----+ +----+ +------+                    |
|                                                                |
|  ADAPTER INTERFACE (Strategy Pattern):                         |
|                                                                |
|  interface PaymentNetworkAdapter {                              |
|      AuthResult authorize(AuthRequest request);                |
|      CaptureResult capture(CaptureRequest request);            |
|      RefundResult refund(RefundRequest request);               |
|      StatusResult queryStatus(String referenceId);             |
|      void reverseAuthorization(String authCode);               |
|  }                                                             |
|                                                                |
|  TIMEOUT HANDLING (CRITICAL):                                  |
|                                                                |
|  Card network timeout does NOT mean failure!                   |
|  The payment may have been authorized on the network side.     |
|                                                                |
|  (1) Send authorization request to Visa                        |
|  (2) Timeout after 15 seconds (no response)                    |
|  (3) DO NOT retry blindly (could result in double-auth)        |
|  (4) Query Visa for transaction status (using our reference)   |
|  (5) If authorized: proceed with capture                       |
|  (6) If not found: safe to retry OR fail                       |
|  (7) If declined: mark as failed                               |
|                                                                |
|  CIRCUIT BREAKER PER NETWORK:                                  |
|                                                                |
|  If Visa returns 5+ consecutive timeouts:                      |
|  (1) Open circuit breaker for Visa adapter                     |
|  (2) New Visa payments get immediate FAILED response           |
|  (3) After 30 seconds: half-open (allow 1 test request)        |
|  (4) If test succeeds: close circuit, resume normal            |
|  (5) If test fails: keep open, wait another 30 seconds         |
|                                                                |
|  Note: MC, Amex, UPI circuits are independent.                 |
|  Visa outage does NOT affect Mastercard payments.              |
+----------------------------------------------------------------+
```

### 9.4 Ledger Service

The Ledger Service is the financial heart of the payment system. It maintains the immutable, double-entry bookkeeping that proves every dollar is accounted for.

**Responsibilities:**

```
+----------------------------------------------------------------+
|                     LEDGER SERVICE                              |
|----------------------------------------------------------------|
|                                                                |
|  CORE PRINCIPLE: Every financial movement = 2 entries          |
|                  (1 DEBIT + 1 CREDIT of equal amount)          |
|                  Sum of all debits = Sum of all credits ALWAYS  |
|                                                                |
|  OPERATIONS:                                                   |
|                                                                |
|  (1) recordPayment(paymentId, merchantId, amount, currency)    |
|      Creates transaction_id, inserts 2 entries atomically:     |
|      - DEBIT  customer_funding_source  $amount                 |
|      - CREDIT merchant_pending_balance $amount                 |
|                                                                |
|  (2) recordPlatformFee(paymentId, merchantId, feeAmount)       |
|      Platform takes its cut (e.g., 2.9% + 30 cents):          |
|      - DEBIT  merchant_pending_balance $feeAmount              |
|      - CREDIT platform_revenue        $feeAmount              |
|                                                                |
|  (3) recordSettlement(paymentId, merchantId, amount)           |
|      When bank confirms settlement:                            |
|      - DEBIT  merchant_pending_balance $amount                 |
|      - CREDIT merchant_available_balance $amount               |
|                                                                |
|  (4) recordRefund(paymentId, merchantId, refundAmount)         |
|      Reverse the original payment:                             |
|      - DEBIT  merchant_pending_balance $refundAmount           |
|      - CREDIT customer_funding_source  $refundAmount           |
|                                                                |
|  (5) getBalance(accountId)                                     |
|      SELECT SUM(CASE WHEN entry_type='CREDIT' THEN amount     |
|                       WHEN entry_type='DEBIT'  THEN -amount    |
|                  END)                                          |
|      FROM ledger_entries                                       |
|      WHERE account_id = ?                                      |
|                                                                |
|  IMMUTABILITY ENFORCEMENT:                                     |
|                                                                |
|  - Database role for ledger service has NO UPDATE or DELETE     |
|    privileges on ledger_entries table                          |
|  - Application code has no update/delete methods               |
|  - Database triggers reject any UPDATE or DELETE attempt        |
|  - To "correct" an entry: insert a new reversal entry          |
|                                                                |
|  ATOMICITY:                                                    |
|                                                                |
|  Both entries for a transaction are inserted in a SINGLE       |
|  PostgreSQL transaction:                                       |
|                                                                |
|  BEGIN;                                                        |
|    INSERT INTO ledger_entries (transaction_id, payment_id,     |
|      account_id, entry_type, amount, ...) VALUES               |
|      ('txn_123', 'pay_abc', 'cust_fund', 'DEBIT',  10000),   |
|      ('txn_123', 'pay_abc', 'merch_pend', 'CREDIT', 10000);  |
|  COMMIT;                                                       |
|                                                                |
|  If either INSERT fails, BOTH are rolled back.                 |
|  There is NEVER a debit without its matching credit.           |
+----------------------------------------------------------------+
```

**Ledger Integrity Verification:**

```
Continuous Ledger Audit (runs every hour):

(1) Global Balance Check:
    SELECT
      SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE 0 END) as total_debits,
      SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE 0 END) as total_credits
    FROM ledger_entries;

    ASSERT total_debits == total_credits
    If not equal: CRITICAL ALERT (pager, Slack, email to finance team)

(2) Per-Transaction Balance Check:
    SELECT transaction_id,
      SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE 0 END) as debits,
      SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE 0 END) as credits
    FROM ledger_entries
    WHERE created_at > NOW() - INTERVAL '1 hour'
    GROUP BY transaction_id
    HAVING debits != credits;

    Should return ZERO rows. Any row = data corruption.

(3) Orphan Entry Check:
    SELECT id FROM ledger_entries
    WHERE transaction_id NOT IN (
      SELECT transaction_id FROM ledger_entries
      GROUP BY transaction_id
      HAVING COUNT(*) = 2
    );

    Every transaction_id must have exactly 2 entries.
```

### 9.5 Reconciliation Service

The Reconciliation Service matches internal ledger records against external bank settlement files to ensure every transaction is accounted for.

**Architecture:**

```
+----------------------------------------------------------------+
|                  RECONCILIATION SERVICE                         |
|----------------------------------------------------------------|
|                                                                |
|  DAILY BATCH PROCESS (runs at 2:00 AM UTC):                   |
|                                                                |
|  (1) FETCH external settlement files                           |
|      - Visa: SFTP download of daily settlement file            |
|      - Mastercard: API pull of settlement batch                |
|      - UPI/NPCI: SFTP download of settlement report            |
|      - Each file contains: transaction_ref, amount, status,    |
|        settlement_date, merchant_id                            |
|                                                                |
|  (2) PARSE settlement files into normalized records            |
|      +--------------------------------------------------+      |
|      | External Record                                  |      |
|      |--------------------------------------------------|      |
|      | network_ref: "VISA_TXN_20260425_00001"            |      |
|      | amount: 10000 (cents)                             |      |
|      | currency: "USD"                                   |      |
|      | status: "SETTLED"                                 |      |
|      | settlement_date: "2026-04-25"                     |      |
|      | merchant_ref: "pay_1NqR2e2eZvKYlo2CdQxWz3P4"     |      |
|      +--------------------------------------------------+      |
|                                                                |
|  (3) FETCH internal ledger entries for the same period         |
|      SELECT * FROM payments                                    |
|      WHERE status IN ('CAPTURED', 'SETTLED')                   |
|      AND captured_at BETWEEN settlement_start AND settlement_end|
|                                                                |
|  (4) MATCH records using payment_id / network reference        |
|                                                                |
|      Three-way matching:                                       |
|      +-------------------+     +-------------------+           |
|      | Internal Payment  |<--->| External Settlem. |           |
|      | (payments table)  |     | (bank file)       |           |
|      +--------+----------+     +-------------------+           |
|               |                                                |
|               v                                                |
|      +-------------------+                                     |
|      | Ledger Entries     |                                     |
|      | (ledger_entries)   |                                     |
|      +-------------------+                                     |
|                                                                |
|  (5) CATEGORIZE results:                                       |
|                                                                |
|      +------------------+--------------------------------------+
|      | Category         | Description                          |
|      +------------------+--------------------------------------+
|      | MATCHED          | Internal and external records agree   |
|      |                  | on amount, currency, and status       |
|      +------------------+--------------------------------------+
|      | AMOUNT_MISMATCH  | Records exist on both sides but       |
|      |                  | amounts differ (e.g., currency        |
|      |                  | conversion rounding)                  |
|      +------------------+--------------------------------------+
|      | MISSING_EXTERNAL | We have a CAPTURED payment but no     |
|      |                  | corresponding bank settlement record  |
|      |                  | (might settle next day, or lost)      |
|      +------------------+--------------------------------------+
|      | MISSING_INTERNAL | Bank file has a record we don't have  |
|      |                  | (possible crash-before-write, or      |
|      |                  | duplicate on bank side)               |
|      +------------------+--------------------------------------+
|      | DUPLICATE        | Multiple internal records for one     |
|      |                  | external record (idempotency failure) |
|      +------------------+--------------------------------------+
|                                                                |
|  (6) AUTO-RESOLVE known patterns:                              |
|      - MISSING_EXTERNAL with captured_at < 48h ago:            |
|        -> Mark as "PENDING_SETTLEMENT" (normal T+2 delay)      |
|      - AMOUNT_MISMATCH within 1 cent:                          |
|        -> Auto-match (currency rounding tolerance)             |
|                                                                |
|  (7) GENERATE reconciliation report:                           |
|      +--------------------------------------------------+      |
|      | Daily Reconciliation Report - 2026-04-25           |      |
|      |--------------------------------------------------|      |
|      | Total internal records:     12,543,892             |      |
|      | Total external records:     12,543,201             |      |
|      | Matched:                    12,542,890 (99.992%)    |      |
|      | Amount mismatches:                  198             |      |
|      | Missing external:                   802             |      |
|      | Missing internal:                   113             |      |
|      | Duplicates:                           2             |      |
|      | Auto-resolved:                      780             |      |
|      | Requires manual review:             335             |      |
|      +--------------------------------------------------+      |
|                                                                |
|  (8) ALERT if exception rate > 0.01% (configurable threshold)  |
+----------------------------------------------------------------+
```

### 9.6 Webhook Service

The Webhook Service reliably delivers payment event notifications to merchants.

**Architecture:**

```
+----------------------------------------------------------------+
|                     WEBHOOK SERVICE                             |
|----------------------------------------------------------------|
|                                                                |
|  EVENT CONSUMPTION (from Kafka):                               |
|                                                                |
|  (1) Consume events from Kafka topics:                         |
|      - payment.succeeded                                       |
|      - payment.failed                                          |
|      - payment.refunded                                        |
|      - refund.created                                          |
|      - refund.succeeded                                        |
|      - refund.failed                                           |
|                                                                |
|  (2) For each event:                                           |
|      (a) Look up merchant's webhook endpoints                  |
|      (b) Filter: does endpoint subscribe to this event type?   |
|      (c) Create webhook_delivery record (status=PENDING)       |
|      (d) Attempt delivery                                      |
|                                                                |
|  DELIVERY FLOW:                                                |
|                                                                |
|  (3) Construct payload:                                        |
|      {                                                         |
|        "id": "evt_1NqR3f3fBbMZnp4EeSzYb5R6",                  |
|        "type": "payment.succeeded",                            |
|        "created_at": "2026-04-26T10:30:02Z",                  |
|        "data": {                                               |
|          "payment_id": "pay_1NqR2e2eZvKYlo2CdQxWz3P4",        |
|          "amount": 10000,                                      |
|          "currency": "USD",                                    |
|          "status": "CAPTURED",                                 |
|          "merchant_id": "merch_abc123"                         |
|        }                                                       |
|      }                                                         |
|                                                                |
|  (4) Sign payload with HMAC-SHA256:                            |
|      signature = HMAC-SHA256(webhook_secret, payload_json)     |
|      Header: X-Webhook-Signature: sha256=<signature>           |
|      Header: X-Webhook-ID: evt_1NqR3f3fBbMZnp4EeSzYb5R6       |
|      Header: X-Webhook-Timestamp: 1714131002                   |
|                                                                |
|  (5) POST to merchant webhook URL:                             |
|      - Timeout: 15 seconds                                     |
|      - Follow redirects: NO (security)                         |
|      - Verify SSL: YES                                         |
|                                                                |
|  (6) Evaluate response:                                        |
|      - 2xx: SUCCESS -> update delivery status=DELIVERED         |
|      - 4xx (not 429): PERMANENT FAILURE -> stop retrying       |
|      - 429 or 5xx: TRANSIENT FAILURE -> schedule retry         |
|      - Timeout: TRANSIENT FAILURE -> schedule retry            |
|      - Connection refused: TRANSIENT FAILURE -> schedule retry  |
|                                                                |
|  RETRY STRATEGY (Exponential Backoff):                         |
|                                                                |
|  Attempt  | Delay After Failure | Cumulative Time              |
|  ---------|--------------------|------------------------------ |
|  1        | 0 (immediate)      | 0 seconds                    |
|  2        | 1 second           | 1 second                     |
|  3        | 2 seconds          | 3 seconds                    |
|  4        | 4 seconds          | 7 seconds                    |
|  5        | 8 seconds          | 15 seconds                   |
|  6        | 16 seconds         | 31 seconds                   |
|  7        | 32 seconds         | ~1 minute                    |
|  8        | 1 minute           | ~2 minutes                   |
|  9        | 2 minutes          | ~4 minutes                   |
|  10       | 5 minutes          | ~9 minutes                   |
|  11       | 15 minutes         | ~24 minutes                  |
|  12       | 30 minutes         | ~54 minutes                  |
|  13       | 1 hour             | ~2 hours                     |
|  14       | 4 hours            | ~6 hours                     |
|  15       | 18 hours           | ~24 hours (MAX)              |
|  ---------|--------------------|------------------------------ |
|  After 15 attempts: mark delivery as EXHAUSTED, alert merchant |
|                                                                |
|  DEDUPLICATION (Merchant Side):                                |
|                                                                |
|  Every webhook includes a unique event_id.                     |
|  Merchants SHOULD store processed event_ids and ignore         |
|  duplicates (webhooks are "at-least-once" delivery).           |
+----------------------------------------------------------------+
```

### 9.7 Fraud Detection Service

The Fraud Detection Service scores every payment for risk before authorization.

**Architecture:**

```
+----------------------------------------------------------------+
|                  FRAUD DETECTION SERVICE                        |
|----------------------------------------------------------------|
|                                                                |
|  INPUT: Payment request (amount, currency, card, merchant,     |
|         customer IP, device fingerprint, billing address)       |
|                                                                |
|  OUTPUT: FraudDecision { score: 0.0-1.0, action: APPROVE /    |
|          REVIEW / DECLINE, reasons: [...] }                    |
|                                                                |
|  SCORING PIPELINE:                                             |
|                                                                |
|  (1) RULE-BASED CHECKS (fast, deterministic):                  |
|                                                                |
|      Rule                          | Action if triggered       |
|      ------------------------------|---------------------------|
|      Card on global blocklist      | DECLINE immediately       |
|      Amount > merchant max ($10K)  | DECLINE                   |
|      Country mismatch (card vs IP) | +0.3 risk score           |
|      First transaction on card     | +0.15 risk score          |
|      Test card number (4242...)    | DECLINE in production     |
|      Merchant is high-risk         | +0.1 risk score           |
|                                                                |
|  (2) VELOCITY CHECKS (Redis counters):                         |
|                                                                |
|      Check                         | Threshold    | Action     |
|      -------------------------------|-------------|------------|
|      Same card, last 1 minute      | > 3 txns    | +0.4 score |
|      Same card, last 1 hour        | > 10 txns   | +0.3 score |
|      Same card, last 24 hours      | > 50 txns   | DECLINE    |
|      Same IP, last 1 minute        | > 10 txns   | +0.2 score |
|      Same IP, last 1 hour          | > 100 txns  | DECLINE    |
|      Same merchant, failed txns    | > 20% in 1h | +0.3 score |
|      in last hour                  |             |            |
|                                                                |
|      Redis keys: "velocity:{card_hash}:{minute_bucket}"        |
|      TTL: 24 hours (auto-cleanup)                              |
|                                                                |
|  (3) ML MODEL SCORING (optional, async-capable):               |
|                                                                |
|      Features:                                                 |
|      - Transaction amount vs card's historical average          |
|      - Time of day vs card's typical usage pattern             |
|      - Merchant category vs card's typical spending             |
|      - Device fingerprint familiarity                          |
|      - Geographic distance from last transaction               |
|                                                                |
|      Model: Gradient Boosted Trees (XGBoost)                   |
|      Latency: < 10ms (model loaded in memory)                  |
|      Output: risk_score 0.0 to 1.0                             |
|                                                                |
|  (4) DECISION MATRIX:                                          |
|                                                                |
|      Combined Score    | Decision                              |
|      ------------------|---------------------------------------|
|      0.0 - 0.3         | APPROVE (low risk)                    |
|      0.3 - 0.7         | REVIEW (manual review queue)          |
|      0.7 - 1.0         | DECLINE (high risk)                   |
|                                                                |
|      Thresholds configurable per merchant (high-risk merchants  |
|      might have stricter thresholds: 0.0-0.2 approve).         |
|                                                                |
|  LATENCY BUDGET: < 20ms total (rules + velocity + ML)          |
|  Must not be a bottleneck in the payment flow.                 |
+----------------------------------------------------------------+
```

### 9.8 Currency Service

The Currency Service handles exchange rate management for multi-currency payments.

**Architecture:**

```
+----------------------------------------------------------------+
|                    CURRENCY SERVICE                             |
|----------------------------------------------------------------|
|                                                                |
|  EXCHANGE RATE MANAGEMENT:                                     |
|                                                                |
|  (1) Fetch rates from provider (every 60 seconds):             |
|      - Primary: Reuters/Bloomberg FX feed                      |
|      - Fallback: ECB reference rates                           |
|      - Emergency: last known rate + staleness warning          |
|                                                                |
|  (2) Cache in Redis with 60-second TTL:                        |
|      Key: "fx_rate:{from}:{to}"                                |
|      Value: { rate: 1.0856, fetched_at: ..., source: ... }     |
|                                                                |
|  (3) Rate staleness protection:                                |
|      - If rate is > 5 minutes old: log WARNING                 |
|      - If rate is > 30 minutes old: REJECT conversion          |
|        (stale rates can cost millions at scale)                |
|                                                                |
|  CONVERSION FLOW:                                              |
|                                                                |
|  (1) Payment arrives: amount=10000 (100 EUR), merchant settles |
|      in USD                                                    |
|  (2) Currency Service: get rate EUR -> USD                      |
|  (3) Rate: 1.0856 (1 EUR = 1.0856 USD)                        |
|  (4) Converted: 10000 * 1.0856 = 10856 cents ($108.56 USD)    |
|  (5) Ledger records BOTH:                                      |
|      - original_amount: 10000, original_currency: EUR          |
|      - amount: 10856, currency: USD                            |
|      - exchange_rate: 1.0856                                   |
|                                                                |
|  SUPPORTED CURRENCIES: 135+ (all ISO 4217)                     |
|  Including zero-decimal currencies (JPY, KRW) where            |
|  amount=1000 means 1000 yen, not 10.00 yen.                   |
|                                                                |
|  DECIMAL HANDLING:                                              |
|  - All amounts stored as BIGINT (smallest unit)                |
|  - No floating point anywhere in the payment pipeline          |
|  - Rounding: HALF_UP (banker's rounding) to nearest cent       |
|  - Exchange rate stored as DECIMAL(18,8) for precision         |
+----------------------------------------------------------------+
```

---

## 10. Idempotency Deep Dive

### Why Payments MUST Be Idempotent

Idempotency is the most critical property of a payment system. Without it, network failures and retries can cause double-charges -- the fastest way to destroy user trust and invite regulatory action.

**The Fundamental Problem:**

```
SCENARIO: Network failure during payment

(1) Merchant --> Payment API:  POST /payments {amount: $100, idempotency_key: "abc"}
(2) Payment API processes request, charges card successfully
(3) Payment API --> Merchant:  200 OK (response)
(4) NETWORK DROPS the response -- merchant never receives it
(5) Merchant's HTTP client: "Connection timed out"
(6) Merchant: "Did the payment go through? I don't know."
(7) Merchant retries: POST /payments {amount: $100, idempotency_key: "abc"}

WITHOUT IDEMPOTENCY:
(8) Payment API processes request AGAIN, charges card AGAIN
(9) Customer is charged $200 instead of $100
    --> Chargeback, angry customer, regulatory violation

WITH IDEMPOTENCY:
(8) Payment API sees idempotency_key "abc" already exists
(9) Returns the cached response from the first request
(10) Customer is charged exactly $100
     --> Correct behavior, merchant gets the response they missed
```

### Idempotency Key Design

```
KEY FORMAT:
- Client-provided UUID v4: "idem_8f14e45f-ceea-4b8a-9c7e-7a71b2e3d8f2"
- Scoped to merchant: key uniqueness is per-merchant (two merchants can use the same key)
- Header: Idempotency-Key: <key>

STORAGE:
- Primary: Redis (sub-millisecond lookup, 48h TTL)
- Backup: PostgreSQL idempotency_keys table (durable, survives Redis failure)

STORED DATA:
+--------------------------------------------------------------+
| Redis Key: "idempotency:{merchant_id}:{idempotency_key}"    |
| Redis Value (JSON):                                          |
| {                                                            |
|   "status": "COMPLETED",         // PROCESSING or COMPLETED  |
|   "request_hash": "sha256_of_request_body",                 |
|   "response_code": 201,                                     |
|   "response_body": "{...full payment response JSON...}",    |
|   "payment_id": "pay_1NqR2e2eZvKYlo2CdQxWz3P4",            |
|   "created_at": "2026-04-26T10:30:00Z"                      |
| }                                                            |
| TTL: 48 hours (172800 seconds)                               |
+--------------------------------------------------------------+
```

### Implementation Flow

```
IDEMPOTENCY CHECK ALGORITHM:

(1) Extract idempotency_key from request header
    - If missing: generate server-side key (optional, or reject with 400)

(2) Compute request_hash = SHA-256(canonical(request_body))
    - Canonical form: sorted keys, no whitespace, deterministic

(3) Redis GET "idempotency:{merchant_id}:{key}"

(4) IF key exists AND status == "COMPLETED":
    (a) Compare stored request_hash with current request_hash
    (b) IF hashes match:
        - Return stored response_code and response_body
        - Header: X-Idempotent-Replayed: true
        - DONE (no processing, instant response)
    (c) IF hashes differ:
        - Return 409 Conflict
        - "Idempotency key already used with different parameters"
        - DONE

(5) IF key exists AND status == "PROCESSING":
    - Another request with this key is currently being processed
    - Return 409 Conflict
    - "A request with this idempotency key is already in progress"
    - DONE (merchant should wait and retry)

(6) IF key does NOT exist:
    (a) Redis SET "idempotency:{merchant_id}:{key}"
        value = { status: "PROCESSING", request_hash: hash }
        flags = NX (only set if not exists)
        TTL = 172800 (48 hours)

    (b) IF SET NX returns false (race condition, another thread set it first):
        - Go back to step (3) and re-read
        - This handles the simultaneous request race

    (c) IF SET NX returns true (we won the race):
        - Also INSERT into PostgreSQL idempotency_keys (status=PROCESSING)
        - Proceed with payment processing

(7) AFTER payment processing completes (success or failure):
    (a) Redis SET "idempotency:{merchant_id}:{key}"
        value = { status: "COMPLETED", response_code, response_body }
        (overwrite, no NX this time)
    (b) UPDATE PostgreSQL idempotency_keys SET status='COMPLETED', response_body=...

(8) IF payment processing crashes midway:
    - Key remains in "PROCESSING" state
    - After 5 minutes: recovery job detects stale PROCESSING keys
    - Recovery job checks payment status and either:
      (a) Completes the key (if payment was actually processed)
      (b) Deletes the key (if payment was never started, allowing retry)
```

### TTL for Idempotency Keys

```
TTL POLICY:

Duration: 48 hours (configurable per merchant tier)

Rationale:
- 48 hours covers: merchant retry windows, delayed webhook processing,
  and manual retry after investigating a "timeout" response
- After 48 hours, the same idempotency key can be reused
  (merchant should generate a new one for a new payment anyway)

Edge case:
- Merchant reuses key after TTL expiry with the same parameters
- System treats it as a NEW payment (key not found in Redis or DB)
- This is correct: after 48h, if the first payment succeeded,
  the merchant should have received confirmation by now
  (via webhook, status query, or reconciliation)

Cleanup:
- Redis: automatic via TTL (48h expiry)
- PostgreSQL: nightly batch job deletes expired keys
  DELETE FROM idempotency_keys WHERE expires_at < NOW()
```

### Race Condition: Simultaneous Requests

```
RACE CONDITION: Two requests with the same idempotency key arrive
at the same time (e.g., merchant's load balancer sends duplicates)

Timeline:
  t=0ms   Request A arrives at Server 1
  t=1ms   Request B arrives at Server 2 (same idempotency key)

WITHOUT PROTECTION:
  t=2ms   Server 1: Redis GET key -> not found
  t=3ms   Server 2: Redis GET key -> not found
  t=4ms   Server 1: starts processing payment
  t=5ms   Server 2: starts processing payment
  --> DOUBLE CHARGE!

WITH REDIS NX FLAG:
  t=2ms   Server 1: Redis SET key NX -> true (Server 1 wins)
  t=3ms   Server 2: Redis SET key NX -> false (Server 2 loses)
  t=4ms   Server 1: starts processing payment
  t=5ms   Server 2: returns 409 "request in progress"
  --> CORRECT: only one payment processed

WITH POSTGRESQL UNIQUE CONSTRAINT (belt + suspenders):
  Even if Redis NX fails (Redis cluster split-brain), the
  PostgreSQL UNIQUE constraint on idempotency_key catches it:

  t=2ms   Server 1: INSERT INTO idempotency_keys ... -> success
  t=3ms   Server 2: INSERT INTO idempotency_keys ... -> UNIQUE VIOLATION
  t=4ms   Server 2: catches exception, returns 409
  --> CORRECT: database is the ultimate guard

DEFENSE IN DEPTH:
  Layer 1: Redis SET NX (fast, catches 99.99% of races)
  Layer 2: PostgreSQL UNIQUE constraint (durable, catches the rest)
  Layer 3: Card network's own idempotency (our reference ID is unique)
```

---

## 11. Double-Entry Ledger

### Why Double-Entry Bookkeeping

```
FUNDAMENTAL PRINCIPLE:
Every financial transaction creates TWO entries of equal value:
  - One DEBIT (money leaves an account)
  - One CREDIT (money enters an account)

The sum of all debits MUST equal the sum of all credits.
If it doesn't, money has appeared from nowhere or disappeared -- a bug.

WHY THIS MATTERS FOR A PAYMENT SYSTEM:

(1) AUDIT TRAIL: Regulators (SEC, RBI, PCI) require proof that every
    dollar movement is recorded and traceable.

(2) RECONCILIATION: To match internal records with bank statements,
    you need a ledger. Without it, you're comparing apples to oranges.

(3) FINANCIAL ACCURACY: The balance equation (Assets = Liabilities + Equity)
    must hold at all times. Double-entry ensures this mathematically.

(4) ERROR DETECTION: If debits != credits, you know something is wrong.
    Single-entry bookkeeping (just tracking balances) hides errors.

(5) IMMUTABILITY: The ledger is append-only. You never update an entry.
    To correct a mistake, you add a new reversal entry. This creates
    a complete history that can be audited at any point in time.
```

### Account Types

```
ACCOUNT HIERARCHY:

+------------------------------------------------------------------+
|  Account Type                | Description                       |
+------------------------------------------------------------------+
|  customer_funding_source     | Customer's card/bank account      |
|                              | (virtual -- represents money      |
|                              |  coming in from external source)  |
+------------------------------------------------------------------+
|  merchant_pending_balance    | Merchant's funds awaiting         |
|                              | settlement (captured but not      |
|                              | yet available for payout)         |
+------------------------------------------------------------------+
|  merchant_available_balance  | Merchant's settled funds,         |
|                              | ready for payout                  |
+------------------------------------------------------------------+
|  platform_fee_revenue        | Our platform's earned fees        |
|                              | (e.g., 2.9% + 30 cents)          |
+------------------------------------------------------------------+
|  bank_settlement_account     | Funds in transit to/from banks    |
|                              | during settlement process         |
+------------------------------------------------------------------+
|  reserve_account             | Funds held back for potential     |
|                              | refunds, disputes, chargebacks   |
+------------------------------------------------------------------+
|  refund_clearing_account     | Temporary account for refund      |
|                              | processing                        |
+------------------------------------------------------------------+
```

### Example: Customer Pays $100

```
STEP 1: Payment captured ($100, platform fee = 2.9% + $0.30 = $3.20)

  Transaction txn_001 (payment capture):
  +-----+------------------+----------------------------+---------+--------+
  | #   | Entry Type       | Account                    | Amount  | Curr.  |
  +-----+------------------+----------------------------+---------+--------+
  | 1   | DEBIT            | customer_funding_source     | $100.00 | USD    |
  | 2   | CREDIT           | merchant_pending_balance    | $100.00 | USD    |
  +-----+------------------+----------------------------+---------+--------+
  Sum check: DEBIT $100.00 = CREDIT $100.00  [BALANCED]

  Transaction txn_002 (platform fee):
  +-----+------------------+----------------------------+---------+--------+
  | #   | Entry Type       | Account                    | Amount  | Curr.  |
  +-----+------------------+----------------------------+---------+--------+
  | 3   | DEBIT            | merchant_pending_balance    | $3.20   | USD    |
  | 4   | CREDIT           | platform_fee_revenue        | $3.20   | USD    |
  +-----+------------------+----------------------------+---------+--------+
  Sum check: DEBIT $3.20 = CREDIT $3.20  [BALANCED]

  After Step 1:
  - customer_funding_source:    -$100.00 (money left customer)
  - merchant_pending_balance:   +$96.80  ($100 - $3.20 fee)
  - platform_fee_revenue:       +$3.20   (our revenue)
  - Total:  -100 + 96.80 + 3.20 = $0.00  [SYSTEM BALANCED]


STEP 2: Settlement (T+2 days, bank confirms funds transfer)

  Transaction txn_003 (settlement):
  +-----+------------------+----------------------------+---------+--------+
  | #   | Entry Type       | Account                    | Amount  | Curr.  |
  +-----+------------------+----------------------------+---------+--------+
  | 5   | DEBIT            | merchant_pending_balance    | $96.80  | USD    |
  | 6   | CREDIT           | merchant_available_balance  | $96.80  | USD    |
  +-----+------------------+----------------------------+---------+--------+
  Sum check: DEBIT $96.80 = CREDIT $96.80  [BALANCED]

  After Step 2:
  - customer_funding_source:    -$100.00
  - merchant_pending_balance:   $0.00    (fully settled)
  - merchant_available_balance: +$96.80  (ready for payout)
  - platform_fee_revenue:       +$3.20
  - Total: -100 + 0 + 96.80 + 3.20 = $0.00  [SYSTEM BALANCED]


STEP 3: Partial refund ($40 of the $100 original)

  Transaction txn_004 (refund):
  +-----+------------------+----------------------------+---------+--------+
  | #   | Entry Type       | Account                    | Amount  | Curr.  |
  +-----+------------------+----------------------------+---------+--------+
  | 7   | DEBIT            | merchant_available_balance  | $40.00  | USD    |
  | 8   | CREDIT           | customer_funding_source     | $40.00  | USD    |
  +-----+------------------+----------------------------+---------+--------+
  Sum check: DEBIT $40.00 = CREDIT $40.00  [BALANCED]

  After Step 3:
  - customer_funding_source:    -$60.00  (net: paid $100, refunded $40)
  - merchant_pending_balance:   $0.00
  - merchant_available_balance: +$56.80  ($96.80 - $40.00)
  - platform_fee_revenue:       +$3.20   (fee NOT refunded in this example)
  - Total: -60 + 0 + 56.80 + 3.20 = $0.00  [SYSTEM BALANCED]
```

### Immutable Append-Only Log

```
IMMUTABILITY RULES:

(1) NEVER UPDATE a ledger entry.
    - Wrong: UPDATE ledger_entries SET amount = 5000 WHERE id = 7
    - Right: INSERT a new reversal entry

(2) NEVER DELETE a ledger entry.
    - Wrong: DELETE FROM ledger_entries WHERE id = 7
    - Right: Entries live forever (archive to cold storage after 7 years)

(3) To correct a mistake: create a new transaction that reverses the error.

    Example: Entry #1 recorded $100 but should have been $90.

    Correction transaction txn_correction_001:
    +-----+------------------+----------------------------+---------+
    | #   | Entry Type       | Account                    | Amount  |
    +-----+------------------+----------------------------+---------+
    | NEW | DEBIT            | merchant_pending_balance    | $100.00 |
    | NEW | CREDIT           | customer_funding_source     | $100.00 |
    +-----+------------------+----------------------------+---------+
    (This reverses the original $100 entry)

    Then new correct transaction txn_corrected_001:
    +-----+------------------+----------------------------+---------+
    | #   | Entry Type       | Account                    | Amount  |
    +-----+------------------+----------------------------+---------+
    | NEW | DEBIT            | customer_funding_source     | $90.00  |
    | NEW | CREDIT           | merchant_pending_balance    | $90.00  |
    +-----+------------------+----------------------------+---------+
    (This records the correct $90 amount)

    Net effect: $90 moved from customer to merchant.
    Full audit trail: original $100, reversal $100, correction $90.

(4) ENFORCEMENT:
    - Database user for ledger writes has only INSERT privilege
    - Application code has no update/delete DAO methods
    - Database trigger rejects UPDATE and DELETE:
      CREATE TRIGGER prevent_ledger_modification
        BEFORE UPDATE OR DELETE ON ledger_entries
        FOR EACH ROW EXECUTE FUNCTION reject_modification();
    - Code review policy: any PR touching ledger schema requires 2 senior approvals
```

---

## 12. Payment Lifecycle

### State Machine

```
PAYMENT STATE MACHINE:

                     +---> FAILED (fraud check declined)
                     |
                     |     +---> FAILED (authorization declined)
                     |     |
  +----------+  (1)  |  (2)|   (3)    +-----------+  (4)   +----------+
  | INITIATED +------+-----+-------->| AUTHORIZED +------->| CAPTURED |
  +----------+                        +-----+-----+        +----+-----+
                                            |                    |
                                        (5) |               (6)  |
                                            v                    v
                                      +-----+----+        +-----+-----+
                                      |  VOIDED  |        |  SETTLED  |
                                      +----------+        +-----+-----+
                                                                |
                                                           (7)  |  (8)
                                                      +---------+---------+
                                                      |                   |
                                                      v                   v
                                               +------+------+    +------+-------+
                                               |   REFUNDED  |    |  PARTIALLY   |
                                               |             |    |  REFUNDED    |
                                               +-------------+    +--------------+

TRANSITIONS:

(1) INITIATED -> AUTHORIZED
    Trigger: Card network returns authorization_code
    Actions: Store auth_code, record transition, start capture timer

(2) INITIATED -> FAILED
    Trigger: Fraud check declines, or card network declines authorization
    Actions: Record failure_reason, publish payment.failed event

(3) AUTHORIZED -> CAPTURED
    Trigger: Capture request succeeds on card network
    Actions: Write ledger entries, publish payment.succeeded event

(4) CAPTURED -> SETTLED
    Trigger: Bank settlement file confirms fund transfer
    Actions: Move funds from pending to available in ledger

(5) AUTHORIZED -> VOIDED
    Trigger: Merchant cancels before capture, or auth expires (7 days)
    Actions: Release hold on customer's card, no ledger entries needed
             (authorization hold is released, no money moved)

(6) CAPTURED -> REFUNDED / PARTIALLY_REFUNDED
    Trigger: Merchant initiates refund
    Actions: Reversal ledger entries, publish payment.refunded event

(7) SETTLED -> REFUNDED / PARTIALLY_REFUNDED
    Trigger: Post-settlement refund
    Actions: Reversal ledger entries, deduct from merchant available balance

(8) Same as (7) but for partial amount
```

### Auth vs Capture (Two-Phase Payment)

```
WHY TWO PHASES?

Some merchants need to HOLD funds now but COLLECT later:
- Hotels: authorize at check-in, capture at checkout (different amount)
- Ride-sharing: authorize estimated fare, capture actual fare
- Marketplaces: authorize when order placed, capture when item ships

AUTHORIZATION (Phase 1):
  - Verifies the card is valid and has sufficient funds
  - Places a HOLD on the customer's credit limit (not a charge)
  - No money moves yet -- the ledger has NO entries at this point
  - Authorization expires after 7 days (configurable, network-dependent)
  - Customer sees "pending charge" on their statement

CAPTURE (Phase 2):
  - Actually collects the authorized funds
  - Can capture LESS than authorized (e.g., hotel minibar was cheaper)
  - Cannot capture MORE than authorized (need new authorization)
  - This is when ledger entries are created (money officially moves)
  - Customer sees "posted charge" on their statement

FLOW (Manual Capture):

(1) Merchant: POST /payments {amount: 20000, capture: false}
    --> Payment created, status = AUTHORIZED
    --> Customer's card has $200 hold

(2) 3 days later, item ships
    Merchant: POST /payments/pay_abc/capture {amount: 18500}
    --> Captures $185 (item was cheaper than estimated)
    --> $15 hold released automatically
    --> Ledger entries created for $185
    --> Webhook: payment.succeeded (amount: 18500)

AUTO-CAPTURE FLOW:

(1) Merchant: POST /payments {amount: 10000, capture: true}  (default)
    --> Authorization AND capture in single flow
    --> Status goes INITIATED -> AUTHORIZED -> CAPTURED
    --> Ledger entries created immediately
    --> From merchant's perspective: one API call, payment done
```

### Settlement

```
SETTLEMENT PROCESS:

Settlement is the actual transfer of funds from the customer's bank
to the payment platform, and eventually to the merchant.

TIMELINE:
  Day 0 (T):    Payment captured
  Day 1 (T+1):  Card network processes batch settlement
  Day 2 (T+2):  Funds arrive in platform's bank account
  Day 2-3:      Reconciliation confirms, funds move to merchant available balance
  Day 3-7:      Merchant initiates payout to their bank account

BATCH PROCESSING:

(1) Card networks settle in daily batches (not real-time)
(2) At end of day, Visa sends settlement file:
    - All transactions authorized and captured on that day
    - Net amounts after interchange fees

(3) Our settlement process:
    (a) Receive settlement file from each network
    (b) Parse and validate (check totals, count records)
    (c) Match each record to our internal payment
    (d) For matched records: update payment status to SETTLED
    (e) For matched records: create ledger entries
        (move from pending to available)
    (f) For unmatched records: flag for investigation

SETTLEMENT LEDGER ENTRIES:

For each settled payment:
  DEBIT  merchant_pending_balance    $amount
  CREDIT merchant_available_balance  $amount

For interchange fees withheld by network:
  DEBIT  platform_fee_revenue        $interchange_fee
  CREDIT bank_settlement_account     $interchange_fee

NET SETTLEMENT EXAMPLE:
  Original payment: $100.00
  Our platform fee: 2.9% + $0.30 = $3.20
  Interchange fee (Visa takes): 1.8% = $1.80
  Network assessment fee: 0.13% = $0.13

  Merchant receives: $100.00 - $3.20 = $96.80 (from our fee)
  Platform keeps: $3.20 - $1.80 - $0.13 = $1.27 (our actual revenue)
  Visa/Mastercard gets: $1.80 + $0.13 = $1.93 (interchange + assessment)
```

---

## 13. Reconciliation

### Internal Ledger vs External Bank Statement

```
RECONCILIATION: THE THREE-WAY MATCH

Every payment must be verified across three data sources:

  Source 1: Our payments table (internal state machine)
  Source 2: Our ledger_entries table (internal accounting)
  Source 3: Bank/card network settlement file (external truth)

+-------------------+       +-------------------+       +-------------------+
| Our Payments DB   | <---> | Our Ledger DB     | <---> | Bank Settlement   |
|                   |       |                   |       | File              |
| pay_abc: $100     |       | txn_001: $100     |       | REF_789: $100     |
| status: CAPTURED  |       | DEBIT cust $100   |       | status: SETTLED   |
|                   |       | CREDIT merch $100 |       |                   |
+-------------------+       +-------------------+       +-------------------+

ALL THREE MUST AGREE. If any two disagree, we have a discrepancy.
```

### Daily Batch Reconciliation

```
DAILY RECONCILIATION FLOW (2:00 AM UTC):

(1) PREPARATION
    - Lock reconciliation for the settlement date (prevent concurrent runs)
    - Create reconciliation_run record with status=IN_PROGRESS

(2) DATA COLLECTION
    (a) Internal payments:
        SELECT id, amount, currency, status, authorization_code,
               captured_at, merchant_id
        FROM payments
        WHERE captured_at BETWEEN '2026-04-24 00:00:00'
                              AND '2026-04-24 23:59:59'
        AND status IN ('CAPTURED', 'SETTLED', 'REFUNDED')

    (b) Internal ledger:
        SELECT transaction_id, payment_id, account_id,
               entry_type, amount, currency
        FROM ledger_entries
        WHERE created_at BETWEEN '2026-04-24 00:00:00'
                             AND '2026-04-24 23:59:59'

    (c) External settlement files:
        - Download from Visa SFTP: /settlements/2026-04-24/visa_settlement.csv
        - Download from MC SFTP: /settlements/2026-04-24/mc_settlement.csv
        - Parse into normalized ExternalRecord objects

(3) MATCHING ALGORITHM

    For each external record:
      (a) Find internal payment by authorization_code or our reference ID
      (b) If found:
          - Compare amounts (tolerance: 1 cent for rounding)
          - Compare currencies
          - Compare status (external SETTLED should match our CAPTURED+)
          - If all match: MATCHED
          - If amount differs: AMOUNT_MISMATCH
      (c) If not found: MISSING_INTERNAL

    For each internal CAPTURED payment without a matching external record:
      - If captured < 48 hours ago: PENDING_SETTLEMENT (expected T+2)
      - If captured >= 48 hours ago: MISSING_EXTERNAL (investigate)

(4) DISCREPANCY HANDLING

    +--------------------+-------------------------------------------+
    | Discrepancy        | Auto-Resolution                           |
    +--------------------+-------------------------------------------+
    | PENDING_SETTLEMENT | No action (wait for next settlement file) |
    | Amount within 1c   | Auto-match (rounding tolerance)           |
    | Duplicate external | Flag, likely network duplicate             |
    +--------------------+-------------------------------------------+

    +--------------------+-------------------------------------------+
    | Discrepancy        | Manual Resolution Required                |
    +--------------------+-------------------------------------------+
    | Amount > 1 cent    | Review exchange rate or partial capture   |
    | MISSING_EXTERNAL   | Contact card network for status inquiry   |
    |   (after 48h)      |                                           |
    | MISSING_INTERNAL   | Likely crash-before-write; recover from   |
    |                    | card network records                      |
    | Status mismatch    | Our CAPTURED vs network DECLINED          |
    |                    | (rare but critical -- we think we have    |
    |                    |  money but network says no)               |
    +--------------------+-------------------------------------------+

(5) REPORT GENERATION

    Reconciliation Report -- 2026-04-24
    ====================================
    Settlement Date: 2026-04-24
    Run Started: 2026-04-25 02:00:00 UTC
    Run Completed: 2026-04-25 03:47:22 UTC

    Internal Records Processed:  12,543,892
    External Records Processed:  12,543,201

    Results:
      Matched:                   12,542,890  (99.992%)
      Amount Mismatches:                198  (auto-resolved: 185)
      Missing External (pending):       802  (< 48h, expected)
      Missing External (stale):           0  (>= 48h, investigate)
      Missing Internal:                 113  (recover from network)
      Duplicates:                         2  (network duplicates)

    Financial Summary:
      Total Internal Amount:     $341,203,456.78
      Total External Amount:     $341,203,442.12
      Difference:                        $14.66  (13 rounding mismatches)

    Status: COMPLETED_WITH_EXCEPTIONS (113 require manual review)
```

### Handling Discrepancies

```
MISSING_INTERNAL (Bank has it, we don't):

This is the SCARIEST discrepancy. The card network charged the customer
but we have no record of it. Common causes:

(1) Our service crashed after sending authorization to Visa
    but before writing to our database.

    Resolution:
    (a) Query card network for full transaction details
    (b) Create payment record retroactively (status=CAPTURED)
    (c) Write ledger entries
    (d) Send delayed webhook to merchant
    (e) Flag for review (merchant may have already handled it)

(2) Bug in our code skipped database write.

    Resolution:
    (a) Same as above
    (b) Root-cause analysis to fix the bug

MISSING_EXTERNAL (We have it, bank doesn't):

Our system thinks the payment was captured, but the bank has no record.

(1) Authorization was approved but capture failed silently.

    Resolution:
    (a) Attempt capture retry (if auth still valid)
    (b) If auth expired: void payment, reverse ledger, notify merchant

(2) Network processed it but it's in the next settlement batch.

    Resolution:
    (a) Wait for next day's settlement file
    (b) Auto-resolves in the next reconciliation run
```

---

## 14. Webhooks

### Event Types

```
PAYMENT EVENTS:

+----------------------------+------------------------------------------------+
| Event Type                 | When Triggered                                 |
+----------------------------+------------------------------------------------+
| payment.created            | Payment record created (status=INITIATED)      |
| payment.authorized         | Card network approved authorization            |
| payment.succeeded          | Payment captured (funds collected)              |
| payment.failed             | Payment declined or errored                    |
| payment.voided             | Authorization canceled before capture          |
| payment.settled            | Bank settlement confirmed                      |
+----------------------------+------------------------------------------------+

REFUND EVENTS:

+----------------------------+------------------------------------------------+
| Event Type                 | When Triggered                                 |
+----------------------------+------------------------------------------------+
| refund.created             | Refund request submitted                       |
| refund.succeeded           | Card network confirmed refund                  |
| refund.failed              | Refund could not be processed                  |
+----------------------------+------------------------------------------------+
```

### Delivery: Signed Webhook Payload

```
WEBHOOK DELIVERY FORMAT:

HTTP Request:
  POST https://merchant.com/webhooks/payments
  Content-Type: application/json
  X-Webhook-ID: evt_1NqR3f3fBbMZnp4EeSzYb5R6
  X-Webhook-Timestamp: 1714131002
  X-Webhook-Signature: sha256=5d41402abc4b2a76b9719d911017c592

Body:
{
  "id": "evt_1NqR3f3fBbMZnp4EeSzYb5R6",
  "type": "payment.succeeded",
  "api_version": "2026-04-01",
  "created_at": "2026-04-26T10:30:02Z",
  "data": {
    "object": "payment",
    "id": "pay_1NqR2e2eZvKYlo2CdQxWz3P4",
    "amount": 10000,
    "currency": "USD",
    "status": "CAPTURED",
    "payment_method": {
      "type": "card",
      "last4": "4242",
      "brand": "visa"
    },
    "merchant_id": "merch_abc123",
    "created_at": "2026-04-26T10:30:00Z"
  }
}

SIGNATURE COMPUTATION (HMAC-SHA256):

(1) Construct signed payload string:
    signed_content = "{webhook_id}.{timestamp}.{body}"
    Example:
    signed_content = "evt_1NqR3f3fBbMZnp4EeSzYb5R6.1714131002.{...json body...}"

(2) Compute HMAC:
    signature = HMAC-SHA256(merchant_webhook_secret, signed_content)

(3) Set header:
    X-Webhook-Signature: sha256={base64(signature)}

MERCHANT VERIFICATION (on merchant side):

(1) Extract timestamp from X-Webhook-Timestamp header
(2) Check: abs(current_time - timestamp) < 300 seconds (5 min tolerance)
    If outside tolerance: REJECT (replay attack prevention)
(3) Reconstruct signed_content from header values + body
(4) Compute expected_signature using their stored webhook secret
(5) Compare: constant_time_compare(expected_signature, received_signature)
    If mismatch: REJECT (tampered payload)
(6) If valid: process event, return 200 OK
```

### Retry Strategy

```
RETRY WITH EXPONENTIAL BACKOFF:

Failed webhook deliveries are retried with increasing delays:

Attempt | Delay      | Cumulative | What Happens
--------|------------|------------|---------------------------------------------
1       | immediate  | 0s         | First delivery attempt
2       | 1s         | 1s         | Quick retry (might be transient)
3       | 2s         | 3s         | Still retrying quickly
4       | 4s         | 7s         | Backing off
5       | 8s         | 15s        | ~15 seconds total
6       | 16s        | 31s        | ~30 seconds total
7       | 32s        | ~1m        | Approaching 1 minute
8       | 1m         | ~2m        | Merchant might be deploying
9       | 2m         | ~4m        | Merchant might have a brief outage
10      | 5m         | ~9m        | Substantial backoff
11      | 15m        | ~24m       | Merchant likely has an issue
12      | 30m        | ~54m       | Almost 1 hour
13      | 1h         | ~2h        | Major merchant outage
14      | 4h         | ~6h        | Extended outage
15      | 18h        | ~24h       | Final attempt after 24 hours

After attempt 15:
- Mark delivery as EXHAUSTED
- Send email to merchant: "Webhook delivery failed after 15 attempts"
- Merchant can manually replay events via API:
  POST /api/v1/webhooks/events/{event_id}/replay

JITTER:
  Each delay includes random jitter (+-25%) to prevent thundering herd
  when many merchants' webhooks fail simultaneously (e.g., shared hosting)

  actual_delay = base_delay * (0.75 + random() * 0.5)
```

### Idempotent Webhook Handling on Merchant Side

```
MERCHANT-SIDE DEDUPLICATION:

Webhooks use AT-LEAST-ONCE delivery. The same event may be delivered
multiple times (e.g., our system crashed after sending but before
recording the delivery as successful).

Merchants MUST handle duplicates:

(1) Store processed event IDs in their database:
    CREATE TABLE processed_webhook_events (
      event_id VARCHAR(50) PRIMARY KEY,
      processed_at TIMESTAMP DEFAULT NOW()
    );

(2) On receiving a webhook:
    (a) Extract event ID from X-Webhook-ID header
    (b) Check: SELECT 1 FROM processed_webhook_events WHERE event_id = ?
    (c) If exists: return 200 OK (already processed, ignore duplicate)
    (d) If not exists:
        - Process the event (e.g., fulfill order)
        - INSERT INTO processed_webhook_events (event_id) VALUES (?)
        - Return 200 OK

(3) Cleanup: DELETE FROM processed_webhook_events
             WHERE processed_at < NOW() - INTERVAL '7 days'
```

---

## 15. Concurrency

### Concurrent Payments

```
SCENARIO 1: Two payments for the same customer, same card

This is legitimate (customer buying from two merchants simultaneously).
No special handling needed -- each payment is independent.

SCENARIO 2: Same idempotency key, concurrent requests

Handled by the idempotency layer (Section 10):
- Redis SET NX ensures only one request proceeds
- Second request gets 409 Conflict

SCENARIO 3: Race condition on merchant balance

When computing merchant balance from ledger entries:

Problem: Two refunds processed simultaneously might both read the same
         balance and both approve, causing negative balance.

  Thread 1: Read balance = $100, refund $80 -> new balance = $20 (approved)
  Thread 2: Read balance = $100, refund $80 -> new balance = $20 (approved)
  Actual: Both refunds processed, balance = -$60 (WRONG!)

Solution: Pessimistic locking on refund approval:

  BEGIN;
    -- Lock the merchant's latest balance computation
    SELECT SUM(...) FROM ledger_entries
    WHERE account_id = 'merchant_available_merch_abc'
    FOR UPDATE;  -- locks rows, serializes concurrent reads

    -- Verify sufficient balance
    IF balance >= refund_amount THEN
      INSERT INTO ledger_entries (...) VALUES (...);
      -- refund approved
    ELSE
      -- insufficient balance, reject refund
    END IF;
  COMMIT;

Alternative: Optimistic locking with version counter on a materialized
balance table. Retry on version conflict.
```

### Double-Spend Prevention

```
SCENARIO: Preventing double-spend on a single payment

A captured payment of $100 should not be captured twice.

Protection layers:

(1) STATE MACHINE: Payment must be in AUTHORIZED state to be captured.
    After capture, status changes to CAPTURED. Second capture attempt
    sees status=CAPTURED and rejects.

    -- Atomic state transition with row-level lock
    UPDATE payments
    SET status = 'CAPTURED', captured_at = NOW()
    WHERE id = 'pay_abc'
    AND status = 'AUTHORIZED'  -- only if currently authorized
    RETURNING id;

    If RETURNING returns 0 rows: payment was not in AUTHORIZED state.
    Reject the capture attempt.

(2) IDEMPOTENCY: Capture requests also use idempotency keys.
    Re-capturing with the same key returns the cached response.

(3) CARD NETWORK: Networks also enforce: you cannot capture the same
    authorization twice. They reject the duplicate capture with a
    specific error code.

(4) LEDGER: Even if somehow two captures slipped through, the daily
    reconciliation would catch the double ledger entries and flag them.

DEFENSE IN DEPTH:
  Layer 1: State machine (application level)
  Layer 2: Idempotency key (API level)
  Layer 3: Card network rejection (external level)
  Layer 4: Reconciliation (audit level)
```

---

## 16. Scaling

### Partition by merchantId

```
PARTITIONING STRATEGY:

All major tables are partitioned by merchant_id for horizontal scaling:

+------------------------------------------------------------------+
| Table              | Partition Key      | Strategy                |
+------------------------------------------------------------------+
| payments           | merchant_id        | Hash partition (256)    |
| ledger_entries     | account_id         | Hash partition (256)    |
| webhook_deliveries | merchant_id (via   | Hash partition (64)     |
|                    |  webhook_endpoint) |                         |
| idempotency_keys   | merchant_id        | Hash partition (256)    |
+------------------------------------------------------------------+

WHY merchant_id?

(1) Most queries are scoped to a single merchant:
    - GET /payments?merchant_id=X (merchant dashboard)
    - GET /balance?merchant_id=X (balance query)
    - Webhook delivery (per merchant endpoint)

(2) Hot merchants (high volume) get their own partition naturally
    if the hash distributes well.

(3) Cross-merchant queries are rare and can use scatter-gather
    across partitions.

DATABASE SHARDING (at extreme scale):

  1B payments/day = ~12K writes/sec (average)
  Peak: 35K writes/sec

  Single PostgreSQL instance: ~5K writes/sec (with SSDs)
  Need: ~7 write shards minimum (with headroom: 16 shards)

  Shard key: merchant_id (consistent hashing with virtual nodes)

  +--------+     +--------+     +--------+     +--------+
  | Shard 0|     | Shard 1|     | Shard 2|     |Shard 15|
  | merch  |     | merch  |     | merch  |     | merch  |
  | 0-15   |     | 16-31  |     | 32-47  |     | 240-255|
  +--------+     +--------+     +--------+     +--------+
  | Primary|     | Primary|     | Primary|     | Primary|
  | Replica|     | Replica|     | Replica|     | Replica|
  | Replica|     | Replica|     | Replica|     | Replica|
  +--------+     +--------+     +--------+     +--------+
```

### Read Replicas for Balance Queries

```
READ REPLICA STRATEGY:

Balance queries (GET /balance) are read-heavy and can tolerate
slight staleness (< 1 second):

  +-----------+     +-----------+     +-----------+
  |  Primary  |---->| Replica 1 |     | Replica 2 |
  | (writes)  |---->| (reads)   |     | (reads)   |
  +-----------+     +-----------+     +-----------+
                         ^                  ^
                         |                  |
                    Balance queries    Balance queries
                    (merchant dashboard)

Write Path (payment processing):
  - Always goes to PRIMARY
  - Synchronous replication to at least 1 replica (durability)

Read Path (balance queries, payment history):
  - Routed to REPLICA
  - Replication lag: typically < 100ms
  - If merchant just made a payment and queries balance,
    they might see stale balance for ~100ms
  - Acceptable tradeoff: balance query latency is p99 < 50ms

Read Path (payment status for idempotency):
  - Goes to PRIMARY (must be consistent for idempotency correctness)
  - Cannot use replica: stale read might miss a PROCESSING payment
    and allow duplicate processing
```

### Service Scaling

```
HORIZONTAL SCALING PER SERVICE:

+------------------------+--------+---------+---------------------------+
| Service                | Min    | Peak    | Scaling Trigger           |
+------------------------+--------+---------+---------------------------+
| Payment API Gateway    | 20     | 200     | CPU > 60% or QPS > 5K/pod|
| Payment Service        | 30     | 300     | CPU > 70% or QPS > 1K/pod|
| Payment Processor      | 10     | 100     | Active connections > 500  |
| Ledger Service         | 10     | 50      | Write latency > 10ms     |
| Webhook Service        | 20     | 200     | Kafka lag > 10K messages  |
| Fraud Detection Service| 10     | 100     | Latency > 15ms           |
| Reconciliation Service | 2      | 10      | Only during daily batch   |
| Currency Service       | 5      | 20      | Cache miss rate > 5%     |
+------------------------+--------+---------+---------------------------+

KAFKA SCALING:

Topic: payment-events
  Partitions: 256 (aligned with DB sharding)
  Replication factor: 3
  Partition key: merchant_id (ordering within a merchant)
  Consumer groups:
    - webhook-service (delivers webhooks)
    - analytics-service (real-time dashboards)
    - audit-service (compliance logging)
  Each consumer group scales independently.
```

---

## 17. Database Choice

```
+------------------------------------------------------------------+
| Database      | Used For                 | Why This Database       |
+------------------------------------------------------------------+
| PostgreSQL    | payments table           | ACID transactions are   |
| (Primary)     | ledger_entries table     | NON-NEGOTIABLE for      |
|               | payment_transitions      | financial data. Strong  |
|               | merchants table          | consistency, row-level  |
|               | refunds table            | locking, rich indexing, |
|               |                          | and JSON support.       |
|               |                          | Partitioning for scale. |
+------------------------------------------------------------------+
| Redis         | Idempotency key cache    | Sub-millisecond lookups |
| (Cache/Lock)  | Rate limit counters      | for hot-path operations.|
|               | Payment status cache     | SET NX for distributed  |
|               | Fraud velocity counters  | locking. TTL for auto-  |
|               | Exchange rate cache      | expiry of temp data.    |
|               | Session data             | Cluster mode for HA.    |
+------------------------------------------------------------------+
| Apache Kafka  | Payment events           | Durable event streaming |
| (Event Bus)   | Webhook delivery queue   | with guaranteed ordering|
|               | Audit log stream         | per partition (merchant).|
|               | Reconciliation triggers  | Decouples services.     |
|               |                          | Replayable for recovery.|
|               |                          | High throughput (100K   |
|               |                          | events/sec+).           |
+------------------------------------------------------------------+
| S3 / Blob     | Bank settlement files    | Cheap, durable storage  |
| Storage       | Reconciliation reports   | for large files and     |
|               | Archived ledger data     | historical data.        |
|               | Audit logs (cold)        | 99.999999999% durability|
+------------------------------------------------------------------+

WHY NOT NoSQL (e.g., DynamoDB, Cassandra) FOR PAYMENTS?

(1) Financial data requires ACID transactions.
    Ledger entries (debit + credit) must be atomic.
    NoSQL databases typically offer eventual consistency.

(2) The ledger balance equation (debits = credits) requires
    strong consistency to verify in real-time.

(3) Complex reconciliation queries (joins, aggregations, range scans)
    are native to SQL but awkward in NoSQL.

(4) Regulatory audits require SQL-like querying capabilities.

Exception: DynamoDB COULD work for idempotency keys (simple key-value)
and webhook delivery tracking (partition per endpoint). But keeping
the tech stack simpler with PostgreSQL + Redis is preferred.
```

---

## 18. CAP Theorem

```
CAP THEOREM ANALYSIS:

For a payment system, the choice is clear: CP (Consistency + Partition Tolerance).

+------------------------------------------------------------------+
| Property         | Choice    | Rationale                         |
+------------------------------------------------------------------+
| Consistency (C)  | REQUIRED  | Financial data MUST be consistent.|
|                  |           | A customer cannot be shown $100   |
|                  |           | balance on one server and $200 on |
|                  |           | another. Double-charges are       |
|                  |           | unacceptable.                     |
+------------------------------------------------------------------+
| Availability (A) | DEGRADED  | We sacrifice availability during  |
|                  | GRACEFULLY| network partitions. It is better  |
|                  |           | to return a 503 "service          |
|                  |           | temporarily unavailable" than to  |
|                  |           | process a payment with stale data |
|                  |           | and risk double-charging.         |
+------------------------------------------------------------------+
| Partition        | REQUIRED  | Network partitions are inevitable |
| Tolerance (P)    |           | in distributed systems. The system|
|                  |           | must handle them.                 |
+------------------------------------------------------------------+

CP IN PRACTICE:

(1) PostgreSQL with synchronous replication:
    - Write is not acknowledged until at least 1 replica confirms
    - If replica is unreachable (partition): writes BLOCK (or fail)
    - This is intentional: better to reject a payment than process
      it without durability guarantee

(2) Redis for idempotency:
    - Redis Cluster uses CP mode (WAIT command for synchronous replication)
    - If Redis is unavailable: fall back to PostgreSQL for idempotency check
      (slower but consistent)

(3) Kafka:
    - acks=all: producer waits for all in-sync replicas to acknowledge
    - min.insync.replicas=2: at least 2 replicas must be in sync
    - If fewer than 2 replicas available: writes are rejected
    - Events are NOT lost, but event publishing is delayed

WHAT HAPPENS DURING A PARTITION:

Scenario: Database primary is in Region A, replica is in Region B.
Network partition splits A from B.

(1) Region A (primary): continues processing payments normally
(2) Region B (replica): CANNOT process payments (reads only, stale data)
(3) Webhook service in Region B: serves from Kafka (if local broker available)
(4) API Gateway in Region B: routes requests to Region A (if cross-region possible)
    OR returns 503 "service unavailable" with Retry-After header

WE NEVER:
  - Allow writes to both sides of a partition (split-brain)
  - Process payments with eventually consistent balance data
  - Serve stale idempotency checks (could cause double-charges)
```

---

## 19. Cloud Services

```
+-------------------------------+---------------------+---------------------+
| Component                     | AWS                 | GCP                 |
+-------------------------------+---------------------+---------------------+
| Load Balancer                 | ALB                 | Cloud Load Balancer |
| Payment API Gateway           | ECS / EKS           | GKE / Cloud Run     |
| Payment Service               | ECS / EKS           | GKE                 |
| Payment Processor             | ECS / EKS           | GKE                 |
| Ledger Service                | ECS / EKS           | GKE                 |
| Fraud Detection Service       | ECS / EKS           | GKE                 |
| Webhook Service               | ECS / EKS           | GKE                 |
| Reconciliation Service        | ECS / EKS (Batch)   | GKE (CronJob)       |
| Currency Service              | ECS / EKS           | GKE                 |
+-------------------------------+---------------------+---------------------+
| PostgreSQL (payments, ledger) | RDS PostgreSQL      | Cloud SQL           |
|                               | (Multi-AZ)          | (HA config)         |
| Redis (cache, locks)          | ElastiCache Redis   | Memorystore Redis   |
|                               | (Cluster Mode)      | (Cluster Mode)      |
| Kafka (events)                | MSK (Managed Kafka) | Confluent on GKE /  |
|                               |                     | Pub/Sub             |
| Blob Storage (files)          | S3                  | Cloud Storage       |
+-------------------------------+---------------------+---------------------+
| Secrets Management            | AWS Secrets Manager | Secret Manager      |
| Key Management (HMAC keys)    | AWS KMS             | Cloud KMS           |
| Monitoring / Metrics          | CloudWatch          | Cloud Monitoring    |
| Logging                       | CloudWatch Logs     | Cloud Logging       |
| Alerting                      | CloudWatch Alarms   | Cloud Alerting      |
| Tracing                       | X-Ray               | Cloud Trace         |
| DNS                           | Route 53            | Cloud DNS           |
| CDN (for receipt pages)       | CloudFront          | Cloud CDN           |
+-------------------------------+---------------------+---------------------+
| VPC / Network Isolation       | VPC + PrivateLink   | VPC + Private       |
|                               |                     | Service Connect     |
| DDoS Protection               | AWS Shield          | Cloud Armor         |
| WAF                           | AWS WAF             | Cloud Armor WAF     |
+-------------------------------+---------------------+---------------------+

MULTI-REGION DEPLOYMENT (for 99.999% availability):

  Region 1 (US-East): Primary
    - All services active
    - PostgreSQL primary + 2 replicas
    - Redis cluster (6 nodes)
    - Kafka cluster (6 brokers)

  Region 2 (US-West): Hot Standby
    - All services deployed but routing is secondary
    - PostgreSQL async replica (cross-region)
    - Redis cluster (independent, for local cache)
    - Kafka MirrorMaker (cross-region replication)

  Failover: DNS-based (Route 53 health checks)
    - If Region 1 health check fails for 30 seconds
    - Promote Region 2 PostgreSQL replica to primary
    - Switch DNS to Region 2
    - Failover time: 60-120 seconds
```

---

## 20. Tradeoffs Summary

```
+------------------------------+-------------------------------+-------------------------------+
| Decision                     | Chosen Approach               | Alternative & Why Not         |
+------------------------------+-------------------------------+-------------------------------+
| Consistency Model            | Strong consistency (CP)       | Eventual consistency (AP)     |
|                              | for all financial data        | would risk double-charges     |
|                              |                               | and inconsistent balances     |
+------------------------------+-------------------------------+-------------------------------+
| Idempotency Storage          | Redis (primary) +             | PostgreSQL only: too slow     |
|                              | PostgreSQL (backup)           | for hot path (~5ms vs ~1ms)  |
|                              |                               | Redis only: not durable       |
|                              |                               | enough for financial data     |
+------------------------------+-------------------------------+-------------------------------+
| Ledger Design                | Double-entry, append-only     | Single-entry (balance-only):  |
|                              | with immutable entries        | loses audit trail, harder to  |
|                              |                               | reconcile, hides errors       |
+------------------------------+-------------------------------+-------------------------------+
| Auth vs Capture              | Support both auto-capture     | Auto-capture only: loses      |
|                              | and manual (two-phase)        | flexibility for hotels,       |
|                              |                               | ride-sharing, marketplace     |
+------------------------------+-------------------------------+-------------------------------+
| Webhook Delivery             | At-least-once with retry      | Exactly-once: impossible      |
|                              | (merchant must dedup)         | over HTTP. At-most-once:      |
|                              |                               | merchants miss events         |
+------------------------------+-------------------------------+-------------------------------+
| Database                     | PostgreSQL (ACID critical)    | DynamoDB: no ACID             |
|                              |                               | transactions across items     |
|                              |                               | Cassandra: eventual           |
|                              |                               | consistency, wrong for ledger |
+------------------------------+-------------------------------+-------------------------------+
| Event Bus                    | Kafka (durable, replayable,   | RabbitMQ: no replay, lower    |
|                              | ordered per partition)        | throughput. SQS: no ordering  |
|                              |                               | guarantee within partition    |
+------------------------------+-------------------------------+-------------------------------+
| Fraud Detection              | Rule-based + ML scoring       | ML only: black-box, hard to   |
|                              | (layered approach)            | debug. Rules only: misses     |
|                              |                               | subtle fraud patterns         |
+------------------------------+-------------------------------+-------------------------------+
| Settlement                   | T+2 batch settlement          | Real-time settlement:         |
|                              | (industry standard)           | requires real-time banking    |
|                              |                               | infrastructure (expensive,    |
|                              |                               | not universally available)    |
+------------------------------+-------------------------------+-------------------------------+
| Reconciliation               | Daily batch (offline)         | Real-time reconciliation:     |
|                              |                               | impractical since banks only  |
|                              |                               | provide daily settlement files|
+------------------------------+-------------------------------+-------------------------------+
| Multi-Currency               | Convert at payment time,      | Store in original currency,   |
|                              | record both amounts           | convert at payout: exchange   |
|                              |                               | rate risk shifts to merchant  |
+------------------------------+-------------------------------+-------------------------------+
| Partitioning                 | Hash partition by merchant_id | Time-based: hot partition on  |
|                              |                               | current day. Random: can't    |
|                              |                               | query by merchant efficiently |
+------------------------------+-------------------------------+-------------------------------+
| Timeout Handling             | Query status before retry     | Blind retry: could double-    |
| (card network timeout)       | (status inquiry pattern)      | charge. Fail immediately:     |
|                              |                               | loses successful auths        |
+------------------------------+-------------------------------+-------------------------------+
```

---

## 21. Interview Talking Points

### Opening (2 minutes)

```
"A payment system needs to guarantee exactly-once execution of financial
transactions despite network failures, provide an immutable audit trail
via double-entry bookkeeping, and reliably notify merchants of payment
events. The three hardest problems are: idempotency (network failures
between us and the card network), ledger consistency (every dollar must
be accounted for), and reconciliation (matching our records with the
bank's records daily)."
```

### Key Points to Hit

```
(1) IDEMPOTENCY (the interviewer's favorite topic):
    - Client provides an idempotency key (UUID) in the header
    - Redis SET NX for fast race-condition-safe locking
    - PostgreSQL UNIQUE constraint as backup
    - Key stored with request hash to detect misuse
    - TTL of 48 hours, then key expires
    - If the card network timeout occurs: QUERY STATUS FIRST, then decide
      (never blindly retry -- the charge might have gone through)

(2) DOUBLE-ENTRY LEDGER (the "senior engineer" signal):
    - Every payment = 1 DEBIT + 1 CREDIT of equal amount
    - Append-only, immutable (never update, never delete)
    - SUM(debits) must ALWAYS equal SUM(credits) -- invariant
    - To correct a mistake: add a new reversal entry, then a correction
    - This is how banks actually work -- mentioning this shows domain expertise

(3) PAYMENT LIFECYCLE STATE MACHINE:
    - INITIATED -> AUTHORIZED -> CAPTURED -> SETTLED
    - Auth vs Capture: two-phase payment for holds (hotels, rides)
    - State transitions enforced by UPDATE ... WHERE status = 'EXPECTED_STATE'
    - Each transition logged in payment_transitions table

(4) RECONCILIATION:
    - Daily batch: match internal ledger vs external bank settlement file
    - Three-way match: payments table, ledger table, bank file
    - Handle MISSING_INTERNAL (scariest -- we lost a record)
    - Auto-resolve known patterns (pending settlement, rounding)

(5) WEBHOOKS:
    - At-least-once delivery (merchants must dedup)
    - HMAC-SHA256 signed payloads (merchants verify authenticity)
    - Exponential backoff: 15 retries over 24 hours
    - Event types: payment.succeeded, payment.failed, refund.*

(6) CONCURRENCY:
    - Double-spend prevention: state machine + idempotency + card network
    - Balance race condition: SELECT FOR UPDATE on refund approval
    - Idempotency race: Redis SET NX + PostgreSQL UNIQUE constraint
```

### Anticipated Interviewer Deep-Dives

```
Q: "What happens if your service crashes after the card network confirms
    the charge but before you write to your database?"

A: "This is the classic payment inconsistency problem. Three layers of defense:
    (1) Idempotency key is already in PROCESSING state. When the service restarts,
        a recovery job scans for stale PROCESSING keys and checks the card network
        for the transaction status.
    (2) The card network has its own reference for the transaction. We can always
        query them to find out if the charge went through.
    (3) Daily reconciliation catches any remaining gaps by comparing our ledger
        against the bank settlement file. Missing internal records are flagged
        and recovered."

Q: "Why not use a distributed transaction (2PC) instead of this saga-like approach?"

A: "Two-phase commit requires ALL participants (our DB, card network, ledger)
    to be available simultaneously and hold locks for the entire transaction.
    Card networks do not participate in 2PC -- they have their own protocols.
    Also, 2PC has a blocking problem: if the coordinator crashes during the
    commit phase, all participants are blocked. At 35K TPS, even brief blocking
    causes cascading failures. Instead, we use idempotency + eventual recovery
    + reconciliation -- the same approach that real payment systems like Stripe use."

Q: "How do you handle a merchant who sends 10,000 requests per second?"

A: "Rate limiting at the API Gateway: token bucket per merchant, configurable.
    Default is 1,000 req/sec but can be increased for large merchants.
    Redis INCR with per-second TTL keys. Return 429 with Retry-After header.
    This protects both our system and the card networks from abuse."

Q: "How do you ensure the ledger always balances?"

A: "Four layers:
    (1) Application: every ledger write creates exactly 2 entries in a single
        PostgreSQL transaction. If either fails, both roll back.
    (2) Database trigger: rejects any transaction_id that doesn't have
        matching debit and credit amounts.
    (3) Hourly audit job: SUM(debits) vs SUM(credits) globally and per-transaction.
    (4) Immutability: no UPDATE or DELETE on ledger_entries. Database role has
        only INSERT privilege. Database trigger rejects mutations."

Q: "Why PostgreSQL and not DynamoDB for this?"

A: "Financial data needs ACID transactions. When I write two ledger entries
    (debit + credit), they MUST be atomic -- either both succeed or neither does.
    DynamoDB's transactions are limited to 25 items within the same partition key
    and don't support the complex aggregation queries needed for reconciliation.
    PostgreSQL gives us: atomic multi-row transactions, strong consistency,
    rich SQL for reconciliation queries, and row-level locking for concurrency."

Q: "Walk me through a refund flow."

A: "Merchant calls POST /refunds with the payment_id and amount.
    (1) We verify the payment exists and is in CAPTURED or SETTLED state.
    (2) We verify total refunds won't exceed the original amount.
    (3) We lock the merchant's balance (SELECT FOR UPDATE) to prevent
        concurrent refunds from creating negative balance.
    (4) We call the card network to initiate the refund.
    (5) We create new ledger entries -- NOT updating the originals, but adding
        new reversal entries: DEBIT merchant_balance, CREDIT customer_source.
    (6) We publish refund.created event to Kafka.
    (7) Card network confirms asynchronously -> we update refund status,
        publish refund.succeeded.
    (8) Webhook notifies merchant."
```

### Time Allocation (45-minute interview)

```
+-----+---------------------------------+----------------------------------+
| Min | Phase                           | What to Cover                    |
+-----+---------------------------------+----------------------------------+
| 0-3 | Requirements Clarification      | Scale (1B txns/day), methods     |
|     |                                 | (cards, UPI), capture mode       |
+-----+---------------------------------+----------------------------------+
| 3-8 | API Design                      | POST /payments (idempotency key  |
|     |                                 | in header), GET /payments/{id},  |
|     |                                 | POST /refunds                    |
+-----+---------------------------------+----------------------------------+
| 8-15| High-Level Architecture         | Draw the ASCII diagram on board  |
|     |                                 | Gateway -> Service -> Processor  |
|     |                                 | -> Card Network, plus Ledger     |
+-----+---------------------------------+----------------------------------+
|15-22| Payment Lifecycle + State Machine| INITIATED -> AUTHORIZED ->       |
|     |                                 | CAPTURED -> SETTLED              |
|     |                                 | Auth vs Capture (two-phase)      |
+-----+---------------------------------+----------------------------------+
|22-30| Idempotency Deep Dive           | Key design, Redis NX, race       |
|     |                                 | condition handling, TTL, crash    |
|     |                                 | recovery                         |
+-----+---------------------------------+----------------------------------+
|30-37| Double-Entry Ledger             | Debit + Credit = 0 always        |
|     |                                 | Append-only, immutable           |
|     |                                 | Example: $100 payment flow       |
+-----+---------------------------------+----------------------------------+
|37-42| Reconciliation + Webhooks       | Daily batch, three-way match     |
|     |                                 | HMAC-signed, exponential backoff |
+-----+---------------------------------+----------------------------------+
|42-45| Scaling + Tradeoffs             | Shard by merchant, CP not AP,    |
|     |                                 | PostgreSQL not NoSQL             |
+-----+---------------------------------+----------------------------------+
```

### One-Liner Summaries (for quick recall)

```
- Idempotency:     "Redis SET NX + PostgreSQL UNIQUE = exactly-once payment"
- Ledger:          "Every debit has a matching credit; sum always equals zero"
- Reconciliation:  "Three-way match: our payments, our ledger, bank's file"
- Webhooks:        "At-least-once delivery, HMAC-signed, 15 retries over 24h"
- State Machine:   "INITIATED -> AUTHORIZED -> CAPTURED -> SETTLED (or FAILED)"
- Auth vs Capture: "Hold funds now, collect later (hotels, rides, marketplaces)"
- Crash Recovery:  "Query card network status before retrying; reconciliation catches the rest"
- CAP:             "CP always -- reject the payment rather than risk double-charge"
- Scaling:         "Shard by merchant_id; read replicas for balance queries"
- Fraud:           "Rules (fast, deterministic) + ML (nuanced patterns) in pipeline"
```

---

> **Final Note**: In an interview, the payment system is all about trust in the numbers. Every design decision should trace back to this principle: **no money appears from nowhere, no money disappears, and every customer is charged exactly once.** If you can articulate how your design ensures this invariant under failure conditions, you will impress any interviewer.
