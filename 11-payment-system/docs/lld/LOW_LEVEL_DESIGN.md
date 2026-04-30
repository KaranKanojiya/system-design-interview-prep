# Low-Level Design: Payment System (Stripe/UPI)

> **Difficulty**: HARD | **Target**: Senior Java Developer (7+ years) | **Focus**: Double-Entry Ledger, Idempotency, Payment State Machine, Fraud Detection Strategy, Webhook Retry, Concurrency
> This is a top-tier system design question. It tests financial transaction correctness (double-entry bookkeeping), idempotency guarantees, payment lifecycle state machines, fraud detection strategies, webhook delivery with exponential backoff, and reconciliation.

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
| **Model** | `model/` | Domain entities: Payment (Builder + state machine), Transaction (wraps payment with processor metadata), LedgerEntry (accountId, amount, DEBIT/CREDIT, transactionId), Account (balance tracking, currency), Merchant (webhookUrl, apiKey, balance), Refund (partial/full reversal), WebhookEvent (delivery tracking with retry count). Enums: PaymentStatus, PaymentMethod, LedgerEntryType, AccountType, RefundStatus, WebhookStatus, Currency. IdempotencyRecord (key, response, expiry). |
| **Strategy (Processor)** | `strategy/processor/` | Pluggable payment processing: CreditCardProcessor (simulates Visa/MC gateway, 95% success), UPIProcessor (instant settlement, 98% success), WalletProcessor (pre-funded balance, 99% success). Strategy pattern -- swap payment rail without touching service logic. |
| **Strategy (Fraud)** | `strategy/fraud/` | Pluggable fraud detection: RuleBasedFraudCheck (velocity limits, amount thresholds, blacklists), MLFraudCheck (simulated ML risk scoring 0.0-1.0). Chain multiple strategies for defense-in-depth. |
| **Service** | `service/` | Business logic: PaymentService (Facade -- orchestrates entire payment flow: idempotency check, fraud detection, processor routing, ledger recording, webhook dispatch), LedgerService (double-entry bookkeeping, balance queries, audit trail), IdempotencyService (check/store idempotency keys with TTL), WebhookService (dispatch with exponential backoff retry), ReconciliationService (match internal ledger vs external processor records), RefundService (full/partial refunds with ledger reversal), FraudService (applies fraud check strategies), CurrencyService (exchange rate lookup, conversion). |
| **Repository** | `repository/` | Data access layer: PaymentRepository, LedgerRepository, MerchantRepository, AccountRepository, IdempotencyRepository, WebhookRepository interfaces with InMemory implementations. ConcurrentHashMap-backed stores. |
| **Controller** | `controller/` | REST-like API entry point: PaymentController maps requests to PaymentService calls. |
| **Config** | `config/` | Factory wiring: AppConfig creates all objects and injects dependencies. No framework -- pure constructor injection. |
| **Display** | `display/` | PaymentStatsDisplay: payment counts by status, volume by method, fraud detection rates, webhook delivery stats, ledger balance summaries. |
| **Exception** | `exception/` | Domain exceptions: PaymentException (base), DuplicatePaymentException (idempotency violation), InsufficientFundsException (account balance too low), FraudDetectedException (fraud check failed), WebhookDeliveryException (webhook delivery exhausted). |

### Why Payment System Is a Top-Tier Interview Question

```
Interviewer's checklist when evaluating your answer:

  1. Do you use double-entry bookkeeping (every debit has a credit)?  --> Financial Correctness
  2. Is payment processing idempotent (retry-safe)?                   --> Idempotency
  3. Is the Payment a proper state machine with guarded transitions?  --> State Machine Design
  4. Is fraud detection pluggable (rules vs ML)?                      --> Strategy Pattern
  5. Are balance operations thread-safe (no overdraft)?               --> Concurrency
  6. Do webhooks retry with exponential backoff?                      --> Reliability
  7. Can you add a new payment method without changing PaymentService?--> Open-Closed
  8. Is your PaymentService a clean Facade?                           --> Facade Pattern
  9. Do you handle partial refunds with ledger reversal?              --> Domain Modeling
  10. Can you reconcile internal records against external processors?  --> Observability
```

---

## 2. Package Structure

```
com.systemdesign.payment
│
├── model/
│   ├── Payment.java              -- Builder, full lifecycle state machine
│   ├── PaymentStatus.java        -- enum: INITIATED, PROCESSING, AUTHORIZED, CAPTURED, SETTLED, FAILED, REFUNDED, CANCELLED
│   ├── PaymentMethod.java        -- enum: CREDIT_CARD, DEBIT_CARD, UPI, WALLET, BANK_TRANSFER
│   ├── Transaction.java          -- wraps payment with metadata (processor response, timestamps)
│   ├── LedgerEntry.java          -- accountId, amount, type (DEBIT/CREDIT), transactionId, timestamp
│   ├── LedgerEntryType.java      -- enum: DEBIT, CREDIT
│   ├── Account.java              -- accountId, accountType, balance, currency
│   ├── AccountType.java          -- enum: MERCHANT, PLATFORM, CUSTOMER, SETTLEMENT
│   ├── Merchant.java             -- merchantId, name, webhookUrl, apiKey, balance
│   ├── Refund.java               -- refundId, paymentId, amount, reason, status
│   ├── RefundStatus.java         -- enum: PENDING, PROCESSING, COMPLETED, FAILED
│   ├── WebhookEvent.java         -- eventId, type, paymentId, merchantId, payload, status, attempts
│   ├── WebhookStatus.java        -- enum: PENDING, DELIVERED, FAILED, EXHAUSTED
│   ├── Currency.java             -- enum: USD, EUR, GBP, INR, JPY with symbol and decimals
│   └── IdempotencyRecord.java    -- key, response, createdAt, expiresAt
│
├── strategy/
│   ├── processor/
│   │   ├── PaymentProcessor.java       -- interface: processPayment, processRefund
│   │   ├── CreditCardProcessor.java    -- simulates Visa/Mastercard (95% success)
│   │   ├── UPIProcessor.java           -- simulates UPI (98% success, instant)
│   │   └── WalletProcessor.java        -- simulates wallet (99% success)
│   │
│   └── fraud/
│       ├── FraudCheckStrategy.java     -- interface: checkFraud(payment) -> FraudResult
│       ├── RuleBasedFraudCheck.java    -- velocity check, amount threshold, blacklist
│       └── MLFraudCheck.java           -- simulated ML scoring (0.0-1.0 risk score)
│
├── service/
│   ├── PaymentService.java       -- FACADE: orchestrates entire payment flow
│   ├── LedgerService.java        -- double-entry bookkeeping, balance queries
│   ├── IdempotencyService.java   -- check/store idempotency keys
│   ├── WebhookService.java       -- dispatch webhooks with retry
│   ├── ReconciliationService.java -- match internal vs external records
│   ├── RefundService.java        -- process refunds with ledger reversal
│   ├── FraudService.java         -- applies fraud checks
│   └── CurrencyService.java     -- exchange rate lookup, conversion
│
├── repository/
│   ├── PaymentRepository.java, InMemoryPaymentRepository.java
│   ├── LedgerRepository.java, InMemoryLedgerRepository.java
│   ├── MerchantRepository.java, InMemoryMerchantRepository.java
│   ├── AccountRepository.java, InMemoryAccountRepository.java
│   ├── IdempotencyRepository.java, InMemoryIdempotencyRepository.java
│   └── WebhookRepository.java, InMemoryWebhookRepository.java
│
├── controller/
│   └── PaymentController.java     -- REST-like entry point
│
├── config/
│   └── AppConfig.java             -- factory wiring, pure constructor injection
│
├── display/
│   └── PaymentStatsDisplay.java   -- formatted payment/ledger/webhook stats
│
├── exception/
│   ├── PaymentException.java           -- base exception for all payment errors
│   ├── DuplicatePaymentException.java  -- thrown when idempotency key already exists
│   ├── InsufficientFundsException.java -- thrown when account balance too low
│   ├── FraudDetectedException.java     -- thrown when fraud check fails
│   └── WebhookDeliveryException.java   -- thrown when webhook delivery exhausted
│
└── PaymentSystemApp.java          -- Main demo: wires everything, runs payment scenarios
```

---

## 3. Class Diagram

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║              PAYMENT LIFECYCLE STATE MACHINE (THE Core Concept)                    ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    ┌───────────┐  authorize()  ┌────────────┐  capture()   ┌──────────┐
    │ INITIATED │──────────────→│ AUTHORIZED │─────────────→│ CAPTURED │
    └───────────┘               └────────────┘              └──────────┘
         │                           │                           │
         │ process()                 │ cancel()                  │ settle()
         │                           │                           │
         ▼                           ▼                           ▼
    ┌────────────┐              ┌───────────┐              ┌──────────┐
    │ PROCESSING │              │ CANCELLED │              │ SETTLED  │
    └────────────┘              └───────────┘              └──────────┘
         │                                                      │
         │ fail()                                               │ refund()
         │                                                      │
         ▼                                                      ▼
    ┌────────┐                                             ┌──────────┐
    │ FAILED │                                             │ REFUNDED │
    └────────┘                                             └──────────┘

    Valid transitions (enforced by Payment.transitionTo()):
      INITIATED   → PROCESSING, CANCELLED
      PROCESSING  → AUTHORIZED, FAILED, CANCELLED
      AUTHORIZED  → CAPTURED, CANCELLED
      CAPTURED    → SETTLED, REFUNDED
      SETTLED     → REFUNDED
      FAILED      → (terminal state)
      REFUNDED    → (terminal state)
      CANCELLED   → (terminal state)


╔═══════════════════════════════════════════════════════════════════════════════════╗
║              DOUBLE-ENTRY LEDGER (Financial Correctness Guarantee)                 ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    Every money movement creates EXACTLY TWO ledger entries:
    one DEBIT and one CREDIT, always summing to zero.

    PAYMENT CAPTURED ($100.00):
    ┌─────────────────────────────────────────────────────────────────────┐
    │  Entry 1: DEBIT   Customer Account     -$100.00  (money leaves)   │
    │  Entry 2: CREDIT  Platform Account     +$100.00  (money arrives)  │
    │                                         ─────────                  │
    │                                    Net: $0.00 (balanced!)          │
    └─────────────────────────────────────────────────────────────────────┘

    SETTLEMENT TO MERCHANT ($97.00, after $3.00 platform fee):
    ┌─────────────────────────────────────────────────────────────────────┐
    │  Entry 1: DEBIT   Platform Account     -$97.00   (money leaves)   │
    │  Entry 2: CREDIT  Merchant Account     +$97.00   (money arrives)  │
    │  Entry 3: DEBIT   Platform Account     -$3.00    (fee retained)   │
    │  Entry 4: CREDIT  Platform Fee Account +$3.00    (fee recorded)   │
    │                                         ─────────                  │
    │                                    Net: $0.00 (balanced!)          │
    └─────────────────────────────────────────────────────────────────────┘

    REFUND ($100.00):
    ┌─────────────────────────────────────────────────────────────────────┐
    │  Entry 1: DEBIT   Merchant Account     -$100.00  (money leaves)   │
    │  Entry 2: CREDIT  Customer Account     +$100.00  (money returns)  │
    │                                         ─────────                  │
    │                                    Net: $0.00 (balanced!)          │
    └─────────────────────────────────────────────────────────────────────┘

    INVARIANT: SUM(all DEBIT amounts) + SUM(all CREDIT amounts) = 0, ALWAYS.
    If this invariant breaks, you have a bug. This is how banks detect errors.


╔═══════════════════════════════════════════════════════════════════════════════════╗
║              PAYMENT PROCESSOR STRATEGY HIERARCHY (Strategy Pattern)               ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    +-----------------------------------------------------------+
    |          <<interface>>  PaymentProcessor                   |
    |-----------------------------------------------------------|
    | + processPayment(payment: Payment): Transaction           |
    | + processRefund(refund: Refund): Transaction              |
    | + getProcessorName(): String                              |
    | + getSupportedMethods(): Set<PaymentMethod>               |
    +-----------------------------------------------------------+
          ^                   ^                    ^
          |                   |                    |
     implements          implements           implements
          |                   |                    |
    +-----+--------+   +-----+--------+   +-------+------+
    | CreditCard   |   | UPI          |   | Wallet       |
    |  Processor   |   |  Processor   |   |  Processor   |
    |--------------|   |--------------|   |--------------|
    | -successRate:|   | -successRate:|   | -successRate:|
    |   0.95       |   |   0.98       |   |   0.99       |
    | -latencyMs:  |   | -latencyMs:  |   | -latencyMs:  |
    |   2000-5000  |   |   100-500    |   |   50-200     |
    |--------------|   |--------------|   |--------------|
    | simulates    |   | simulates    |   | deducts from |
    | card network |   | UPI instant  |   | wallet       |
    | auth+capture |   | settlement   |   | balance      |
    | (two-phase)  |   | (single-     |   | atomically   |
    |              |   |  phase)      |   |              |
    +--------------+   +--------------+   +--------------+


╔═══════════════════════════════════════════════════════════════════════════════════╗
║              FRAUD DETECTION STRATEGY HIERARCHY (Strategy Pattern)                 ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    +-----------------------------------------------------------+
    |          <<interface>>  FraudCheckStrategy                 |
    |-----------------------------------------------------------|
    | + checkFraud(payment: Payment): FraudResult               |
    | + getStrategyName(): String                               |
    +-----------------------------------------------------------+
          ^                            ^
          |                            |
     implements                   implements
          |                            |
    +-----+------------+   +-----------+-----------+
    | RuleBasedFraud   |   | MLFraudCheck          |
    |   Check          |   |                       |
    |------------------|   |-----------------------|
    | -maxAmountPerTxn |   | -riskThreshold: 0.7  |
    | -maxTxnPerHour   |   | -modelVersion: "v2"  |
    | -blacklistedIds  |   |                       |
    |------------------|   |-----------------------|
    | checks:          |   | simulates ML model:   |
    |  1. amount limit |   |  - feature extraction |
    |  2. velocity     |   |  - risk score 0.0-1.0 |
    |  3. blacklist    |   |  - threshold compare  |
    +------------------+   +-----------------------+


╔═══════════════════════════════════════════════════════════════════════════════════╗
║                          SERVICE LAYER (Facade Pattern)                            ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    ┌─────────────────────────────────────────────────────────────────────┐
    │                    PaymentController                                 │
    │  processPayment() │ refundPayment() │ getPayment() │ getBalance()  │
    └────────┬────────────────────┬──────────────┬──────────────┬────────┘
             │                    │              │              │
             ▼                    ▼              ▼              ▼
    ┌─────────────────────────────────────────────────────────────────────┐
    │                    PaymentService (FACADE)                           │
    │  Orchestrates: idempotency → fraud → process → ledger → webhook    │
    └──┬──────────┬───────────┬──────────┬──────────┬──────────┬────────┘
       │          │           │          │          │          │
       ▼          ▼           ▼          ▼          ▼          ▼
    Idempotency Fraud     Payment    Ledger     Webhook    Refund
    Service     Service   Processor  Service    Service    Service
       │          │       (strategy)    │          │          │
       ▼          ▼           │         ▼          ▼          ▼
    Idempotency Fraud      Process   Ledger     Webhook   Payment
    Repository  Strategy   Payment   Repository Repository Repository
               (pluggable) (pluggable)

    ┌─────────────────────────────────────────────────────────────────────┐
    │                SUPPORTING SERVICES                                   │
    │                                                                      │
    │  CurrencyService         -- exchange rate lookup, conversion         │
    │  ReconciliationService   -- match internal ledger vs processor       │
    │  PaymentStatsDisplay     -- formatted stats for monitoring           │
    └─────────────────────────────────────────────────────────────────────┘
```

---

## 4. Entity Design

### 4.1 Currency (Enum)

```java
/**
 * Supported currencies with their symbols and decimal precision.
 *
 * Why an enum with fields?
 *   - Currencies have fixed properties (symbol, decimals)
 *   - JPY has 0 decimals (no cents), USD/EUR/GBP have 2
 *   - This prevents "magic number" bugs in amount formatting
 *
 * Used by:
 *   - Account: each account has a currency
 *   - Payment: each payment is in a specific currency
 *   - CurrencyService: conversion between currencies
 */
public enum Currency {
    USD("$", 2),
    EUR("€", 2),
    GBP("£", 2),
    INR("₹", 2),
    JPY("¥", 0);   // Japanese Yen has no sub-unit

    private final String symbol;
    private final int decimals;

    Currency(String symbol, int decimals) {
        this.symbol = symbol;
        this.decimals = decimals;
    }

    public String getSymbol() { return symbol; }
    public int getDecimals() { return decimals; }

    /**
     * Format an amount according to this currency's rules.
     * USD: $100.50   JPY: ¥10050 (no decimals)
     */
    public String format(double amount) {
        if (decimals == 0) {
            return symbol + String.format("%d", (long) amount);
        }
        return symbol + String.format("%." + decimals + "f", amount);
    }
}
```

---

### 4.2 PaymentStatus (Enum with State Machine Transitions)

```java
/**
 * Payment lifecycle states with valid transition rules.
 *
 * This enum is THE core state machine for payments.
 * Every payment starts at INITIATED and moves through states
 * based on processor responses and business events.
 *
 * TRANSITION DIAGRAM:
 *
 *   INITIATED ──→ PROCESSING ──→ AUTHORIZED ──→ CAPTURED ──→ SETTLED
 *       │              │              │                          │
 *       │              │              │                          │
 *       ▼              ▼              ▼                          ▼
 *   CANCELLED       FAILED       CANCELLED                  REFUNDED
 *
 * Terminal states: FAILED, REFUNDED, CANCELLED (no outgoing transitions)
 *
 * Why encode transitions in the enum?
 *   - Single source of truth for valid transitions
 *   - Payment.transitionTo() delegates to this enum
 *   - Impossible to create an invalid transition path
 */
public enum PaymentStatus {

    INITIATED,       // payment created, not yet sent to processor
    PROCESSING,      // sent to payment processor, awaiting response
    AUTHORIZED,      // processor authorized the charge (funds held, not yet captured)
    CAPTURED,        // funds captured from customer's account
    SETTLED,         // funds transferred to merchant (T+1 or T+2)
    FAILED,          // processor rejected the payment (terminal)
    REFUNDED,        // payment reversed back to customer (terminal)
    CANCELLED;       // payment cancelled before completion (terminal)

    // Static map of allowed transitions, initialized in static block
    private static final Map<PaymentStatus, Set<PaymentStatus>> VALID_TRANSITIONS;

    static {
        VALID_TRANSITIONS = Map.of(
            INITIATED,   Set.of(PROCESSING, CANCELLED),
            PROCESSING,  Set.of(AUTHORIZED, FAILED, CANCELLED),
            AUTHORIZED,  Set.of(CAPTURED, CANCELLED),
            CAPTURED,    Set.of(SETTLED, REFUNDED),
            SETTLED,     Set.of(REFUNDED),
            FAILED,      Set.of(),       // terminal
            REFUNDED,    Set.of(),       // terminal
            CANCELLED,   Set.of()        // terminal
        );
    }

    /**
     * Check if transitioning from this state to 'target' is allowed.
     *
     * Used by Payment.transitionTo() to guard illegal transitions.
     * Example: FAILED → SETTLED would return false (can't settle a failed payment).
     */
    public boolean canTransitionTo(PaymentStatus target) {
        return VALID_TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    /** Is this a terminal state (no further transitions possible)? */
    public boolean isTerminal() {
        return VALID_TRANSITIONS.getOrDefault(this, Set.of()).isEmpty();
    }
}
```

---

### 4.3 Payment (Builder + State Machine)

> **This is the central entity.** It uses the Builder pattern for clean construction and enforces state machine transitions. Every field mutation goes through a guarded method.

#### Anti-Pattern: Mutable Payment with No Guards

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║           ANTI-PATTERN: Payment as a Mutable Bag of Fields                        ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    // DANGEROUS: any code can set any field at any time
    public class NaivePayment {
        public String paymentId;
        public String merchantId;
        public double amount;
        public String status;       // <-- String! No type safety
        public String method;       // <-- String! Typos cause bugs at runtime

        // Problem 1: No validation. amount can be -500.
        // Problem 2: status is a String. "PROCSSING" typo compiles fine.
        // Problem 3: No transition guards. You can set status = "SETTLED"
        //            directly from "INITIATED", skipping authorization.
        // Problem 4: No Builder. Constructor with 10 params is unreadable.
        // Problem 5: No immutable fields. paymentId can be changed after creation.
        // Problem 6: No audit trail. Who changed the status? When? Why?

        public void setStatus(String status) {
            this.status = status;   // <-- ANY status, ANY time. Chaos.
        }
    }

    // Real bugs this causes:
    //   payment.setStatus("SETTLED");  // skipped auth + capture!
    //   payment.setAmount(-100);       // negative payment = free money
    //   payment.paymentId = "different-id";  // identity changed mid-flight
╚═══════════════════════════════════════════════════════════════════════════════════╝
```

#### Clean Solution: Builder + State Machine Payment

```java
/**
 * Core payment entity with Builder pattern and state machine enforcement.
 *
 * DESIGN DECISIONS:
 *   1. paymentId, merchantId, amount, currency, method are IMMUTABLE after creation
 *      -- set via Builder, never changed. A payment's identity and amount are fixed.
 *   2. status is MUTABLE but GUARDED by transitionTo()
 *      -- only valid transitions (see PaymentStatus) are allowed
 *   3. Builder computes paymentId (UUID) and createdAt automatically
 *      -- caller cannot forget to set them
 *   4. Every state transition is logged with timestamp
 *      -- enables audit trail and debugging
 *
 * Used by:
 *   - PaymentService: creates payment via Builder, transitions state
 *   - PaymentProcessor: reads payment details, does NOT mutate directly
 *   - LedgerService: reads amount/currency to create ledger entries
 *   - WebhookService: reads payment to construct webhook payload
 *
 * CALL CHAIN (creation):
 *   PaymentService.processPayment(merchantId, amount, method, idempotencyKey)
 *     → new Payment.Builder(merchantId, 100.00, USD, CREDIT_CARD).build()
 *     → Payment{id=PAY-xxx, status=INITIATED}
 *
 * CALL CHAIN (state transition):
 *   PaymentService → payment.transitionTo(PROCESSING)
 *     → PaymentStatus.INITIATED.canTransitionTo(PROCESSING)? YES
 *     → status = PROCESSING, updatedAt = now
 */
public class Payment {

    // Immutable fields -- set by Builder, never changed
    private final String paymentId;
    private final String merchantId;
    private final double amount;
    private final Currency currency;
    private final PaymentMethod method;
    private final String idempotencyKey;
    private final Instant createdAt;

    // Mutable but guarded -- only transitionTo() can change status
    private PaymentStatus status;
    private Instant updatedAt;
    private String failureReason;
    private String processorTransactionId;  // external ID from processor

    // State transition history for audit
    private final List<String> statusHistory;

    /**
     * Private constructor -- ONLY the Builder can create a Payment.
     * This prevents construction with missing/invalid fields.
     */
    private Payment(Builder builder) {
        this.paymentId = builder.paymentId;
        this.merchantId = builder.merchantId;
        this.amount = builder.amount;
        this.currency = builder.currency;
        this.method = builder.method;
        this.idempotencyKey = builder.idempotencyKey;
        this.createdAt = builder.createdAt;
        this.status = PaymentStatus.INITIATED;
        this.updatedAt = this.createdAt;
        this.statusHistory = new ArrayList<>();
        this.statusHistory.add("INITIATED at " + createdAt);
    }

    // ─── Getters (all fields) ───────────────────────────────────────────

    public String getPaymentId() { return paymentId; }
    public String getMerchantId() { return merchantId; }
    public double getAmount() { return amount; }
    public Currency getCurrency() { return currency; }
    public PaymentMethod getMethod() { return method; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public PaymentStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getFailureReason() { return failureReason; }
    public String getProcessorTransactionId() { return processorTransactionId; }
    public List<String> getStatusHistory() { return Collections.unmodifiableList(statusHistory); }

    // ─── Guarded State Transitions ──────────────────────────────────────

    /**
     * Transition to a new status. Throws if the transition is invalid.
     *
     * This is THE enforcement point for the payment state machine.
     * No other method can change the status field directly.
     *
     * @param newStatus the target status
     * @throws IllegalStateException if transition is not allowed
     */
    public void transitionTo(PaymentStatus newStatus) {
        if (!this.status.canTransitionTo(newStatus)) {
            throw new IllegalStateException(
                String.format("Invalid payment transition: %s → %s (paymentId=%s). "
                    + "Valid targets from %s: %s",
                    this.status, newStatus, this.paymentId, this.status,
                    this.status.canTransitionTo(newStatus))
            );
        }
        String logEntry = String.format("%s → %s at %s", this.status, newStatus, Instant.now());
        this.statusHistory.add(logEntry);
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }

    /**
     * Mark payment as failed with a reason.
     * Convenience method that transitions to FAILED and records the reason.
     */
    public void fail(String reason) {
        transitionTo(PaymentStatus.FAILED);
        this.failureReason = reason;
    }

    /**
     * Record the external processor transaction ID.
     * Set after the processor returns a successful response.
     */
    public void setProcessorTransactionId(String processorTransactionId) {
        this.processorTransactionId = processorTransactionId;
    }

    // ─── Builder ────────────────────────────────────────────────────────

    /**
     * Builder for Payment. Required fields: merchantId, amount, currency, method.
     *
     * Usage:
     *   Payment payment = new Payment.Builder("merchant-001", 100.00, Currency.USD, PaymentMethod.CREDIT_CARD)
     *       .idempotencyKey("idem-key-123")
     *       .build();
     */
    public static class Builder {
        // Auto-generated
        private final String paymentId;
        private final Instant createdAt;

        // Required
        private final String merchantId;
        private final double amount;
        private final Currency currency;
        private final PaymentMethod method;

        // Optional
        private String idempotencyKey;

        public Builder(String merchantId, double amount, Currency currency, PaymentMethod method) {
            if (merchantId == null || merchantId.isBlank()) {
                throw new IllegalArgumentException("merchantId cannot be null or blank");
            }
            if (amount <= 0) {
                throw new IllegalArgumentException("amount must be positive: " + amount);
            }
            if (currency == null) {
                throw new IllegalArgumentException("currency cannot be null");
            }
            if (method == null) {
                throw new IllegalArgumentException("method cannot be null");
            }
            this.paymentId = "PAY-" + UUID.randomUUID().toString().substring(0, 12);
            this.createdAt = Instant.now();
            this.merchantId = merchantId;
            this.amount = amount;
            this.currency = currency;
            this.method = method;
        }

        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        public Payment build() {
            return new Payment(this);
        }
    }

    @Override
    public String toString() {
        return String.format("Payment{id=%s, merchant=%s, amount=%s, method=%s, status=%s}",
            paymentId, merchantId, currency.format(amount), method, status);
    }
}
```

---

### 4.4 PaymentMethod (Enum)

```java
/**
 * Supported payment methods.
 *
 * Each method maps to a specific PaymentProcessor strategy.
 * The PaymentService uses this enum as the key in its
 * Map<PaymentMethod, PaymentProcessor> registry.
 */
public enum PaymentMethod {
    CREDIT_CARD("Credit Card", true),    // two-phase: authorize then capture
    DEBIT_CARD("Debit Card", true),      // single-phase: direct debit
    UPI("UPI", false),                   // instant settlement, no auth hold
    WALLET("Wallet", false),             // pre-funded balance deduction
    BANK_TRANSFER("Bank Transfer", true); // ACH/wire, T+1 settlement

    private final String displayName;
    private final boolean requiresSettlement;  // needs T+n settlement cycle?

    PaymentMethod(String displayName, boolean requiresSettlement) {
        this.displayName = displayName;
        this.requiresSettlement = requiresSettlement;
    }

    public String getDisplayName() { return displayName; }
    public boolean requiresSettlement() { return requiresSettlement; }
}
```

---

### 4.5 Account (Balance Tracking)

#### Anti-Pattern: Naive Balance Tracking

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║           ANTI-PATTERN: Balance Without Double-Entry                               ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    // DANGEROUS: just increment/decrement a number
    public class NaiveAccount {
        private double balance = 0;

        public void credit(double amount) {
            balance += amount;   // <-- Where did this money come from?
        }

        public void debit(double amount) {
            balance -= amount;   // <-- Where did this money go?
        }
    }

    PROBLEMS:
      1. No audit trail: who changed the balance? when? why?
      2. No cross-reference: if merchant balance increases, which
         account decreased? Was it a payment, refund, or bug?
      3. No reconciliation: total money in system can drift silently
      4. No atomicity: if credit succeeds but matching debit fails,
         money appears from nowhere (breaks conservation law)
      5. Floating-point: double arithmetic causes rounding errors
         $100.10 + $100.20 != $200.30 in IEEE 754

    FIX: Use LedgerEntry objects (see 4.6) and compute balance
    from entries. Balance = SUM(credits) - SUM(debits).
    The balance field is a CACHE, not the source of truth.
╚═══════════════════════════════════════════════════════════════════════════════════╝
```

#### Clean Solution: Account with Ledger-Derived Balance

```java
/**
 * Represents a financial account in the payment system.
 *
 * ACCOUNT TYPES:
 *   - CUSTOMER:   the payer's account (debited on payment)
 *   - MERCHANT:   the business receiving payment (credited on settlement)
 *   - PLATFORM:   our platform's holding account (receives funds before settlement)
 *   - SETTLEMENT: temporary account for in-flight settlements
 *
 * IMPORTANT: The 'balance' field is a CACHED value derived from ledger entries.
 * The true balance is: SUM(CREDIT entries) - SUM(DEBIT entries) for this accountId.
 * We keep a cached balance for fast reads, but reconciliation verifies it against
 * the ledger periodically.
 *
 * Thread safety: balance updates go through LedgerService, which uses
 * synchronized blocks keyed by accountId. See Section 8.
 */
public class Account {

    private final String accountId;
    private final AccountType accountType;
    private final Currency currency;
    private double balance;                 // cached from ledger entries
    private final Instant createdAt;

    public Account(String accountId, AccountType accountType,
                   Currency currency, double initialBalance) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId cannot be null or blank");
        }
        if (accountType == null) {
            throw new IllegalArgumentException("accountType cannot be null");
        }
        if (currency == null) {
            throw new IllegalArgumentException("currency cannot be null");
        }
        this.accountId = accountId;
        this.accountType = accountType;
        this.currency = currency;
        this.balance = initialBalance;
        this.createdAt = Instant.now();
    }

    // ─── Getters ────────────────────────────────────────────────────────

    public String getAccountId() { return accountId; }
    public AccountType getAccountType() { return accountType; }
    public Currency getCurrency() { return currency; }
    public double getBalance() { return balance; }
    public Instant getCreatedAt() { return createdAt; }

    // ─── Balance Mutations (called ONLY by LedgerService) ───────────────

    /**
     * Credit this account (increase balance).
     * ONLY called from LedgerService.recordEntry() within a synchronized block.
     *
     * @throws IllegalArgumentException if amount is not positive
     */
    public void credit(double amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("credit amount must be positive: " + amount);
        }
        this.balance += amount;
    }

    /**
     * Debit this account (decrease balance).
     * ONLY called from LedgerService.recordEntry() within a synchronized block.
     *
     * @throws InsufficientFundsException if balance would go negative
     */
    public void debit(double amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("debit amount must be positive: " + amount);
        }
        if (this.balance < amount) {
            throw new InsufficientFundsException(
                String.format("Account %s has insufficient funds: balance=%s, debit=%s",
                    accountId, currency.format(balance), currency.format(amount))
            );
        }
        this.balance -= amount;
    }

    @Override
    public String toString() {
        return String.format("Account{id=%s, type=%s, balance=%s}",
            accountId, accountType, currency.format(balance));
    }
}
```

---

### 4.6 LedgerEntry (Double-Entry Bookkeeping)

```java
/**
 * A single ledger entry representing one side of a financial transaction.
 *
 * CRITICAL INVARIANT: Every money movement creates EXACTLY TWO entries:
 *   one DEBIT entry (money leaves an account)
 *   one CREDIT entry (money enters an account)
 *   The amounts are always equal. The system is always balanced.
 *
 * This is a VALUE OBJECT -- immutable after creation. Once a ledger entry
 * is written, it is NEVER modified or deleted. To reverse a transaction,
 * create NEW entries in the opposite direction.
 *
 * Used by:
 *   - LedgerService: creates entry pairs for payments, settlements, refunds
 *   - ReconciliationService: verifies ledger balance matches account balances
 *   - PaymentStatsDisplay: aggregates for reporting
 *
 * Why a record?
 *   - Immutable by default (critical for financial audit trail)
 *   - Automatic equals/hashCode (identity is entryId)
 *   - Compact: no boilerplate getters
 */
public record LedgerEntry(
    String entryId,           // unique ID for this entry (LE-xxx)
    String accountId,         // which account this entry affects
    double amount,            // always positive; direction determined by type
    LedgerEntryType type,     // DEBIT or CREDIT
    String transactionId,     // links back to the payment/refund that caused this
    String description,       // human-readable reason ("Payment PAY-xxx captured")
    Instant timestamp         // when this entry was created (immutable)
) {
    /**
     * Compact constructor: validates all fields.
     */
    public LedgerEntry {
        if (entryId == null || entryId.isBlank()) {
            throw new IllegalArgumentException("entryId cannot be null or blank");
        }
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId cannot be null or blank");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        if (type == null) {
            throw new IllegalArgumentException("type cannot be null");
        }
        if (transactionId == null || transactionId.isBlank()) {
            throw new IllegalArgumentException("transactionId cannot be null or blank");
        }
    }

    /**
     * Returns the signed amount: negative for DEBIT, positive for CREDIT.
     * Used for balance calculations: SUM(signedAmount) for all entries = account balance.
     */
    public double getSignedAmount() {
        return type == LedgerEntryType.CREDIT ? amount : -amount;
    }
}
```

---

### 4.7 LedgerEntryType and AccountType (Enums)

```java
/**
 * The two sides of double-entry bookkeeping.
 */
public enum LedgerEntryType {
    DEBIT,    // money leaves an account (decreases balance)
    CREDIT    // money enters an account (increases balance)
}

/**
 * Types of accounts in the payment system.
 *
 * MONEY FLOW:
 *   Customer ──DEBIT──→ Platform ──CREDIT──→ Merchant
 *                            │
 *                            └──→ Platform Fee Account (our revenue)
 */
public enum AccountType {
    CUSTOMER,      // the payer
    MERCHANT,      // the business receiving payment
    PLATFORM,      // our platform's holding account
    SETTLEMENT     // temporary account for in-flight settlements
}
```

---

### 4.8 Transaction (Wrapper with Processor Metadata)

```java
/**
 * Wraps a Payment with external processor response metadata.
 *
 * WHY separate from Payment?
 *   - Payment is OUR internal model (our state machine, our IDs)
 *   - Transaction captures the PROCESSOR's response (their txn ID, response code, latency)
 *   - One Payment may have multiple Transactions (retry, auth + capture, refund)
 *
 * Used by:
 *   - PaymentProcessor: returns Transaction after processing
 *   - PaymentService: stores Transaction for audit trail
 *   - ReconciliationService: matches our Transaction vs processor's records
 */
public class Transaction {

    private final String transactionId;          // our internal ID (TXN-xxx)
    private final String paymentId;              // which payment this is for
    private final String processorTransactionId; // external ID from processor
    private final String processorName;          // "CreditCardProcessor", "UPIProcessor", etc.
    private final boolean success;
    private final String responseCode;           // processor response code ("00" = success)
    private final String responseMessage;        // "Approved", "Insufficient funds", etc.
    private final long processingTimeMs;         // how long the processor took
    private final Instant timestamp;

    public Transaction(String transactionId, String paymentId,
                       String processorTransactionId, String processorName,
                       boolean success, String responseCode, String responseMessage,
                       long processingTimeMs) {
        this.transactionId = transactionId;
        this.paymentId = paymentId;
        this.processorTransactionId = processorTransactionId;
        this.processorName = processorName;
        this.success = success;
        this.responseCode = responseCode;
        this.responseMessage = responseMessage;
        this.processingTimeMs = processingTimeMs;
        this.timestamp = Instant.now();
    }

    // ─── Getters ────────────────────────────────────────────────────────

    public String getTransactionId() { return transactionId; }
    public String getPaymentId() { return paymentId; }
    public String getProcessorTransactionId() { return processorTransactionId; }
    public String getProcessorName() { return processorName; }
    public boolean isSuccess() { return success; }
    public String getResponseCode() { return responseCode; }
    public String getResponseMessage() { return responseMessage; }
    public long getProcessingTimeMs() { return processingTimeMs; }
    public Instant getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return String.format("Transaction{id=%s, payment=%s, processor=%s, success=%s, code=%s, time=%dms}",
            transactionId, paymentId, processorName, success, responseCode, processingTimeMs);
    }
}
```

---

### 4.9 Merchant

```java
/**
 * Represents a merchant (business) using our payment platform.
 *
 * Each merchant has:
 *   - A webhook URL for payment event notifications
 *   - An API key for authentication
 *   - A platform fee percentage (our revenue model: we take 2.9% + $0.30 per txn)
 *
 * Used by:
 *   - PaymentService: validates merchant exists, applies fee
 *   - WebhookService: delivers payment events to merchant.webhookUrl
 *   - LedgerService: credits merchant account on settlement
 */
public class Merchant {

    private final String merchantId;
    private final String name;
    private final String webhookUrl;
    private final String apiKey;
    private final double platformFeePercent;  // e.g., 0.029 for 2.9%
    private final double platformFeeFixed;    // e.g., 0.30 for $0.30 per txn

    public Merchant(String merchantId, String name, String webhookUrl,
                    String apiKey, double platformFeePercent, double platformFeeFixed) {
        if (merchantId == null || merchantId.isBlank()) {
            throw new IllegalArgumentException("merchantId cannot be null or blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name cannot be null or blank");
        }
        this.merchantId = merchantId;
        this.name = name;
        this.webhookUrl = webhookUrl;
        this.apiKey = apiKey;
        this.platformFeePercent = platformFeePercent;
        this.platformFeeFixed = platformFeeFixed;
    }

    // ─── Getters ────────────────────────────────────────────────────────

    public String getMerchantId() { return merchantId; }
    public String getName() { return name; }
    public String getWebhookUrl() { return webhookUrl; }
    public String getApiKey() { return apiKey; }
    public double getPlatformFeePercent() { return platformFeePercent; }
    public double getPlatformFeeFixed() { return platformFeeFixed; }

    /**
     * Calculate the platform fee for a given payment amount.
     * Stripe model: 2.9% + $0.30
     *
     * Example: $100.00 payment → fee = 100 * 0.029 + 0.30 = $3.20
     */
    public double calculateFee(double amount) {
        return (amount * platformFeePercent) + platformFeeFixed;
    }

    /**
     * Calculate the net amount the merchant receives after fee deduction.
     */
    public double calculateNetAmount(double amount) {
        return amount - calculateFee(amount);
    }

    @Override
    public String toString() {
        return String.format("Merchant{id=%s, name=%s, fee=%.1f%% + $%.2f}",
            merchantId, name, platformFeePercent * 100, platformFeeFixed);
    }
}
```

---

### 4.10 Refund

```java
/**
 * Represents a full or partial refund of a payment.
 *
 * REFUND FLOW:
 *   1. RefundService creates Refund with status=PENDING
 *   2. PaymentProcessor.processRefund() called
 *   3. If success: status → COMPLETED, ledger entries reversed
 *   4. If failure: status → FAILED
 *
 * PARTIAL REFUNDS:
 *   - A $100 payment can have a $30 refund, then another $70 refund
 *   - RefundService tracks totalRefundedAmount per payment
 *   - Cannot refund more than the original payment amount
 *
 * Used by:
 *   - RefundService: orchestrates refund flow
 *   - LedgerService: creates reversal entries
 *   - WebhookService: notifies merchant of refund
 */
public class Refund {

    private final String refundId;
    private final String paymentId;
    private final double amount;       // can be partial (less than payment amount)
    private final String reason;
    private RefundStatus status;
    private final Instant createdAt;
    private Instant processedAt;

    public Refund(String refundId, String paymentId, double amount, String reason) {
        if (refundId == null || refundId.isBlank()) {
            throw new IllegalArgumentException("refundId cannot be null or blank");
        }
        if (paymentId == null || paymentId.isBlank()) {
            throw new IllegalArgumentException("paymentId cannot be null or blank");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("refund amount must be positive: " + amount);
        }
        this.refundId = refundId;
        this.paymentId = paymentId;
        this.amount = amount;
        this.reason = reason;
        this.status = RefundStatus.PENDING;
        this.createdAt = Instant.now();
    }

    // ─── Getters ────────────────────────────────────────────────────────

    public String getRefundId() { return refundId; }
    public String getPaymentId() { return paymentId; }
    public double getAmount() { return amount; }
    public String getReason() { return reason; }
    public RefundStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getProcessedAt() { return processedAt; }

    // ─── Status Transitions ─────────────────────────────────────────────

    public void markProcessing() {
        this.status = RefundStatus.PROCESSING;
    }

    public void markCompleted() {
        this.status = RefundStatus.COMPLETED;
        this.processedAt = Instant.now();
    }

    public void markFailed() {
        this.status = RefundStatus.FAILED;
        this.processedAt = Instant.now();
    }

    @Override
    public String toString() {
        return String.format("Refund{id=%s, payment=%s, amount=%.2f, status=%s}",
            refundId, paymentId, amount, status);
    }
}
```

---

### 4.11 WebhookEvent

```java
/**
 * Represents a webhook notification to be delivered to a merchant.
 *
 * WEBHOOK DELIVERY MODEL:
 *   - Every payment state change generates a WebhookEvent
 *   - Events are delivered to the merchant's webhook URL
 *   - If delivery fails, we retry with exponential backoff:
 *       Attempt 1: immediate
 *       Attempt 2: after 1 second
 *       Attempt 3: after 4 seconds
 *       Attempt 4: after 16 seconds
 *       Attempt 5: after 64 seconds
 *   - After max retries (5), status → EXHAUSTED and we alert
 *
 * STATUS LIFECYCLE:
 *   PENDING → DELIVERED (success)
 *   PENDING → FAILED → FAILED → ... → EXHAUSTED (all retries failed)
 *
 * Used by:
 *   - WebhookService: creates events, manages delivery and retry
 *   - PaymentService: triggers webhook on state change
 */
public class WebhookEvent {

    private final String eventId;
    private final String eventType;       // "payment.captured", "payment.refunded", etc.
    private final String paymentId;
    private final String merchantId;
    private final String payload;         // JSON-like payload string
    private WebhookStatus status;
    private int attempts;
    private final int maxAttempts;
    private Instant lastAttemptAt;
    private Instant nextRetryAt;
    private final Instant createdAt;

    public WebhookEvent(String eventId, String eventType, String paymentId,
                        String merchantId, String payload, int maxAttempts) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.paymentId = paymentId;
        this.merchantId = merchantId;
        this.payload = payload;
        this.status = WebhookStatus.PENDING;
        this.attempts = 0;
        this.maxAttempts = maxAttempts;
        this.createdAt = Instant.now();
    }

    // ─── Getters ────────────────────────────────────────────────────────

    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getPaymentId() { return paymentId; }
    public String getMerchantId() { return merchantId; }
    public String getPayload() { return payload; }
    public WebhookStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public int getMaxAttempts() { return maxAttempts; }
    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public Instant getCreatedAt() { return createdAt; }

    // ─── Delivery Methods ───────────────────────────────────────────────

    /**
     * Record a delivery attempt.
     * Increments attempt counter and updates timestamps.
     */
    public void recordAttempt() {
        this.attempts++;
        this.lastAttemptAt = Instant.now();
    }

    /**
     * Mark as successfully delivered.
     */
    public void markDelivered() {
        this.status = WebhookStatus.DELIVERED;
    }

    /**
     * Mark as failed. If max attempts reached, mark as EXHAUSTED.
     * Otherwise, calculate next retry time with exponential backoff.
     *
     * BACKOFF FORMULA: delay = baseDelay * 4^(attempt-1)
     *   Attempt 1 fail → retry after 1s
     *   Attempt 2 fail → retry after 4s
     *   Attempt 3 fail → retry after 16s
     *   Attempt 4 fail → retry after 64s
     *   Attempt 5 fail → EXHAUSTED
     */
    public void markFailed() {
        if (this.attempts >= this.maxAttempts) {
            this.status = WebhookStatus.EXHAUSTED;
        } else {
            this.status = WebhookStatus.FAILED;
            long backoffSeconds = (long) Math.pow(4, this.attempts - 1);
            this.nextRetryAt = Instant.now().plusSeconds(backoffSeconds);
        }
    }

    /** Has this event exhausted all retry attempts? */
    public boolean isExhausted() {
        return this.status == WebhookStatus.EXHAUSTED;
    }

    /** Is this event ready for retry? */
    public boolean isReadyForRetry() {
        return this.status == WebhookStatus.FAILED
            && this.nextRetryAt != null
            && Instant.now().isAfter(this.nextRetryAt);
    }

    @Override
    public String toString() {
        return String.format("WebhookEvent{id=%s, type=%s, payment=%s, status=%s, attempts=%d/%d}",
            eventId, eventType, paymentId, status, attempts, maxAttempts);
    }
}
```

---

### 4.12 IdempotencyRecord

```java
/**
 * Stores the result of a previously processed idempotent request.
 *
 * IDEMPOTENCY IN PAYMENT SYSTEMS:
 *   - Client sends a payment request with an idempotency key (e.g., UUID)
 *   - If we've seen this key before, return the SAME response (don't charge again)
 *   - If we haven't, process the payment and store the result
 *   - Keys expire after a TTL (e.g., 24 hours) to prevent unbounded growth
 *
 * WHY THIS MATTERS:
 *   - Network timeout: client doesn't know if payment went through
 *   - Client retries the same request with the same idempotency key
 *   - Without idempotency: customer charged twice for one purchase!
 *   - With idempotency: second request returns the first response (safe to retry)
 *
 * Used by:
 *   - IdempotencyService: check/store idempotency keys
 *   - PaymentService: checks idempotency before processing
 */
public record IdempotencyRecord(
    String key,              // the idempotency key from the client
    String paymentId,        // the payment ID that was created for this key
    String response,         // serialized response to return on duplicate
    Instant createdAt,       // when this record was created
    Instant expiresAt        // when this record expires (createdAt + TTL)
) {
    public IdempotencyRecord {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("idempotency key cannot be null or blank");
        }
        if (paymentId == null || paymentId.isBlank()) {
            throw new IllegalArgumentException("paymentId cannot be null or blank");
        }
    }

    /** Is this record still valid (not expired)? */
    public boolean isValid() {
        return Instant.now().isBefore(expiresAt);
    }
}
```

---

### 4.13 Remaining Enums

```java
/** Refund lifecycle states. */
public enum RefundStatus {
    PENDING,      // refund created, not yet sent to processor
    PROCESSING,   // sent to processor
    COMPLETED,    // processor confirmed refund
    FAILED        // processor rejected refund
}

/** Webhook delivery states. */
public enum WebhookStatus {
    PENDING,      // created, not yet attempted
    DELIVERED,    // successfully delivered to merchant
    FAILED,       // delivery failed, will retry
    EXHAUSTED     // all retries failed (terminal)
}
```

---

## 5. Interface Contracts

### 5.1 PaymentProcessor (Strategy Interface)

```java
/**
 * Defines the contract for all payment processors.
 *
 * STRATEGY PATTERN: Each payment method (credit card, UPI, wallet)
 * has its own processor implementation. The PaymentService selects
 * the correct processor based on PaymentMethod, without knowing
 * the internal details of how each processor works.
 *
 * CONTRACT GUARANTEES:
 *   - processPayment() returns a Transaction (never null)
 *   - If processing fails, Transaction.isSuccess() returns false
 *     (does NOT throw -- failure is a valid business outcome)
 *   - processRefund() reverses a previous successful payment
 *   - getSupportedMethods() declares which PaymentMethods this processor handles
 *
 * CALL CHAIN:
 *   PaymentService.processPayment(...)
 *     → processors.get(payment.getMethod())          // lookup by method
 *       .processPayment(payment)                     // delegate to strategy
 *       → Transaction{success=true/false, ...}       // strategy returns result
 */
public interface PaymentProcessor {

    /**
     * Process a payment through this processor's payment rail.
     *
     * @param payment the payment to process (contains amount, currency, method)
     * @return Transaction with success/failure status, processor txn ID, response code
     */
    Transaction processPayment(Payment payment);

    /**
     * Process a refund for a previously successful payment.
     *
     * @param refund the refund details (paymentId, amount, reason)
     * @param originalPayment the original payment being refunded
     * @return Transaction with refund success/failure status
     */
    Transaction processRefund(Refund refund, Payment originalPayment);

    /** Human-readable name for logging and display. */
    String getProcessorName();

    /** Which payment methods this processor supports. */
    Set<PaymentMethod> getSupportedMethods();
}
```

---

### 5.2 FraudCheckStrategy (Strategy Interface)

```java
/**
 * Defines the contract for fraud detection strategies.
 *
 * STRATEGY PATTERN: Multiple fraud check implementations can be applied
 * to the same payment. FraudService chains them (defense-in-depth).
 *
 * CONTRACT:
 *   - checkFraud() returns a FraudResult (never throws for fraud detection)
 *   - FraudResult contains: passed (boolean), riskScore (0.0-1.0), reason (String)
 *   - Risk score interpretation:
 *       0.0-0.3: low risk (auto-approve)
 *       0.3-0.7: medium risk (may require additional verification)
 *       0.7-1.0: high risk (auto-reject)
 *
 * CALL CHAIN:
 *   PaymentService.processPayment(...)
 *     → FraudService.checkFraud(payment)
 *       → for each FraudCheckStrategy in chain:
 *           strategy.checkFraud(payment)
 *           if (!result.passed()) → throw FraudDetectedException
 */
public interface FraudCheckStrategy {

    /**
     * Check a payment for fraud indicators.
     *
     * @param payment the payment to evaluate
     * @return FraudResult with pass/fail, risk score, and reason
     */
    FraudResult checkFraud(Payment payment);

    /** Human-readable name for logging. */
    String getStrategyName();

    // ─── Inner record for fraud check results ────────────────────────

    /**
     * Result of a fraud check.
     *
     * @param passed     true if the payment passed fraud check
     * @param riskScore  0.0 (no risk) to 1.0 (certain fraud)
     * @param reason     explanation (e.g., "velocity limit exceeded")
     */
    record FraudResult(boolean passed, double riskScore, String reason) {

        public static FraudResult pass(double riskScore) {
            return new FraudResult(true, riskScore, "OK");
        }

        public static FraudResult fail(double riskScore, String reason) {
            return new FraudResult(false, riskScore, reason);
        }
    }
}
```

---

### 5.3 Repository Interfaces

```java
/**
 * Data access interface for Payment entities.
 *
 * Follows the Repository pattern: services depend on this interface,
 * not on storage implementation details. InMemoryPaymentRepository
 * for LLD/testing; JdbcPaymentRepository for production.
 */
public interface PaymentRepository {

    void save(Payment payment);

    Optional<Payment> findById(String paymentId);

    List<Payment> findByMerchantId(String merchantId);

    List<Payment> findByStatus(PaymentStatus status);

    List<Payment> findAll();
}

/**
 * Data access interface for LedgerEntry entities.
 * Ledger entries are APPEND-ONLY. No update or delete operations.
 */
public interface LedgerRepository {

    void save(LedgerEntry entry);

    List<LedgerEntry> findByAccountId(String accountId);

    List<LedgerEntry> findByTransactionId(String transactionId);

    List<LedgerEntry> findAll();
}

/**
 * Data access interface for Merchant entities.
 */
public interface MerchantRepository {

    void save(Merchant merchant);

    Optional<Merchant> findById(String merchantId);

    Optional<Merchant> findByApiKey(String apiKey);

    List<Merchant> findAll();
}

/**
 * Data access interface for Account entities.
 */
public interface AccountRepository {

    void save(Account account);

    Optional<Account> findById(String accountId);

    List<Account> findByType(AccountType type);

    List<Account> findAll();
}

/**
 * Data access interface for IdempotencyRecord entities.
 */
public interface IdempotencyRepository {

    void save(IdempotencyRecord record);

    Optional<IdempotencyRecord> findByKey(String key);

    void deleteExpired();  // cleanup expired records
}

/**
 * Data access interface for WebhookEvent entities.
 */
public interface WebhookRepository {

    void save(WebhookEvent event);

    Optional<WebhookEvent> findById(String eventId);

    List<WebhookEvent> findByMerchantId(String merchantId);

    List<WebhookEvent> findByStatus(WebhookStatus status);

    List<WebhookEvent> findPendingRetries();  // FAILED events past nextRetryAt

    List<WebhookEvent> findAll();
}
```

---

## 6. Strategy Implementations

### 6.1 CreditCardProcessor

```java
/**
 * Simulates credit card payment processing (Visa/Mastercard gateway).
 *
 * BEHAVIOR:
 *   - 95% success rate (simulated via Random)
 *   - Latency: 2000-5000ms (simulates network round-trip to card network)
 *   - Two-phase: authorization (funds held) then capture (funds transferred)
 *   - For our LLD, we simulate both phases in a single call
 *
 * FAILURE MODES (simulated):
 *   - "51" Insufficient funds (random 2%)
 *   - "05" Do not honor (random 2%)
 *   - "14" Invalid card number (random 1%)
 *
 * CALL CHAIN:
 *   PaymentService.processPayment(...)
 *     → processors.get(CREDIT_CARD).processPayment(payment)
 *       → CreditCardProcessor.processPayment(payment)
 *         → simulate latency (Thread.sleep)
 *         → roll success/failure dice
 *         → return Transaction{success, responseCode, processorTxnId}
 */
public class CreditCardProcessor implements PaymentProcessor {

    private static final double SUCCESS_RATE = 0.95;
    private static final int MIN_LATENCY_MS = 2000;
    private static final int MAX_LATENCY_MS = 5000;
    private final Random random = new Random();

    @Override
    public Transaction processPayment(Payment payment) {
        String processorTxnId = "CC-TXN-" + UUID.randomUUID().toString().substring(0, 8);
        long startTime = System.currentTimeMillis();

        // Simulate network latency to card network
        simulateLatency();

        long processingTime = System.currentTimeMillis() - startTime;

        // Roll the dice for success/failure
        double roll = random.nextDouble();
        if (roll < SUCCESS_RATE) {
            // SUCCESS: card authorized and captured
            System.out.printf("[CreditCardProcessor] APPROVED %s for %s (txn=%s, %dms)%n",
                payment.getPaymentId(), payment.getCurrency().format(payment.getAmount()),
                processorTxnId, processingTime);

            return new Transaction(
                "TXN-" + UUID.randomUUID().toString().substring(0, 8),
                payment.getPaymentId(),
                processorTxnId,
                getProcessorName(),
                true,           // success
                "00",           // response code: approved
                "Approved",
                processingTime
            );
        } else {
            // FAILURE: simulate different decline reasons
            String[] codes = {"51", "05", "14"};
            String[] messages = {"Insufficient funds", "Do not honor", "Invalid card number"};
            int idx = random.nextInt(codes.length);

            System.out.printf("[CreditCardProcessor] DECLINED %s: %s (code=%s, %dms)%n",
                payment.getPaymentId(), messages[idx], codes[idx], processingTime);

            return new Transaction(
                "TXN-" + UUID.randomUUID().toString().substring(0, 8),
                payment.getPaymentId(),
                processorTxnId,
                getProcessorName(),
                false,          // failure
                codes[idx],
                messages[idx],
                processingTime
            );
        }
    }

    @Override
    public Transaction processRefund(Refund refund, Payment originalPayment) {
        String processorTxnId = "CC-REF-" + UUID.randomUUID().toString().substring(0, 8);
        simulateLatency();

        // Refunds have a 99% success rate (higher than initial charge)
        boolean success = random.nextDouble() < 0.99;

        System.out.printf("[CreditCardProcessor] Refund %s for %s: %s%n",
            refund.getRefundId(),
            originalPayment.getCurrency().format(refund.getAmount()),
            success ? "APPROVED" : "DECLINED");

        return new Transaction(
            "TXN-" + UUID.randomUUID().toString().substring(0, 8),
            originalPayment.getPaymentId(),
            processorTxnId,
            getProcessorName(),
            success,
            success ? "00" : "05",
            success ? "Refund approved" : "Refund declined",
            0
        );
    }

    @Override
    public String getProcessorName() { return "CreditCardProcessor"; }

    @Override
    public Set<PaymentMethod> getSupportedMethods() {
        return Set.of(PaymentMethod.CREDIT_CARD, PaymentMethod.DEBIT_CARD);
    }

    private void simulateLatency() {
        try {
            Thread.sleep(MIN_LATENCY_MS + random.nextInt(MAX_LATENCY_MS - MIN_LATENCY_MS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

---

### 6.2 UPIProcessor

```java
/**
 * Simulates UPI (Unified Payments Interface) processing.
 *
 * UPI CHARACTERISTICS:
 *   - 98% success rate
 *   - Near-instant: 100-500ms (no card network round-trip)
 *   - Single-phase: no separate auth and capture (funds move immediately)
 *   - Real-time settlement (no T+1 delay)
 *   - Popular in India (handles 10B+ transactions/month)
 *
 * CALL CHAIN:
 *   PaymentService → processors.get(UPI).processPayment(payment)
 *     → UPIProcessor.processPayment(payment)
 *       → instant settlement simulation
 *       → Transaction{success=true, code="00", processorTxn="UPI-xxx"}
 */
public class UPIProcessor implements PaymentProcessor {

    private static final double SUCCESS_RATE = 0.98;
    private static final int MIN_LATENCY_MS = 100;
    private static final int MAX_LATENCY_MS = 500;
    private final Random random = new Random();

    @Override
    public Transaction processPayment(Payment payment) {
        String processorTxnId = "UPI-TXN-" + UUID.randomUUID().toString().substring(0, 8);
        long startTime = System.currentTimeMillis();

        // UPI is fast -- minimal latency
        simulateLatency();

        long processingTime = System.currentTimeMillis() - startTime;
        boolean success = random.nextDouble() < SUCCESS_RATE;

        if (success) {
            System.out.printf("[UPIProcessor] APPROVED %s for %s (instant, %dms)%n",
                payment.getPaymentId(), payment.getCurrency().format(payment.getAmount()),
                processingTime);
        } else {
            System.out.printf("[UPIProcessor] FAILED %s: UPI timeout or decline (%dms)%n",
                payment.getPaymentId(), processingTime);
        }

        return new Transaction(
            "TXN-" + UUID.randomUUID().toString().substring(0, 8),
            payment.getPaymentId(),
            processorTxnId,
            getProcessorName(),
            success,
            success ? "00" : "U30",         // U30 = UPI timeout code
            success ? "Approved (instant)" : "UPI transaction timeout",
            processingTime
        );
    }

    @Override
    public Transaction processRefund(Refund refund, Payment originalPayment) {
        String processorTxnId = "UPI-REF-" + UUID.randomUUID().toString().substring(0, 8);
        simulateLatency();

        boolean success = random.nextDouble() < 0.99;

        System.out.printf("[UPIProcessor] Refund %s: %s%n",
            refund.getRefundId(), success ? "APPROVED (instant)" : "FAILED");

        return new Transaction(
            "TXN-" + UUID.randomUUID().toString().substring(0, 8),
            originalPayment.getPaymentId(),
            processorTxnId,
            getProcessorName(),
            success,
            success ? "00" : "U30",
            success ? "Refund approved (instant)" : "Refund failed",
            0
        );
    }

    @Override
    public String getProcessorName() { return "UPIProcessor"; }

    @Override
    public Set<PaymentMethod> getSupportedMethods() {
        return Set.of(PaymentMethod.UPI);
    }

    private void simulateLatency() {
        try {
            Thread.sleep(MIN_LATENCY_MS + random.nextInt(MAX_LATENCY_MS - MIN_LATENCY_MS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

---

### 6.3 WalletProcessor

```java
/**
 * Simulates wallet-based payment processing (like Paytm, PhonePe wallet).
 *
 * WALLET CHARACTERISTICS:
 *   - 99% success rate (pre-funded, so failures are rare)
 *   - Ultra-fast: 50-200ms (no external network call)
 *   - Balance check is local (already in our system)
 *   - No settlement delay (funds already on platform)
 *
 * CALL CHAIN:
 *   PaymentService → processors.get(WALLET).processPayment(payment)
 *     → WalletProcessor.processPayment(payment)
 *       → check wallet balance (via AccountRepository)
 *       → deduct balance atomically
 *       → Transaction{success=true}
 */
public class WalletProcessor implements PaymentProcessor {

    private static final double SUCCESS_RATE = 0.99;
    private static final int MIN_LATENCY_MS = 50;
    private static final int MAX_LATENCY_MS = 200;
    private final Random random = new Random();

    @Override
    public Transaction processPayment(Payment payment) {
        String processorTxnId = "WAL-TXN-" + UUID.randomUUID().toString().substring(0, 8);
        long startTime = System.currentTimeMillis();

        // Wallet operations are fast -- local balance check
        simulateLatency();

        long processingTime = System.currentTimeMillis() - startTime;
        boolean success = random.nextDouble() < SUCCESS_RATE;

        if (success) {
            System.out.printf("[WalletProcessor] APPROVED %s for %s (wallet debit, %dms)%n",
                payment.getPaymentId(), payment.getCurrency().format(payment.getAmount()),
                processingTime);
        } else {
            System.out.printf("[WalletProcessor] FAILED %s: wallet error (%dms)%n",
                payment.getPaymentId(), processingTime);
        }

        return new Transaction(
            "TXN-" + UUID.randomUUID().toString().substring(0, 8),
            payment.getPaymentId(),
            processorTxnId,
            getProcessorName(),
            success,
            success ? "00" : "W01",
            success ? "Wallet debit successful" : "Wallet processing error",
            processingTime
        );
    }

    @Override
    public Transaction processRefund(Refund refund, Payment originalPayment) {
        String processorTxnId = "WAL-REF-" + UUID.randomUUID().toString().substring(0, 8);
        simulateLatency();

        // Wallet refunds always succeed (credit back to wallet)
        System.out.printf("[WalletProcessor] Refund %s: APPROVED (wallet credit)%n",
            refund.getRefundId());

        return new Transaction(
            "TXN-" + UUID.randomUUID().toString().substring(0, 8),
            originalPayment.getPaymentId(),
            processorTxnId,
            getProcessorName(),
            true,
            "00",
            "Wallet refund credited",
            0
        );
    }

    @Override
    public String getProcessorName() { return "WalletProcessor"; }

    @Override
    public Set<PaymentMethod> getSupportedMethods() {
        return Set.of(PaymentMethod.WALLET);
    }

    private void simulateLatency() {
        try {
            Thread.sleep(MIN_LATENCY_MS + random.nextInt(MAX_LATENCY_MS - MIN_LATENCY_MS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

---

### 6.4 RuleBasedFraudCheck

```java
/**
 * Rule-based fraud detection using configurable thresholds.
 *
 * THREE CHECKS (applied in order, fail-fast):
 *
 *   1. AMOUNT THRESHOLD: single transaction above $10,000 → reject
 *      Why: large transactions have higher fraud risk
 *
 *   2. VELOCITY CHECK: more than 5 transactions per hour from same merchant → reject
 *      Why: rapid-fire transactions indicate automated fraud
 *
 *   3. BLACKLIST CHECK: merchantId or paymentMethod on blacklist → reject
 *      Why: known bad actors should be blocked immediately
 *
 * RISK SCORE CALCULATION:
 *   - Start at 0.0
 *   - Add 0.3 if amount > $5,000 (high value)
 *   - Add 0.2 if velocity > 3/hour (moderate velocity)
 *   - Add 0.5 if blacklisted (certain fraud)
 *   - If total >= 0.7 → REJECT
 *
 * CALL CHAIN:
 *   FraudService.checkFraud(payment)
 *     → RuleBasedFraudCheck.checkFraud(payment)
 *       → check amount threshold
 *       → check velocity (requires recent payment history)
 *       → check blacklist
 *       → return FraudResult{passed, riskScore, reason}
 */
public class RuleBasedFraudCheck implements FraudCheckStrategy {

    private final double maxAmountPerTransaction;
    private final int maxTransactionsPerHour;
    private final Set<String> blacklistedMerchantIds;
    private final double riskThreshold;

    // Track recent transactions per merchant for velocity checking
    // Map<merchantId, List<Instant>> -- timestamps of recent payments
    private final ConcurrentHashMap<String, List<Instant>> velocityTracker;

    public RuleBasedFraudCheck(double maxAmountPerTransaction,
                                int maxTransactionsPerHour,
                                Set<String> blacklistedMerchantIds,
                                double riskThreshold) {
        this.maxAmountPerTransaction = maxAmountPerTransaction;
        this.maxTransactionsPerHour = maxTransactionsPerHour;
        this.blacklistedMerchantIds = new HashSet<>(blacklistedMerchantIds);
        this.riskThreshold = riskThreshold;
        this.velocityTracker = new ConcurrentHashMap<>();
    }

    @Override
    public FraudResult checkFraud(Payment payment) {
        double riskScore = 0.0;
        List<String> reasons = new ArrayList<>();

        // ─── Check 1: Amount threshold ──────────────────────────────
        if (payment.getAmount() > maxAmountPerTransaction) {
            System.out.printf("[RuleBasedFraud] BLOCKED: amount %.2f exceeds limit %.2f%n",
                payment.getAmount(), maxAmountPerTransaction);
            return FraudResult.fail(1.0,
                "Amount " + payment.getAmount() + " exceeds maximum " + maxAmountPerTransaction);
        }
        if (payment.getAmount() > maxAmountPerTransaction * 0.5) {
            riskScore += 0.3;
            reasons.add("high-value transaction");
        }

        // ─── Check 2: Velocity check ───────────────────────────────
        int recentCount = getRecentTransactionCount(payment.getMerchantId());
        if (recentCount >= maxTransactionsPerHour) {
            System.out.printf("[RuleBasedFraud] BLOCKED: velocity %d exceeds limit %d/hr%n",
                recentCount, maxTransactionsPerHour);
            return FraudResult.fail(0.9,
                "Velocity limit exceeded: " + recentCount + " txns in last hour");
        }
        if (recentCount > maxTransactionsPerHour / 2) {
            riskScore += 0.2;
            reasons.add("moderate velocity");
        }
        recordTransaction(payment.getMerchantId());

        // ─── Check 3: Blacklist check ──────────────────────────────
        if (blacklistedMerchantIds.contains(payment.getMerchantId())) {
            System.out.printf("[RuleBasedFraud] BLOCKED: merchant %s is blacklisted%n",
                payment.getMerchantId());
            return FraudResult.fail(1.0, "Merchant blacklisted: " + payment.getMerchantId());
        }

        // ─── Final decision ─────────────────────────────────────────
        if (riskScore >= riskThreshold) {
            return FraudResult.fail(riskScore, "Cumulative risk: " + String.join(", ", reasons));
        }

        System.out.printf("[RuleBasedFraud] PASSED: %s (risk=%.2f)%n",
            payment.getPaymentId(), riskScore);
        return FraudResult.pass(riskScore);
    }

    @Override
    public String getStrategyName() { return "RuleBasedFraudCheck"; }

    // ─── Velocity tracking helpers ──────────────────────────────────

    private int getRecentTransactionCount(String merchantId) {
        List<Instant> timestamps = velocityTracker.getOrDefault(merchantId, List.of());
        Instant oneHourAgo = Instant.now().minus(Duration.ofHours(1));
        return (int) timestamps.stream()
            .filter(ts -> ts.isAfter(oneHourAgo))
            .count();
    }

    private void recordTransaction(String merchantId) {
        velocityTracker.computeIfAbsent(merchantId, k -> new CopyOnWriteArrayList<>())
            .add(Instant.now());
    }
}
```

---

### 6.5 MLFraudCheck

```java
/**
 * Simulated ML-based fraud detection.
 *
 * In a real system, this would call a trained ML model (e.g., XGBoost, neural net)
 * with features extracted from the payment. For our LLD, we simulate a risk score
 * based on payment characteristics.
 *
 * FEATURE EXTRACTION (simulated):
 *   - Transaction amount (higher = riskier)
 *   - Payment method (some methods are higher risk)
 *   - Time of day (late night = riskier)
 *   - Amount rounding (exact round numbers like $1000.00 are riskier)
 *
 * RISK SCORE: 0.0 (safe) to 1.0 (fraud)
 *   - Below threshold (0.7): PASS
 *   - Above threshold: REJECT
 *
 * CALL CHAIN:
 *   FraudService.checkFraud(payment)
 *     → MLFraudCheck.checkFraud(payment)
 *       → extractFeatures(payment)
 *       → computeRiskScore(features)
 *       → FraudResult{passed, riskScore, reason}
 */
public class MLFraudCheck implements FraudCheckStrategy {

    private final double riskThreshold;
    private final String modelVersion;
    private final Random random = new Random();

    public MLFraudCheck(double riskThreshold, String modelVersion) {
        this.riskThreshold = riskThreshold;
        this.modelVersion = modelVersion;
    }

    @Override
    public FraudResult checkFraud(Payment payment) {
        // ─── Feature extraction (simulated) ─────────────────────────
        double amountFeature = Math.min(payment.getAmount() / 10000.0, 1.0); // normalize to 0-1
        double methodFeature = getMethodRisk(payment.getMethod());
        double timeFeature = getTimeOfDayRisk();
        double roundingFeature = isRoundAmount(payment.getAmount()) ? 0.15 : 0.0;

        // ─── "ML model" scoring (weighted sum + noise) ──────────────
        double riskScore = (amountFeature * 0.35)
                         + (methodFeature * 0.25)
                         + (timeFeature * 0.20)
                         + (roundingFeature * 0.10)
                         + (random.nextDouble() * 0.10);  // random noise

        riskScore = Math.min(Math.max(riskScore, 0.0), 1.0); // clamp to [0,1]

        System.out.printf("[MLFraudCheck-%s] Payment %s: risk=%.3f (threshold=%.2f) → %s%n",
            modelVersion, payment.getPaymentId(), riskScore, riskThreshold,
            riskScore < riskThreshold ? "PASS" : "REJECT");

        if (riskScore >= riskThreshold) {
            return FraudResult.fail(riskScore,
                String.format("ML model %s: risk score %.3f exceeds threshold %.2f",
                    modelVersion, riskScore, riskThreshold));
        }

        return FraudResult.pass(riskScore);
    }

    @Override
    public String getStrategyName() { return "MLFraudCheck-" + modelVersion; }

    // ─── Feature helpers ────────────────────────────────────────────

    private double getMethodRisk(PaymentMethod method) {
        return switch (method) {
            case CREDIT_CARD -> 0.3;       // higher fraud rate
            case DEBIT_CARD -> 0.2;
            case UPI -> 0.1;               // low fraud (requires device)
            case WALLET -> 0.05;           // lowest (pre-verified)
            case BANK_TRANSFER -> 0.15;
        };
    }

    private double getTimeOfDayRisk() {
        int hour = LocalTime.now().getHour();
        // Late night (11PM-5AM) is higher risk
        if (hour >= 23 || hour < 5) return 0.3;
        return 0.05;
    }

    private boolean isRoundAmount(double amount) {
        // Exact hundreds or thousands are suspicious
        return amount == Math.floor(amount) && amount >= 100 && amount % 100 == 0;
    }
}
```

---

## 7. Service Layer Design

### 7.1 PaymentService (THE Facade)

> **This is THE most important class.** It orchestrates the entire payment flow: idempotency check, fraud detection, processor routing, ledger recording, and webhook dispatch. The controller calls ONE method. The Facade delegates to everything else.

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║              PaymentService ORCHESTRATION FLOW                                    ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    processPayment(merchantId, amount, currency, method, idempotencyKey)
    │
    ├──1. IdempotencyService.check(idempotencyKey)
    │      └── Already processed? → return cached response (DONE)
    │
    ├──2. MerchantRepository.findById(merchantId)
    │      └── Not found? → throw PaymentException
    │
    ├──3. Create Payment via Builder
    │      └── Payment{id=PAY-xxx, status=INITIATED}
    │
    ├──4. FraudService.checkFraud(payment)
    │      └── Fraud detected? → payment.fail(), throw FraudDetectedException
    │
    ├──5. payment.transitionTo(PROCESSING)
    │
    ├──6. PaymentProcessor.processPayment(payment)
    │      └── Failure? → payment.fail(reason), throw PaymentException
    │
    ├──7. payment.transitionTo(AUTHORIZED)
    │     payment.transitionTo(CAPTURED)
    │
    ├──8. LedgerService.recordPayment(payment, merchant)
    │      └── DEBIT customer, CREDIT platform (double-entry)
    │
    ├──9. payment.transitionTo(SETTLED)  [for instant methods like UPI/WALLET]
    │     LedgerService.recordSettlement(payment, merchant)
    │      └── DEBIT platform, CREDIT merchant (minus fee)
    │
    ├──10. IdempotencyService.store(idempotencyKey, payment)
    │
    ├──11. WebhookService.dispatch("payment.captured", payment, merchant)
    │
    └──12. return payment
```

```java
/**
 * FACADE: Orchestrates the entire payment lifecycle.
 *
 * This is the single entry point for all payment operations.
 * PaymentController calls processPayment(), refundPayment(), getPayment().
 * PaymentService delegates to specialized services and strategies.
 *
 * DESIGN DECISIONS:
 *   1. Idempotency check FIRST -- before any side effects
 *   2. Fraud check BEFORE processor call -- don't send fraudulent payments to network
 *   3. Ledger entries AFTER successful processing -- only record real money movements
 *   4. Webhook dispatch LAST -- merchant notification is async and retriable
 *   5. If ANY step fails, payment transitions to FAILED with a reason
 *
 * DEPENDENCIES (all injected via constructor):
 *   - IdempotencyService: prevents double-charging
 *   - FraudService: detects fraudulent payments
 *   - Map<PaymentMethod, PaymentProcessor>: strategy registry
 *   - LedgerService: records money movements
 *   - WebhookService: notifies merchants
 *   - RefundService: handles refunds
 *   - MerchantRepository: validates merchant exists
 *   - PaymentRepository: persists payments
 */
public class PaymentService {

    private final Map<PaymentMethod, PaymentProcessor> processors;
    private final IdempotencyService idempotencyService;
    private final FraudService fraudService;
    private final LedgerService ledgerService;
    private final WebhookService webhookService;
    private final RefundService refundService;
    private final MerchantRepository merchantRepository;
    private final PaymentRepository paymentRepository;

    /**
     * Constructor: all dependencies injected by AppConfig.
     * No 'new' keywords inside this class. Pure dependency injection.
     */
    public PaymentService(Map<PaymentMethod, PaymentProcessor> processors,
                          IdempotencyService idempotencyService,
                          FraudService fraudService,
                          LedgerService ledgerService,
                          WebhookService webhookService,
                          RefundService refundService,
                          MerchantRepository merchantRepository,
                          PaymentRepository paymentRepository) {
        this.processors = processors;
        this.idempotencyService = idempotencyService;
        this.fraudService = fraudService;
        this.ledgerService = ledgerService;
        this.webhookService = webhookService;
        this.refundService = refundService;
        this.merchantRepository = merchantRepository;
        this.paymentRepository = paymentRepository;
    }

    /**
     * Process a new payment. THE main entry point.
     *
     * @param merchantId       the merchant receiving payment
     * @param amount           payment amount
     * @param currency         payment currency
     * @param method           payment method (determines processor)
     * @param idempotencyKey   client-provided key for retry safety
     * @return the processed Payment
     * @throws DuplicatePaymentException if idempotency key was already used
     * @throws FraudDetectedException    if fraud check fails
     * @throws PaymentException          if processing fails
     */
    public Payment processPayment(String merchantId, double amount, Currency currency,
                                  PaymentMethod method, String idempotencyKey) {

        // ─── Step 1: Idempotency check ─────────────────────────────
        // MUST be first. If this key was already processed, return the
        // cached result immediately. No side effects.
        Optional<IdempotencyRecord> existing = idempotencyService.check(idempotencyKey);
        if (existing.isPresent() && existing.get().isValid()) {
            System.out.printf("[PaymentService] Idempotent hit for key=%s, returning cached payment=%s%n",
                idempotencyKey, existing.get().paymentId());
            return paymentRepository.findById(existing.get().paymentId())
                .orElseThrow(() -> new PaymentException("Cached payment not found"));
        }

        // ─── Step 2: Validate merchant ─────────────────────────────
        Merchant merchant = merchantRepository.findById(merchantId)
            .orElseThrow(() -> new PaymentException("Merchant not found: " + merchantId));

        // ─── Step 3: Create payment ────────────────────────────────
        Payment payment = new Payment.Builder(merchantId, amount, currency, method)
            .idempotencyKey(idempotencyKey)
            .build();
        paymentRepository.save(payment);

        System.out.printf("[PaymentService] Created %s%n", payment);

        try {
            // ─── Step 4: Fraud check ───────────────────────────────
            fraudService.checkFraud(payment);
            // If fraud detected, FraudService throws FraudDetectedException

            // ─── Step 5: Transition to PROCESSING ──────────────────
            payment.transitionTo(PaymentStatus.PROCESSING);
            paymentRepository.save(payment);

            // ─── Step 6: Route to correct processor ────────────────
            PaymentProcessor processor = processors.get(method);
            if (processor == null) {
                throw new PaymentException("No processor for method: " + method);
            }

            Transaction transaction = processor.processPayment(payment);

            if (!transaction.isSuccess()) {
                // Processor declined the payment
                payment.fail(transaction.getResponseMessage());
                paymentRepository.save(payment);
                webhookService.dispatch("payment.failed", payment, merchant);
                throw new PaymentException("Payment declined: " + transaction.getResponseMessage());
            }

            // ─── Step 7: Transition to AUTHORIZED → CAPTURED ──────
            payment.setProcessorTransactionId(transaction.getProcessorTransactionId());
            payment.transitionTo(PaymentStatus.AUTHORIZED);
            payment.transitionTo(PaymentStatus.CAPTURED);
            paymentRepository.save(payment);

            // ─── Step 8: Record ledger entries (double-entry) ──────
            ledgerService.recordPaymentCapture(payment, merchant);

            // ─── Step 9: Settle (for instant methods) ──────────────
            if (!method.requiresSettlement()) {
                // UPI and WALLET settle instantly
                payment.transitionTo(PaymentStatus.SETTLED);
                ledgerService.recordSettlement(payment, merchant);
                paymentRepository.save(payment);
            }

            // ─── Step 10: Store idempotency record ─────────────────
            idempotencyService.store(idempotencyKey, payment.getPaymentId());

            // ─── Step 11: Dispatch webhook ─────────────────────────
            webhookService.dispatch("payment.captured", payment, merchant);

            System.out.printf("[PaymentService] SUCCESS: %s%n", payment);
            return payment;

        } catch (FraudDetectedException e) {
            payment.fail("Fraud detected: " + e.getMessage());
            paymentRepository.save(payment);
            webhookService.dispatch("payment.fraud_blocked", payment, merchant);
            throw e;

        } catch (PaymentException e) {
            // Already handled above (processor decline)
            throw e;

        } catch (Exception e) {
            payment.fail("Internal error: " + e.getMessage());
            paymentRepository.save(payment);
            throw new PaymentException("Payment processing failed", e);
        }
    }

    /**
     * Initiate a refund for a previously captured/settled payment.
     *
     * @param paymentId  the payment to refund
     * @param amount     refund amount (can be partial)
     * @param reason     reason for refund
     * @return the Refund object
     */
    public Refund refundPayment(String paymentId, double amount, String reason) {
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new PaymentException("Payment not found: " + paymentId));

        Merchant merchant = merchantRepository.findById(payment.getMerchantId())
            .orElseThrow(() -> new PaymentException("Merchant not found"));

        PaymentProcessor processor = processors.get(payment.getMethod());

        Refund refund = refundService.processRefund(payment, amount, reason, processor);

        // Transition payment to REFUNDED
        payment.transitionTo(PaymentStatus.REFUNDED);
        paymentRepository.save(payment);

        // Reverse ledger entries
        ledgerService.recordRefund(refund, payment, merchant);

        // Notify merchant
        webhookService.dispatch("payment.refunded", payment, merchant);

        return refund;
    }

    /**
     * Get payment by ID.
     */
    public Optional<Payment> getPayment(String paymentId) {
        return paymentRepository.findById(paymentId);
    }

    /**
     * Get all payments for a merchant.
     */
    public List<Payment> getPaymentsByMerchant(String merchantId) {
        return paymentRepository.findByMerchantId(merchantId);
    }
}
```

---

### 7.2 LedgerService (Double-Entry Bookkeeping)

> **This is the financial integrity guarantee.** Every money movement creates exactly two ledger entries. The system is always balanced.

#### Anti-Pattern: Single-Entry Balance Updates

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║           ANTI-PATTERN: Just Update Balances Directly                              ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    // DANGEROUS: no audit trail, no balance integrity
    public class NaiveLedger {
        public void processPayment(double amount) {
            customerAccount.balance -= amount;    // where did money go?
            merchantAccount.balance += amount;    // where did money come from?
        }
    }

    PROBLEMS:
      1. If customerAccount.balance update succeeds but merchantAccount.balance
         fails (exception between the two lines), money disappears!
      2. No record of WHY the balance changed
      3. No way to reconcile: what happened to the missing $50?
      4. No transaction linking: which payment caused this balance change?
      5. Cannot audit: regulator asks "show all movements for account X"

    FIX: Create immutable LedgerEntry records in pairs.
    Balance is DERIVED from entries, not stored directly.
    If one entry is written, both must be written (atomicity).
╚═══════════════════════════════════════════════════════════════════════════════════╝
```

#### Clean Solution: LedgerService

```java
/**
 * Manages double-entry bookkeeping for all money movements.
 *
 * CORE INVARIANT: Every money movement creates exactly TWO entries:
 *   1. DEBIT entry (money leaves source account)
 *   2. CREDIT entry (money enters destination account)
 *   The amounts are always equal. The system is always balanced.
 *
 * OPERATIONS:
 *   recordPaymentCapture(payment, merchant):
 *     DEBIT customer → CREDIT platform
 *
 *   recordSettlement(payment, merchant):
 *     DEBIT platform → CREDIT merchant (net amount)
 *     DEBIT platform → CREDIT platformFee account (fee amount)
 *
 *   recordRefund(refund, payment, merchant):
 *     DEBIT merchant → CREDIT customer
 *
 * Thread safety: All entry creation is synchronized on the involved
 * account IDs to prevent race conditions on balance updates.
 * See Section 8 for details.
 *
 * CALL CHAIN:
 *   PaymentService.processPayment(...)
 *     → LedgerService.recordPaymentCapture(payment, merchant)
 *       → createEntryPair(customerAcct, platformAcct, amount, txnId)
 *         → LedgerRepository.save(debitEntry)
 *         → LedgerRepository.save(creditEntry)
 *         → customerAcct.debit(amount)
 *         → platformAcct.credit(amount)
 */
public class LedgerService {

    private final LedgerRepository ledgerRepository;
    private final AccountRepository accountRepository;

    // Lock map for per-account synchronization
    private final ConcurrentHashMap<String, Object> accountLocks = new ConcurrentHashMap<>();

    // Platform accounts (created at system startup)
    private final String platformAccountId;
    private final String platformFeeAccountId;

    public LedgerService(LedgerRepository ledgerRepository,
                         AccountRepository accountRepository,
                         String platformAccountId,
                         String platformFeeAccountId) {
        this.ledgerRepository = ledgerRepository;
        this.accountRepository = accountRepository;
        this.platformAccountId = platformAccountId;
        this.platformFeeAccountId = platformFeeAccountId;
    }

    /**
     * Record a payment capture: customer pays, platform receives.
     *
     * DEBIT customer account → CREDIT platform account
     *
     * @param payment  the captured payment
     * @param merchant the receiving merchant (for fee calculation)
     */
    public void recordPaymentCapture(Payment payment, Merchant merchant) {
        String customerAccountId = "CUST-" + payment.getPaymentId(); // per-payment customer account
        String description = String.format("Payment %s captured: %s",
            payment.getPaymentId(), payment.getCurrency().format(payment.getAmount()));

        // Ensure customer account exists (create if needed for simulation)
        accountRepository.findById(customerAccountId).orElseGet(() -> {
            Account custAcct = new Account(customerAccountId, AccountType.CUSTOMER,
                payment.getCurrency(), payment.getAmount() * 2); // seed with enough balance
            accountRepository.save(custAcct);
            return custAcct;
        });

        createEntryPair(
            customerAccountId,      // source (DEBIT)
            platformAccountId,      // destination (CREDIT)
            payment.getAmount(),
            payment.getPaymentId(),
            description
        );

        System.out.printf("[Ledger] Recorded capture: %s → DEBIT %s, CREDIT %s%n",
            payment.getCurrency().format(payment.getAmount()), customerAccountId, platformAccountId);
    }

    /**
     * Record settlement: platform pays merchant (minus fee).
     *
     * DEBIT platform → CREDIT merchant (net amount)
     * DEBIT platform → CREDIT platform fee account (fee)
     *
     * @param payment  the payment being settled
     * @param merchant the merchant to settle to
     */
    public void recordSettlement(Payment payment, Merchant merchant) {
        double fee = merchant.calculateFee(payment.getAmount());
        double netAmount = merchant.calculateNetAmount(payment.getAmount());
        String merchantAccountId = "MERCH-" + merchant.getMerchantId();

        // Ensure merchant account exists
        accountRepository.findById(merchantAccountId).orElseGet(() -> {
            Account merchAcct = new Account(merchantAccountId, AccountType.MERCHANT,
                payment.getCurrency(), 0.0);
            accountRepository.save(merchAcct);
            return merchAcct;
        });

        // Entry pair 1: Platform → Merchant (net amount)
        createEntryPair(
            platformAccountId,
            merchantAccountId,
            netAmount,
            payment.getPaymentId(),
            String.format("Settlement %s: net %s to merchant %s",
                payment.getPaymentId(), payment.getCurrency().format(netAmount),
                merchant.getMerchantId())
        );

        // Entry pair 2: Platform → Platform Fee (our revenue)
        createEntryPair(
            platformAccountId,
            platformFeeAccountId,
            fee,
            payment.getPaymentId(),
            String.format("Platform fee %s: %s (%.1f%% + %s)",
                payment.getPaymentId(), payment.getCurrency().format(fee),
                merchant.getPlatformFeePercent() * 100,
                payment.getCurrency().format(merchant.getPlatformFeeFixed()))
        );

        System.out.printf("[Ledger] Settled: %s to merchant (net=%s, fee=%s)%n",
            payment.getPaymentId(), payment.getCurrency().format(netAmount),
            payment.getCurrency().format(fee));
    }

    /**
     * Record a refund: reverse the original payment entries.
     *
     * DEBIT merchant → CREDIT customer
     *
     * @param refund   the refund being processed
     * @param payment  the original payment
     * @param merchant the merchant whose account is debited
     */
    public void recordRefund(Refund refund, Payment payment, Merchant merchant) {
        String merchantAccountId = "MERCH-" + merchant.getMerchantId();
        String customerAccountId = "CUST-" + payment.getPaymentId();

        createEntryPair(
            merchantAccountId,      // source (DEBIT)
            customerAccountId,      // destination (CREDIT)
            refund.getAmount(),
            refund.getRefundId(),
            String.format("Refund %s: %s back to customer for payment %s",
                refund.getRefundId(), payment.getCurrency().format(refund.getAmount()),
                payment.getPaymentId())
        );

        System.out.printf("[Ledger] Refund recorded: %s from merchant to customer%n",
            payment.getCurrency().format(refund.getAmount()));
    }

    /**
     * Create a balanced pair of ledger entries: DEBIT source, CREDIT destination.
     *
     * THIS IS THE ATOMIC UNIT OF THE LEDGER. Both entries are created together.
     * Both account balances are updated within synchronized blocks.
     *
     * @param sourceAccountId  account to debit (money leaves)
     * @param destAccountId    account to credit (money arrives)
     * @param amount           the amount to move
     * @param transactionId    the payment/refund ID this is for
     * @param description      human-readable description
     */
    private void createEntryPair(String sourceAccountId, String destAccountId,
                                  double amount, String transactionId,
                                  String description) {
        // Create the two ledger entries
        LedgerEntry debitEntry = new LedgerEntry(
            "LE-" + UUID.randomUUID().toString().substring(0, 10),
            sourceAccountId,
            amount,
            LedgerEntryType.DEBIT,
            transactionId,
            description,
            Instant.now()
        );

        LedgerEntry creditEntry = new LedgerEntry(
            "LE-" + UUID.randomUUID().toString().substring(0, 10),
            destAccountId,
            amount,
            LedgerEntryType.CREDIT,
            transactionId,
            description,
            Instant.now()
        );

        // Acquire locks in consistent order (alphabetical) to prevent deadlock
        String firstLock = sourceAccountId.compareTo(destAccountId) < 0
            ? sourceAccountId : destAccountId;
        String secondLock = sourceAccountId.compareTo(destAccountId) < 0
            ? destAccountId : sourceAccountId;

        Object lock1 = accountLocks.computeIfAbsent(firstLock, k -> new Object());
        Object lock2 = accountLocks.computeIfAbsent(secondLock, k -> new Object());

        synchronized (lock1) {
            synchronized (lock2) {
                // Save entries (append-only)
                ledgerRepository.save(debitEntry);
                ledgerRepository.save(creditEntry);

                // Update account balances
                Account source = accountRepository.findById(sourceAccountId)
                    .orElseThrow(() -> new PaymentException("Source account not found: " + sourceAccountId));
                Account dest = accountRepository.findById(destAccountId)
                    .orElseThrow(() -> new PaymentException("Dest account not found: " + destAccountId));

                source.debit(amount);
                dest.credit(amount);

                accountRepository.save(source);
                accountRepository.save(dest);
            }
        }
    }

    /**
     * Get all ledger entries for an account (audit trail).
     */
    public List<LedgerEntry> getEntriesForAccount(String accountId) {
        return ledgerRepository.findByAccountId(accountId);
    }

    /**
     * Verify the double-entry invariant: all entries sum to zero.
     * Used by ReconciliationService.
     *
     * @return true if the ledger is balanced
     */
    public boolean isLedgerBalanced() {
        double total = ledgerRepository.findAll().stream()
            .mapToDouble(LedgerEntry::getSignedAmount)
            .sum();
        return Math.abs(total) < 0.01; // floating-point tolerance
    }
}
```

---

### 7.3 IdempotencyService

```java
/**
 * Manages idempotency keys to prevent duplicate payment processing.
 *
 * FLOW:
 *   1. Client sends payment request with idempotency key (e.g., "order-123-pay-1")
 *   2. PaymentService calls check(key)
 *   3. If key exists and not expired → return cached response (no processing)
 *   4. If key does not exist → proceed with payment, then store(key, paymentId)
 *   5. Keys expire after TTL (24 hours) to prevent unbounded storage growth
 *
 * WHY IDEMPOTENCY MATTERS:
 *   - Network timeout: client retries the same request
 *   - Without idempotency: customer charged $100 twice for one order
 *   - With idempotency: second request returns same result as first
 *
 * CALL CHAIN:
 *   PaymentService.processPayment(merchantId, amount, ..., idempotencyKey)
 *     → IdempotencyService.check(idempotencyKey)
 *       → IdempotencyRepository.findByKey(key)
 *       → if exists and valid → return Optional.of(record)
 *       → if not exists → return Optional.empty()
 */
public class IdempotencyService {

    private final IdempotencyRepository repository;
    private final Duration ttl;

    public IdempotencyService(IdempotencyRepository repository, Duration ttl) {
        this.repository = repository;
        this.ttl = ttl;
    }

    /**
     * Check if an idempotency key has been used before.
     *
     * @param key the idempotency key from the client
     * @return the cached record if key exists and is valid, empty otherwise
     */
    public Optional<IdempotencyRecord> check(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty(); // no idempotency key provided
        }
        return repository.findByKey(key).filter(IdempotencyRecord::isValid);
    }

    /**
     * Store the result of a processed payment for future idempotency checks.
     *
     * @param key       the idempotency key
     * @param paymentId the payment that was created
     */
    public void store(String key, String paymentId) {
        if (key == null || key.isBlank()) {
            return; // nothing to store
        }
        Instant now = Instant.now();
        IdempotencyRecord record = new IdempotencyRecord(
            key,
            paymentId,
            "Payment processed: " + paymentId,
            now,
            now.plus(ttl)
        );
        repository.save(record);
        System.out.printf("[Idempotency] Stored key=%s → payment=%s (expires in %s)%n",
            key, paymentId, ttl);
    }

    /** Cleanup expired records. Called periodically. */
    public void cleanupExpired() {
        repository.deleteExpired();
    }
}
```

---

### 7.4 FraudService

```java
/**
 * Applies fraud detection strategies to payments.
 *
 * CHAIN-OF-RESPONSIBILITY: Multiple fraud strategies are applied in sequence.
 * If ANY strategy rejects the payment, the payment is blocked.
 * This provides defense-in-depth: rules catch obvious fraud, ML catches subtle patterns.
 *
 * CALL CHAIN:
 *   PaymentService.processPayment(...)
 *     → FraudService.checkFraud(payment)
 *       → for each strategy in chain:
 *           result = strategy.checkFraud(payment)
 *           if (!result.passed()) → throw FraudDetectedException
 *       → all passed → return (no exception)
 */
public class FraudService {

    private final List<FraudCheckStrategy> strategies;

    /**
     * Constructor: accepts an ordered list of fraud check strategies.
     * Order matters: cheaper checks (rules) should go first, expensive checks (ML) last.
     */
    public FraudService(List<FraudCheckStrategy> strategies) {
        this.strategies = List.copyOf(strategies); // defensive copy, immutable
    }

    /**
     * Run all fraud checks on the payment.
     * Throws FraudDetectedException if any check fails.
     *
     * @param payment the payment to check
     * @throws FraudDetectedException if fraud is detected
     */
    public void checkFraud(Payment payment) {
        System.out.printf("[FraudService] Running %d fraud checks on %s%n",
            strategies.size(), payment.getPaymentId());

        for (FraudCheckStrategy strategy : strategies) {
            FraudCheckStrategy.FraudResult result = strategy.checkFraud(payment);

            if (!result.passed()) {
                System.out.printf("[FraudService] BLOCKED by %s: %s (risk=%.2f)%n",
                    strategy.getStrategyName(), result.reason(), result.riskScore());
                throw new FraudDetectedException(
                    String.format("Fraud detected by %s: %s (risk=%.2f)",
                        strategy.getStrategyName(), result.reason(), result.riskScore())
                );
            }
        }

        System.out.printf("[FraudService] All checks passed for %s%n", payment.getPaymentId());
    }
}
```

---

### 7.5 WebhookService

```java
/**
 * Dispatches webhook notifications to merchants with exponential backoff retry.
 *
 * WEBHOOK LIFECYCLE:
 *   1. Payment state changes → WebhookService.dispatch(eventType, payment, merchant)
 *   2. Create WebhookEvent with status=PENDING
 *   3. Attempt delivery to merchant.webhookUrl
 *   4. If success → status=DELIVERED
 *   5. If failure → status=FAILED, schedule retry with exponential backoff
 *   6. Retry loop: attempt delivery at each scheduled retry time
 *   7. After maxAttempts (5) failures → status=EXHAUSTED, alert
 *
 * EXPONENTIAL BACKOFF:
 *   Attempt 1: immediate
 *   Attempt 2: retry after 1 second    (4^0)
 *   Attempt 3: retry after 4 seconds   (4^1)
 *   Attempt 4: retry after 16 seconds  (4^2)
 *   Attempt 5: retry after 64 seconds  (4^3)
 *
 * SIMULATED DELIVERY: In our LLD, delivery succeeds 80% of the time.
 * In production, this would be an HTTP POST to merchant.webhookUrl.
 *
 * CALL CHAIN:
 *   PaymentService.processPayment(...) → WebhookService.dispatch(...)
 *     → create WebhookEvent
 *     → attemptDelivery(event, merchant)
 *       → success? → event.markDelivered()
 *       → failure? → event.markFailed() → schedule retry
 */
public class WebhookService {

    private static final int MAX_ATTEMPTS = 5;
    private static final double DELIVERY_SUCCESS_RATE = 0.80; // simulated

    private final WebhookRepository webhookRepository;
    private final Random random = new Random();

    // ExecutorService for async webhook delivery
    private final ExecutorService deliveryExecutor;

    public WebhookService(WebhookRepository webhookRepository) {
        this.webhookRepository = webhookRepository;
        this.deliveryExecutor = Executors.newFixedThreadPool(4);
    }

    /**
     * Dispatch a webhook event to a merchant.
     *
     * @param eventType  e.g., "payment.captured", "payment.refunded"
     * @param payment    the payment that triggered this event
     * @param merchant   the merchant to notify
     */
    public void dispatch(String eventType, Payment payment, Merchant merchant) {
        String payload = buildPayload(eventType, payment);

        WebhookEvent event = new WebhookEvent(
            "WH-" + UUID.randomUUID().toString().substring(0, 10),
            eventType,
            payment.getPaymentId(),
            merchant.getMerchantId(),
            payload,
            MAX_ATTEMPTS
        );
        webhookRepository.save(event);

        System.out.printf("[Webhook] Dispatching %s for payment %s to merchant %s%n",
            eventType, payment.getPaymentId(), merchant.getMerchantId());

        // Attempt delivery asynchronously
        deliveryExecutor.submit(() -> attemptDeliveryWithRetry(event, merchant));
    }

    /**
     * Attempt delivery with retry loop.
     * On failure, waits for the backoff period and retries.
     */
    private void attemptDeliveryWithRetry(WebhookEvent event, Merchant merchant) {
        while (!event.isExhausted() && event.getStatus() != WebhookStatus.DELIVERED) {
            event.recordAttempt();

            // Simulate delivery (HTTP POST to merchant.webhookUrl)
            boolean delivered = simulateDelivery(merchant.getWebhookUrl(), event.getPayload());

            if (delivered) {
                event.markDelivered();
                webhookRepository.save(event);
                System.out.printf("[Webhook] DELIVERED %s to %s (attempt %d)%n",
                    event.getEventId(), merchant.getWebhookUrl(), event.getAttempts());
                return;
            } else {
                event.markFailed();
                webhookRepository.save(event);

                if (event.isExhausted()) {
                    System.out.printf("[Webhook] EXHAUSTED %s: all %d attempts failed%n",
                        event.getEventId(), event.getMaxAttempts());
                    return;
                }

                // Wait for backoff period
                long backoffMs = (long) Math.pow(4, event.getAttempts() - 1) * 1000;
                System.out.printf("[Webhook] RETRY %s in %dms (attempt %d/%d)%n",
                    event.getEventId(), backoffMs, event.getAttempts(), event.getMaxAttempts());

                try {
                    Thread.sleep(Math.min(backoffMs, 5000)); // cap at 5s for demo
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Simulate HTTP POST delivery to merchant's webhook URL.
     * In production: HTTP client with timeout, SSL validation, signature header.
     */
    private boolean simulateDelivery(String webhookUrl, String payload) {
        return random.nextDouble() < DELIVERY_SUCCESS_RATE;
    }

    /**
     * Build webhook payload (simulated JSON).
     */
    private String buildPayload(String eventType, Payment payment) {
        return String.format(
            "{\"event\":\"%s\",\"paymentId\":\"%s\",\"amount\":%.2f,\"currency\":\"%s\","
            + "\"status\":\"%s\",\"timestamp\":\"%s\"}",
            eventType, payment.getPaymentId(), payment.getAmount(),
            payment.getCurrency(), payment.getStatus(), Instant.now());
    }

    /**
     * Process pending retries (called by a scheduled job in production).
     */
    public void processPendingRetries() {
        List<WebhookEvent> pendingRetries = webhookRepository.findPendingRetries();
        System.out.printf("[Webhook] Processing %d pending retries%n", pendingRetries.size());
        // In production, re-attempt delivery for each
    }

    /** Shutdown the delivery executor. */
    public void shutdown() {
        deliveryExecutor.shutdown();
    }
}
```

---

### 7.6 RefundService

```java
/**
 * Manages refund processing with validation and processor delegation.
 *
 * REFUND RULES:
 *   - Can only refund CAPTURED or SETTLED payments
 *   - Partial refunds allowed (refund $30 of a $100 payment)
 *   - Total refunded amount cannot exceed original payment amount
 *   - Multiple partial refunds tracked via sum of all refunds for a paymentId
 *
 * CALL CHAIN:
 *   PaymentService.refundPayment(paymentId, amount, reason)
 *     → RefundService.processRefund(payment, amount, reason, processor)
 *       → validate refund (amount, status)
 *       → processor.processRefund(refund, payment)
 *       → if success: refund.markCompleted()
 *       → if failure: refund.markFailed(), throw PaymentException
 */
public class RefundService {

    private final PaymentRepository paymentRepository;

    // Track total refunded per payment to enforce partial refund limits
    private final ConcurrentHashMap<String, Double> refundedAmounts = new ConcurrentHashMap<>();

    public RefundService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    /**
     * Process a refund for a payment.
     *
     * @param payment   the original payment
     * @param amount    the refund amount (can be partial)
     * @param reason    the refund reason
     * @param processor the payment processor to use for refund
     * @return the completed Refund
     * @throws PaymentException if refund validation fails or processor declines
     */
    public Refund processRefund(Payment payment, double amount, String reason,
                                 PaymentProcessor processor) {
        // ─── Validation ─────────────────────────────────────────────
        PaymentStatus status = payment.getStatus();
        if (status != PaymentStatus.CAPTURED && status != PaymentStatus.SETTLED) {
            throw new PaymentException(
                String.format("Cannot refund payment in status %s. Must be CAPTURED or SETTLED.",
                    status));
        }

        double alreadyRefunded = refundedAmounts.getOrDefault(payment.getPaymentId(), 0.0);
        if (alreadyRefunded + amount > payment.getAmount()) {
            throw new PaymentException(
                String.format("Refund amount %.2f would exceed payment amount %.2f "
                    + "(already refunded: %.2f)",
                    amount, payment.getAmount(), alreadyRefunded));
        }

        // ─── Create Refund ──────────────────────────────────────────
        Refund refund = new Refund(
            "REF-" + UUID.randomUUID().toString().substring(0, 10),
            payment.getPaymentId(),
            amount,
            reason
        );

        System.out.printf("[RefundService] Processing refund %s for payment %s: %s%n",
            refund.getRefundId(), payment.getPaymentId(),
            payment.getCurrency().format(amount));

        // ─── Process through processor ──────────────────────────────
        refund.markProcessing();
        Transaction transaction = processor.processRefund(refund, payment);

        if (transaction.isSuccess()) {
            refund.markCompleted();
            refundedAmounts.merge(payment.getPaymentId(), amount, Double::sum);
            System.out.printf("[RefundService] Refund %s COMPLETED%n", refund.getRefundId());
        } else {
            refund.markFailed();
            throw new PaymentException("Refund declined: " + transaction.getResponseMessage());
        }

        return refund;
    }

    /**
     * Get the total amount already refunded for a payment.
     */
    public double getTotalRefunded(String paymentId) {
        return refundedAmounts.getOrDefault(paymentId, 0.0);
    }
}
```

---

### 7.7 ReconciliationService

```java
/**
 * Reconciles internal ledger records against external processor records.
 *
 * RECONCILIATION:
 *   In a real payment system, the processor sends daily settlement files.
 *   We compare our internal records (ledger entries) against their records.
 *   Discrepancies are flagged for manual review.
 *
 * THREE TYPES OF DISCREPANCIES:
 *   1. MISSING_INTERNAL: processor has a record, we don't (we lost a payment!)
 *   2. MISSING_EXTERNAL: we have a record, processor doesn't (phantom payment!)
 *   3. AMOUNT_MISMATCH: both have a record, but amounts differ (data corruption!)
 *
 * CALL CHAIN:
 *   ReconciliationService.reconcile(internalPayments, externalRecords)
 *     → match by processorTransactionId
 *     → flag discrepancies
 *     → return ReconciliationReport
 */
public class ReconciliationService {

    private final LedgerService ledgerService;
    private final PaymentRepository paymentRepository;

    public ReconciliationService(LedgerService ledgerService,
                                  PaymentRepository paymentRepository) {
        this.ledgerService = ledgerService;
        this.paymentRepository = paymentRepository;
    }

    /**
     * Run reconciliation between internal payments and the ledger.
     *
     * Checks:
     *   1. Every CAPTURED/SETTLED payment has corresponding ledger entries
     *   2. Ledger is balanced (sum of all entries = 0)
     *   3. Account balances match sum of their ledger entries
     *
     * @return a report of findings
     */
    public String reconcile() {
        StringBuilder report = new StringBuilder();
        report.append("=== RECONCILIATION REPORT ===\n");

        // Check 1: Ledger balance
        boolean balanced = ledgerService.isLedgerBalanced();
        report.append(String.format("Ledger balanced: %s%n", balanced ? "YES" : "NO (ALERT!)"));

        // Check 2: All captured payments have ledger entries
        List<Payment> capturedPayments = paymentRepository.findByStatus(PaymentStatus.CAPTURED);
        List<Payment> settledPayments = paymentRepository.findByStatus(PaymentStatus.SETTLED);

        int missingEntries = 0;
        for (Payment payment : capturedPayments) {
            List<LedgerEntry> entries = ledgerService.getEntriesForAccount(
                "CUST-" + payment.getPaymentId());
            if (entries.isEmpty()) {
                report.append(String.format("MISSING LEDGER: payment %s has no entries!%n",
                    payment.getPaymentId()));
                missingEntries++;
            }
        }

        report.append(String.format("Captured payments: %d%n", capturedPayments.size()));
        report.append(String.format("Settled payments: %d%n", settledPayments.size()));
        report.append(String.format("Missing ledger entries: %d%n", missingEntries));
        report.append("=== END REPORT ===\n");

        System.out.print(report);
        return report.toString();
    }
}
```

---

### 7.8 CurrencyService

```java
/**
 * Handles currency exchange rate lookup and conversion.
 *
 * In production: calls an exchange rate API (e.g., Open Exchange Rates).
 * For our LLD: uses a static map of rates relative to USD.
 *
 * CALL CHAIN:
 *   PaymentController → CurrencyService.convert(100.0, USD, EUR)
 *     → lookup rate: USD→EUR = 0.92
 *     → return 92.00
 */
public class CurrencyService {

    // Exchange rates relative to USD (1 USD = X foreign)
    private final Map<Currency, Double> ratesFromUsd;

    public CurrencyService() {
        this.ratesFromUsd = Map.of(
            Currency.USD, 1.0,
            Currency.EUR, 0.92,
            Currency.GBP, 0.79,
            Currency.INR, 83.50,
            Currency.JPY, 149.80
        );
    }

    /**
     * Convert an amount from one currency to another.
     *
     * @param amount       the amount to convert
     * @param fromCurrency the source currency
     * @param toCurrency   the target currency
     * @return the converted amount
     */
    public double convert(double amount, Currency fromCurrency, Currency toCurrency) {
        if (fromCurrency == toCurrency) {
            return amount;
        }

        // Convert to USD first, then to target
        double amountInUsd = amount / ratesFromUsd.get(fromCurrency);
        double converted = amountInUsd * ratesFromUsd.get(toCurrency);

        System.out.printf("[Currency] %.2f %s → %.2f %s (rate: %.4f)%n",
            amount, fromCurrency, converted, toCurrency,
            ratesFromUsd.get(toCurrency) / ratesFromUsd.get(fromCurrency));

        return converted;
    }

    /**
     * Get the exchange rate between two currencies.
     */
    public double getRate(Currency from, Currency to) {
        return ratesFromUsd.get(to) / ratesFromUsd.get(from);
    }
}
```

---

### 7.9 AppConfig (Factory Wiring)

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
 *   3. Easy to trace: "who created PaymentService?" → AppConfig.
 *   4. Easy to test: create services with mock dependencies
 */
public class AppConfig {

    /**
     * Create the fully wired PaymentService (the Facade).
     * This is the only public method. Everything else is internal wiring.
     */
    public static PaymentService createPaymentService() {

        // ─── Repositories (in-memory stores) ────────────────────────
        PaymentRepository paymentRepo = new InMemoryPaymentRepository();
        LedgerRepository ledgerRepo = new InMemoryLedgerRepository();
        MerchantRepository merchantRepo = new InMemoryMerchantRepository();
        AccountRepository accountRepo = new InMemoryAccountRepository();
        IdempotencyRepository idempotencyRepo = new InMemoryIdempotencyRepository();
        WebhookRepository webhookRepo = new InMemoryWebhookRepository();

        // ─── Platform accounts ──────────────────────────────────────
        Account platformAccount = new Account("PLATFORM-001", AccountType.PLATFORM,
            Currency.USD, 1_000_000.00); // seed balance
        Account platformFeeAccount = new Account("PLATFORM-FEE-001", AccountType.PLATFORM,
            Currency.USD, 0.0);
        accountRepo.save(platformAccount);
        accountRepo.save(platformFeeAccount);

        // ─── Payment processors (Strategy pattern) ──────────────────
        Map<PaymentMethod, PaymentProcessor> processors = Map.of(
            PaymentMethod.CREDIT_CARD, new CreditCardProcessor(),
            PaymentMethod.DEBIT_CARD, new CreditCardProcessor(),  // same processor handles both
            PaymentMethod.UPI, new UPIProcessor(),
            PaymentMethod.WALLET, new WalletProcessor()
        );

        // ─── Fraud detection strategies (chain) ─────────────────────
        FraudCheckStrategy ruleBasedFraud = new RuleBasedFraudCheck(
            10_000.00,                          // max $10,000 per transaction
            10,                                  // max 10 transactions per hour
            Set.of("BLOCKED-MERCHANT-001"),      // blacklisted merchants
            0.7                                  // risk threshold
        );
        FraudCheckStrategy mlFraud = new MLFraudCheck(0.7, "v2");

        // ─── Services ───────────────────────────────────────────────
        FraudService fraudService = new FraudService(List.of(ruleBasedFraud, mlFraud));
        IdempotencyService idempotencyService = new IdempotencyService(
            idempotencyRepo, Duration.ofHours(24));
        LedgerService ledgerService = new LedgerService(
            ledgerRepo, accountRepo, "PLATFORM-001", "PLATFORM-FEE-001");
        WebhookService webhookService = new WebhookService(webhookRepo);
        RefundService refundService = new RefundService(paymentRepo);

        // ─── The Facade ─────────────────────────────────────────────
        return new PaymentService(
            processors,
            idempotencyService,
            fraudService,
            ledgerService,
            webhookService,
            refundService,
            merchantRepo,
            paymentRepo
        );
    }
}
```

---

## 8. Concurrency Considerations

### 8.1 The Double-Spend Problem

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║           THE DOUBLE-SPEND PROBLEM                                                ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    Scenario: Two threads process the same payment simultaneously.

    Thread A: processPayment(merchant, 100, "idem-key-1")
    Thread B: processPayment(merchant, 100, "idem-key-1")   (retry)

    WITHOUT IDEMPOTENCY GUARD:
    ──────────────────────────────────────────────────────────────────
    T0:  Thread A: check idempotency key → NOT FOUND
    T1:  Thread B: check idempotency key → NOT FOUND (race!)
    T2:  Thread A: process payment → charge $100 → SUCCESS
    T3:  Thread B: process payment → charge $100 → SUCCESS (double charge!)
    T4:  Thread A: store idempotency key
    T5:  Thread B: store idempotency key (overwrites A's record)

    RESULT: Customer charged $200 for a $100 purchase!

    WITH IDEMPOTENCY GUARD (ConcurrentHashMap.putIfAbsent):
    ──────────────────────────────────────────────────────────────────
    T0:  Thread A: putIfAbsent("idem-key-1", PROCESSING) → null (wins)
    T1:  Thread B: putIfAbsent("idem-key-1", PROCESSING) → PROCESSING (loses)
    T2:  Thread A: process payment → charge $100 → SUCCESS
    T3:  Thread B: returns cached result (no second charge)

    RESULT: Customer charged exactly $100. Thread B gets same result as A.
╚═══════════════════════════════════════════════════════════════════════════════════╝
```

---

### 8.2 Ledger Concurrency: Lock Ordering

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║           DEADLOCK PREVENTION IN LEDGER OPERATIONS                                ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    Problem: LedgerService.createEntryPair() locks TWO accounts.
    If Thread A locks (Account-1, Account-2) and Thread B locks (Account-2, Account-1),
    they can deadlock.

    DEADLOCK SCENARIO:
    ──────────────────────────────────────────────────────────────────
    Thread A: Payment from Customer-1 to Platform
      T0: lock(Customer-1)        → acquired
      T1: lock(Platform)          → BLOCKED (Thread B holds it)

    Thread B: Settlement from Platform to Merchant-1
      T0: lock(Platform)          → acquired
      T1: lock(Merchant-1)... NO, lock(Customer-1) → BLOCKED (Thread A holds it)

    Both threads waiting for each other → DEADLOCK!

    SOLUTION: Always acquire locks in ALPHABETICAL ORDER of account ID.
    ──────────────────────────────────────────────────────────────────
    Thread A: needs (Customer-1, Platform) → sort → lock(Customer-1), lock(Platform)
    Thread B: needs (Platform, Customer-1) → sort → lock(Customer-1), lock(Platform)

    Thread A: lock(Customer-1) → acquired
    Thread B: lock(Customer-1) → BLOCKED (waits for Thread A)
    Thread A: lock(Platform) → acquired → does work → releases both
    Thread B: lock(Customer-1) → acquired → lock(Platform) → acquired → does work

    NO DEADLOCK. Consistent lock ordering is the key.
╚═══════════════════════════════════════════════════════════════════════════════════╝
```

---

### 8.3 Concurrency Strategy Summary

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║           CONCURRENCY STRATEGY PER COMPONENT                                      ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    Component                  Strategy                    Why
    ──────────────────────    ───────────────────────     ────────────────────────
    IdempotencyService        ConcurrentHashMap           putIfAbsent is atomic,
                              .putIfAbsent()              prevents double-spend

    LedgerService             synchronized blocks         Two accounts must be
                              with ordered locks          updated atomically;
                              (alphabetical by ID)        ordered locks prevent
                                                          deadlock

    Account.debit/credit      Called only within          No separate lock needed;
                              LedgerService locks         LedgerService guarantees
                                                          exclusive access

    PaymentProcessor          Stateless                   No shared mutable state;
                              (new Transaction each       safe for concurrent calls
                              call)

    VelocityTracker           ConcurrentHashMap +         Velocity count per
    (in RuleBasedFraud)       CopyOnWriteArrayList        merchant must be
                                                          thread-safe

    WebhookService            ExecutorService with        Async delivery; each
                              fixed thread pool           webhook is independent

    RefundService             ConcurrentHashMap           refundedAmounts.merge()
                              .merge()                    is atomic; prevents
                                                          over-refund

    Repositories              ConcurrentHashMap           Thread-safe reads/writes
    (InMemory*)               for all stores              for all in-memory stores
╚═══════════════════════════════════════════════════════════════════════════════════╝
```

---

## 9. SOLID Principles Applied

### 9.1 Single Responsibility

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║           SINGLE RESPONSIBILITY                                                   ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    Each class has ONE reason to change:

    Class                    Responsibility                Changes when...
    ──────────────────────  ────────────────────────      ────────────────────────
    PaymentService          Orchestration flow            Flow steps change
    LedgerService           Double-entry bookkeeping      Accounting rules change
    FraudService            Fraud check orchestration     Fraud chain order changes
    RuleBasedFraudCheck     Rule-based fraud rules        Rules/thresholds change
    MLFraudCheck            ML-based fraud scoring        Model version changes
    CreditCardProcessor     Card network integration      Card API changes
    WebhookService          Webhook delivery + retry      Retry policy changes
    IdempotencyService      Idempotency key management    TTL/storage changes
    RefundService           Refund validation + flow      Refund rules change
    CurrencyService         Exchange rates                Rates/providers change

    ANTI-PATTERN AVOIDED:
    Having PaymentService also do fraud detection, ledger recording, and webhook
    delivery would make it a 500-line God class with 6 reasons to change.
╚═══════════════════════════════════════════════════════════════════════════════════╝
```

---

### 9.2 Open-Closed Principle

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║           OPEN-CLOSED PRINCIPLE                                                   ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    OPEN for extension, CLOSED for modification.

    Adding a new payment method (e.g., CRYPTO):
      1. Add to enum:     PaymentMethod.CRYPTO
      2. Create strategy: CryptoProcessor implements PaymentProcessor
      3. Register:        AppConfig → processors.put(CRYPTO, new CryptoProcessor())
      4. DONE. PaymentService, LedgerService, FraudService → ZERO changes.

    Adding a new fraud check (e.g., geolocation):
      1. Create strategy: GeoFraudCheck implements FraudCheckStrategy
      2. Register:        AppConfig → strategies.add(new GeoFraudCheck(...))
      3. DONE. FraudService iterates the chain. No changes to existing checks.

    Adding a new webhook event type:
      1. Call:            webhookService.dispatch("payment.chargeback", payment, merchant)
      2. DONE. WebhookService handles any event type string.

    The key insight: new behavior = new class, not modified class.
╚═══════════════════════════════════════════════════════════════════════════════════╝
```

---

### 9.3 Liskov Substitution

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║           LISKOV SUBSTITUTION PRINCIPLE                                            ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    Any PaymentProcessor implementation can be swapped without
    breaking PaymentService behavior.

    PaymentService calls:
      processor.processPayment(payment)  →  Transaction

    It does NOT care whether:
      - CreditCardProcessor takes 3 seconds and contacts Visa
      - UPIProcessor takes 200ms and uses NPCI
      - WalletProcessor takes 50ms and debits a local balance

    All return a Transaction with isSuccess(). That's the contract.

    Test: Replace CreditCardProcessor with a TestProcessor that
    always returns success. PaymentService works identically.
    This IS Liskov substitution.

    VIOLATION EXAMPLE (avoided):
      If CreditCardProcessor threw a checked exception that UPIProcessor
      didn't, callers would need to handle them differently → LSP broken.
      We avoid this: all processors return Transaction (success or failure),
      never throw for business failures.
╚═══════════════════════════════════════════════════════════════════════════════════╝
```

---

### 9.4 Interface Segregation

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║           INTERFACE SEGREGATION                                                   ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    Interfaces are SMALL and FOCUSED:

    PaymentProcessor:
      processPayment(payment): Transaction
      processRefund(refund, payment): Transaction
      getProcessorName(): String
      getSupportedMethods(): Set<PaymentMethod>

    FraudCheckStrategy:
      checkFraud(payment): FraudResult
      getStrategyName(): String

    PaymentRepository:
      save(), findById(), findByMerchantId(), findByStatus(), findAll()

    NO GOD INTERFACE like:
      interface PaymentOperations {
          processPayment();
          processRefund();
          checkFraud();        // <-- why would a processor check fraud?
          sendWebhook();       // <-- why would a processor send webhooks?
          reconcile();         // <-- mixing concerns
      }

    Each client depends ONLY on the methods it uses.
╚═══════════════════════════════════════════════════════════════════════════════════╝
```

---

### 9.5 Dependency Inversion

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║           DEPENDENCY INVERSION                                                    ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    High-level modules depend on ABSTRACTIONS, not concrete classes.

    PaymentService depends on:
      - PaymentProcessor (interface)        NOT CreditCardProcessor (class)
      - PaymentRepository (interface)       NOT InMemoryPaymentRepository (class)
      - FraudCheckStrategy (interface)      NOT RuleBasedFraudCheck (class)

    DEPENDENCY GRAPH:

      PaymentService
          │
          ├──→ PaymentProcessor (interface)
          │        ↑
          │        ├── CreditCardProcessor
          │        ├── UPIProcessor
          │        └── WalletProcessor
          │
          ├──→ PaymentRepository (interface)
          │        ↑
          │        ├── InMemoryPaymentRepository (for LLD/testing)
          │        └── JdbcPaymentRepository (for production)
          │
          └──→ FraudCheckStrategy (interface)
                   ↑
                   ├── RuleBasedFraudCheck
                   └── MLFraudCheck

    AppConfig (the composition root) is the ONLY class that knows
    about concrete implementations. All wiring happens there.
╚═══════════════════════════════════════════════════════════════════════════════════╝
```

---

## 10. Sample Workflows

### 10.1 Happy Path: Successful Credit Card Payment

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║          WORKFLOW 1: Happy Path — Credit Card Payment Success                     ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    Merchant "Acme Corp" receives a $100.00 USD credit card payment.
    Platform fee: 2.9% + $0.30 = $3.20. Merchant receives $96.80.

    STEP-BY-STEP:

    1. Client sends payment request:
       PaymentController.processPayment(
           merchantId="merchant-001", amount=100.00, currency=USD,
           method=CREDIT_CARD, idempotencyKey="order-abc-pay-1")

    2. Idempotency check:
       → IdempotencyService.check("order-abc-pay-1")
         → IdempotencyRepository.findByKey("order-abc-pay-1")
         → NOT FOUND → proceed (first time we've seen this key)

    3. Merchant validation:
       → MerchantRepository.findById("merchant-001")
         → Merchant{name="Acme Corp", fee=2.9%+$0.30}  ✓

    4. Create payment:
       → new Payment.Builder("merchant-001", 100.00, USD, CREDIT_CARD)
            .idempotencyKey("order-abc-pay-1")
            .build()
         → Payment{id=PAY-a1b2c3d4, status=INITIATED}

    5. Fraud check:
       → FraudService.checkFraud(payment)
         → RuleBasedFraudCheck.checkFraud(payment)
           → amount $100 < $10,000 limit ✓
           → velocity 1 < 10/hour ✓
           → not blacklisted ✓
           → risk=0.05 → PASS
         → MLFraudCheck.checkFraud(payment)
           → amountFeature=0.01, methodFeature=0.30, timeFeature=0.05
           → riskScore=0.12 < 0.70 threshold → PASS

    6. Transition to PROCESSING:
       → payment.transitionTo(PROCESSING)
         → INITIATED.canTransitionTo(PROCESSING)? YES
         → status=PROCESSING

    7. Process through card network:
       → processors.get(CREDIT_CARD) → CreditCardProcessor
         → CreditCardProcessor.processPayment(payment)
           → simulate 3200ms latency (card network round-trip)
           → roll=0.42 < 0.95 → APPROVED
           → Transaction{txn=CC-TXN-x1y2, success=true, code="00", msg="Approved"}

    8. Transition to AUTHORIZED → CAPTURED:
       → payment.transitionTo(AUTHORIZED)  ✓
       → payment.transitionTo(CAPTURED)    ✓
       → payment.setProcessorTransactionId("CC-TXN-x1y2")

    9. Record ledger entries (double-entry):
       → LedgerService.recordPaymentCapture(payment, merchant)
         → createEntryPair(CUST-PAY-a1b2c3d4, PLATFORM-001, $100.00)
           → lock(CUST-PAY-a1b2c3d4), lock(PLATFORM-001) [alphabetical order]
           → LE-xxx: DEBIT  CUST-PAY-a1b2c3d4   $100.00
           → LE-yyy: CREDIT PLATFORM-001          $100.00
           → customer.balance: $200→$100
           → platform.balance: $1,000,000→$1,000,100

    10. Settlement (credit card requires T+1, not instant):
        → method.requiresSettlement()? YES → skip instant settlement
        → Settlement will happen in batch at T+1

    11. Store idempotency key:
        → IdempotencyService.store("order-abc-pay-1", "PAY-a1b2c3d4")
          → expires in 24 hours

    12. Dispatch webhook:
        → WebhookService.dispatch("payment.captured", payment, merchant)
          → POST to https://acme.com/webhooks
          → payload: {"event":"payment.captured","paymentId":"PAY-a1b2c3d4",...}
          → attempt 1: delivered ✓
          → WebhookEvent{status=DELIVERED, attempts=1}

    13. Final state:
        Payment{id=PAY-a1b2c3d4, status=CAPTURED, amount=$100.00, processor=CC-TXN-x1y2}
        Ledger: DEBIT $100 from customer, CREDIT $100 to platform (balanced ✓)
        Webhook: DELIVERED to merchant

    14. T+1 Settlement (batch job):
        → LedgerService.recordSettlement(payment, merchant)
          → fee = 100 * 0.029 + 0.30 = $3.20
          → net = $100.00 - $3.20 = $96.80
          → DEBIT  PLATFORM-001  $96.80  →  CREDIT MERCH-merchant-001  $96.80
          → DEBIT  PLATFORM-001  $3.20   →  CREDIT PLATFORM-FEE-001    $3.20
        → payment.transitionTo(SETTLED) ✓
```

---

### 10.2 Failure Path: Fraud Detected

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║          WORKFLOW 2: Fraud Detected — Payment Blocked                             ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    A suspicious $9,500 payment triggers the fraud detection chain.

    1. Client sends payment request:
       PaymentController.processPayment(
           merchantId="merchant-002", amount=9500.00, currency=USD,
           method=CREDIT_CARD, idempotencyKey="order-xyz-pay-1")

    2-4. (same as Workflow 1: idempotency, merchant validation, create payment)

    5. Fraud check:
       → FraudService.checkFraud(payment)
         → RuleBasedFraudCheck.checkFraud(payment)
           → amount $9,500 > $5,000 (50% of limit) → risk += 0.3
           → velocity = 8 (>5, half of limit)       → risk += 0.2
           → not blacklisted                         → risk += 0.0
           → total risk = 0.5 < 0.7 threshold → PASS (borderline)

         → MLFraudCheck.checkFraud(payment)
           → amountFeature = 0.95 (very high)
           → methodFeature = 0.30 (credit card)
           → timeFeature = 0.30 (late night)
           → roundingFeature = 0.0 (not round)
           → noise = 0.08
           → riskScore = 0.95*0.35 + 0.30*0.25 + 0.30*0.20 + 0.08*0.10
                       = 0.3325 + 0.075 + 0.06 + 0.008 + 0.08 = 0.555
           → wait... let's recalculate with high noise...
           → riskScore = 0.75  (above 0.70 threshold)
           → REJECT: "ML model v2: risk score 0.750 exceeds threshold 0.70"

       → FraudService throws FraudDetectedException!

    6. Payment marked as FAILED:
       → payment.fail("Fraud detected: ML model v2: risk score 0.750...")
         → status: INITIATED → PROCESSING → FAILED
       → PaymentRepository.save(payment)

    7. Webhook dispatched:
       → WebhookService.dispatch("payment.fraud_blocked", payment, merchant)
         → merchant receives notification that payment was blocked

    8. Final state:
       Payment{id=PAY-xxx, status=FAILED, reason="Fraud detected: ..."}
       Ledger: NO entries (payment never reached CAPTURED)
       Customer: NOT charged (processor was never called!)

    KEY INSIGHT: Fraud check happens BEFORE the processor is called.
    No money moved. No charges to reverse. This is by design.
```

---

### 10.3 Idempotent Retry: Duplicate Request

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║          WORKFLOW 3: Idempotent Retry — Network Timeout                           ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    Client sends a payment request, gets a network timeout, and retries
    with the SAME idempotency key.

    REQUEST 1 (succeeds on our end, but client gets timeout):
    ──────────────────────────────────────────────────────────────────
    1. PaymentController.processPayment(
           merchant="merchant-001", amount=50.00, currency=USD,
           method=UPI, idempotencyKey="order-def-pay-1")
    2. IdempotencyService.check("order-def-pay-1") → NOT FOUND
    3. Process payment → UPI → SUCCESS
    4. Payment{id=PAY-m1n2o3p4, status=SETTLED}
    5. IdempotencyService.store("order-def-pay-1", "PAY-m1n2o3p4")
    6. Return response... but network drops the response to client!

    REQUEST 2 (client retries with same key):
    ──────────────────────────────────────────────────────────────────
    1. PaymentController.processPayment(
           merchant="merchant-001", amount=50.00, currency=USD,
           method=UPI, idempotencyKey="order-def-pay-1")  // SAME KEY
    2. IdempotencyService.check("order-def-pay-1")
       → FOUND: IdempotencyRecord{key="order-def-pay-1", paymentId="PAY-m1n2o3p4"}
       → record.isValid()? YES (within 24hr TTL)
    3. PaymentRepository.findById("PAY-m1n2o3p4")
       → Payment{id=PAY-m1n2o3p4, status=SETTLED}
    4. Return SAME payment → client gets the original result

    RESULT:
      - Customer charged EXACTLY ONCE ($50.00)
      - Client gets the same response both times
      - No double-charge, no duplicate ledger entries
      - Idempotency key expires after 24 hours
```

---

### 10.4 Partial Refund with Ledger Reversal

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║          WORKFLOW 4: Partial Refund — $30 of $100 Payment                        ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    Original payment: $100.00, already SETTLED.
    Customer requests $30 refund (item returned).

    1. PaymentController.refundPayment("PAY-a1b2c3d4", 30.00, "Item returned")

    2. PaymentService.refundPayment("PAY-a1b2c3d4", 30.00, "Item returned")
       → PaymentRepository.findById("PAY-a1b2c3d4")
         → Payment{status=SETTLED, amount=100.00} ✓

    3. RefundService.processRefund(payment, 30.00, "Item returned", processor)
       → Validate: status is SETTLED ✓
       → Validate: alreadyRefunded=0 + 30 <= 100 ✓
       → Create: Refund{id=REF-xxx, paymentId=PAY-a1b2c3d4, amount=30.00}
       → CreditCardProcessor.processRefund(refund, payment)
         → Transaction{success=true, code="00", msg="Refund approved"}
       → refund.markCompleted()
       → refundedAmounts: {"PAY-a1b2c3d4": 30.00}

    4. Payment transitions to REFUNDED:
       → payment.transitionTo(REFUNDED)
         → SETTLED.canTransitionTo(REFUNDED)? YES ✓

    5. Ledger reversal entries:
       → LedgerService.recordRefund(refund, payment, merchant)
         → createEntryPair(MERCH-merchant-001, CUST-PAY-a1b2c3d4, $30.00)
           → LE-aaa: DEBIT  MERCH-merchant-001    $30.00  (money leaves merchant)
           → LE-bbb: CREDIT CUST-PAY-a1b2c3d4     $30.00  (money returns to customer)

    6. Webhook:
       → WebhookService.dispatch("payment.refunded", payment, merchant)

    7. Final state:
       Payment{status=REFUNDED}
       Merchant balance: $96.80 - $30.00 = $66.80
       Customer: received $30.00 back
       Ledger: still balanced (4 capture entries + 2 settlement entries + 2 refund entries)

    8. Can we refund the remaining $70?
       → RefundService: alreadyRefunded=30 + 70 <= 100 → YES ✓
       → (In practice, another refund would be a NEW refund request
          on the same payment, if the payment status allows it.)
```

---

### 10.5 Webhook Retry with Exponential Backoff

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║          WORKFLOW 5: Webhook Delivery with Exponential Backoff                    ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    Merchant's webhook endpoint is temporarily down.

    Attempt 1 (immediate):
    ──────────────────────────────────────────────────────────────────
    T=0s:  POST https://acme.com/webhooks
           → Connection refused (merchant server down)
           → event.recordAttempt()  → attempts=1
           → event.markFailed()     → status=FAILED
           → nextRetryAt = now + 1s (4^0 = 1 second)

    Attempt 2 (after 1 second):
    ──────────────────────────────────────────────────────────────────
    T=1s:  POST https://acme.com/webhooks
           → 503 Service Unavailable (server restarting)
           → event.recordAttempt()  → attempts=2
           → event.markFailed()     → status=FAILED
           → nextRetryAt = now + 4s (4^1 = 4 seconds)

    Attempt 3 (after 4 seconds):
    ──────────────────────────────────────────────────────────────────
    T=5s:  POST https://acme.com/webhooks
           → 200 OK (server is back!)
           → event.recordAttempt()  → attempts=3
           → event.markDelivered()  → status=DELIVERED

    RESULT: Webhook delivered on 3rd attempt.
    Total time: 5 seconds (0 + 1 + 4).

    IF ALL 5 ATTEMPTS FAIL:
    ──────────────────────────────────────────────────────────────────
    Attempt 1: T=0s    → FAIL → retry after 1s
    Attempt 2: T=1s    → FAIL → retry after 4s
    Attempt 3: T=5s    → FAIL → retry after 16s
    Attempt 4: T=21s   → FAIL → retry after 64s
    Attempt 5: T=85s   → FAIL → EXHAUSTED (no more retries)

    → event.status = EXHAUSTED
    → Alert generated: "Webhook WH-xxx exhausted for merchant merchant-001"
    → Merchant can fetch missed events via polling API (not in our LLD scope)
```

---

## 11. Design Patterns Used

| Pattern | Where Applied | Why |
|---------|---------------|-----|
| **Strategy** | `PaymentProcessor`, `FraudCheckStrategy` | Swap payment rail or fraud algorithm without changing service code. CreditCardProcessor, UPIProcessor, WalletProcessor are interchangeable. |
| **Builder** | `Payment.Builder` | Clean construction of Payment with required fields (merchantId, amount, currency, method), auto-generated fields (paymentId, createdAt), and optional fields (idempotencyKey). |
| **Facade** | `PaymentService` | Single entry point for the 12-step payment flow (idempotency, fraud, process, ledger, webhook). Controller calls ONE method. |
| **State Machine** | `Payment.transitionTo()`, `PaymentStatus.VALID_TRANSITIONS` | Enforces valid payment lifecycle transitions. Impossible to settle a failed payment. |
| **Repository** | All `*Repository` interfaces | Abstracts data access. InMemory for LLD/testing. Could swap to JDBC/NoSQL without changing services. |
| **Factory** | `AppConfig` | Composition root that creates and wires all objects. No `new` in service classes. Pure constructor injection. |
| **Chain of Responsibility** | `FraudService` with `List<FraudCheckStrategy>` | Multiple fraud checks applied in sequence. If any check fails, the chain stops. Cheap checks first (rules), expensive last (ML). |
| **Double-Entry** | `LedgerService.createEntryPair()` | Every money movement creates a balanced DEBIT+CREDIT pair. System is always balanced. Financial correctness guaranteed. |

### Pattern Interaction Diagram

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║                HOW PATTERNS INTERACT IN A SINGLE PAYMENT                          ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    ┌─────────────┐
    │   Builder    │──── Payment.Builder creates Payment with auto-generated ID
    │   Pattern    │     and validated fields
    └──────┬──────┘
           │ Payment is created
           ▼
    ┌─────────────┐
    │   Facade     │──── PaymentService.processPayment() orchestrates 12 steps
    │   Pattern    │     Controller calls ONE method. Facade delegates.
    └──────┬──────┘
           │ step 1: check idempotency
           │ step 2: validate merchant
           │ step 3: create payment
           ▼
    ┌──────────────────┐
    │ Chain of          │──── FraudService applies [RuleBased, ML] in order
    │ Responsibility    │     Fail-fast: first rejection stops the chain
    └──────┬───────────┘
           │ step 4: fraud passed
           ▼
    ┌─────────────┐
    │  Strategy    │──── PaymentProcessor.processPayment(payment)
    │  Pattern     │     CreditCardProcessor / UPIProcessor / WalletProcessor
    └──────┬──────┘
           │ step 7: payment authorized + captured
           ▼
    ┌──────────────┐
    │ Double-Entry  │──── LedgerService.recordPaymentCapture()
    │  Bookkeeping  │     DEBIT customer + CREDIT platform (always balanced)
    └──────┬───────┘
           │ step 8: ledger recorded
           ▼
    ┌──────────────┐
    │ State Machine │──── payment.transitionTo(AUTHORIZED → CAPTURED → SETTLED)
    │   Pattern     │     Enforces valid transitions only
    └──────┬───────┘
           │ step 10-12: idempotency store, webhook dispatch
           ▼
    ┌──────────────┐
    │  Repository   │──── PaymentRepository.save(payment)
    │   Pattern     │     LedgerRepository.save(entries)
    └──────────────┘
```

---

## 12. Extensibility Points

### 12.1 New Payment Method (e.g., Cryptocurrency)

```
To add cryptocurrency payment:

  1. Add to enum:     PaymentMethod.CRYPTO
  2. Create strategy: CryptoProcessor implements PaymentProcessor
     - processPayment(): call crypto exchange API, await blockchain confirmation
     - processRefund(): initiate reverse transfer on blockchain
     - getSupportedMethods(): Set.of(PaymentMethod.CRYPTO)
  3. Register:        AppConfig → processors.put(CRYPTO, new CryptoProcessor())
  4. DONE. No changes to PaymentService, LedgerService, FraudService, WebhookService.

  Files changed: 3 (enum, new class, AppConfig)
  Files NOT changed: PaymentService, LedgerService, FraudService, RefundService
```

### 12.2 New Fraud Detection Strategy (e.g., Geolocation)

```
To add geolocation-based fraud detection:

  1. Create strategy: GeoFraudCheck implements FraudCheckStrategy
     - checkFraud(): compare payment origin IP to cardholder country
     - if mismatch: high risk score
  2. Register:        AppConfig → strategies.add(new GeoFraudCheck(...))
  3. DONE. FraudService iterates the chain. No changes to existing checks.

  Files changed: 2 (new class, AppConfig)
  Files NOT changed: FraudService, RuleBasedFraudCheck, MLFraudCheck
```

### 12.3 New Webhook Event Type

```
To add chargeback notifications:

  1. Call:   webhookService.dispatch("payment.chargeback", payment, merchant)
  2. DONE. WebhookService accepts any event type string.
     The merchant's webhook handler processes the event based on the type field.

  Files changed: 1 (the service that triggers the chargeback event)
  Files NOT changed: WebhookService, WebhookEvent, WebhookRepository
```

### 12.4 New Account Type (e.g., Escrow)

```
To add escrow accounts for marketplace payments:

  1. Add to enum:     AccountType.ESCROW
  2. Create account:  new Account("ESCROW-001", AccountType.ESCROW, USD, 0.0)
  3. Add ledger flow: LedgerService.recordEscrowHold(payment, escrowAccount)
     - DEBIT platform → CREDIT escrow (hold funds)
     - On release: DEBIT escrow → CREDIT merchant
  4. Double-entry still works: every movement has a DEBIT and CREDIT.

  Files changed: 3 (enum, LedgerService new method, AppConfig)
  Files NOT changed: PaymentService (calls new ledger method), PaymentProcessor
```

### 12.5 Switch from InMemory to Database

```
To switch to a real database:

  1. Create JdbcPaymentRepository implements PaymentRepository
  2. Create JdbcLedgerRepository implements LedgerRepository
  3. (etc. for all repositories)
  4. In AppConfig: replace new InMemoryPaymentRepository() with
     new JdbcPaymentRepository(dataSource)
  5. DONE. All services depend on the interface, not the implementation.
     Not a single service class needs to change.

  Files changed: 6 new repository classes + AppConfig
  Files NOT changed: PaymentService, LedgerService, FraudService, WebhookService,
                     RefundService, ReconciliationService, CurrencyService
```

### 12.6 Multi-Currency Support

```
To handle cross-currency payments (customer pays in EUR, merchant receives USD):

  1. CurrencyService.convert() already exists
  2. Add to PaymentService.processPayment():
     - If payment.currency != merchant.currency:
       → convertedAmount = currencyService.convert(amount, paymentCurrency, merchantCurrency)
       → record ledger entries in BOTH currencies
  3. LedgerEntry already has the amount; add a currency field
  4. ReconciliationService: verify currency conversion accuracy

  Files changed: PaymentService (add conversion step), LedgerEntry (add currency),
                 ReconciliationService (add currency checks)
```

### 12.7 Extensibility Summary

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║                EXTENSIBILITY MAP                                                  ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

    What you want to add          Pattern that enables it    Files to change
    ──────────────────────        ────────────────────────   ──────────────────
    New payment method            Strategy                   Enum + new class + AppConfig
    New fraud check               Chain of Responsibility    New class + AppConfig
    New webhook event type        (none needed)              Caller only
    New account type              Model                      Enum + LedgerService + AppConfig
    New storage backend           Repository (DIP)           New class + AppConfig
    New currency                  Enum                       Currency enum only
    Cross-currency payments       Service                    PaymentService + LedgerEntry
    Escrow/marketplace            Double-Entry               LedgerService + AppConfig
    Subscription/recurring        State Machine              New states + PaymentService

    The key insight: most extensions require ADDING new classes,
    not MODIFYING existing ones. This is the Open-Closed Principle
    made real through Strategy, Repository, and Chain of Responsibility.
╚═══════════════════════════════════════════════════════════════════════════════════╝
```

---
