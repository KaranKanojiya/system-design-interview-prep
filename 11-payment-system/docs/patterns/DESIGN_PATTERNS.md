# Design Patterns in the Payment System (Stripe/UPI)

> Interview-ready reference for a Senior Java developer.
> A payment system is the most safety-critical domain in system design -- every pattern exists to prevent double-charges, lost money, or inconsistent ledgers.
> For each pattern: ugly anti-pattern code, clean pattern-based code, numbered call chain, and interview one-liner.

---

## Table of Contents

| # | Pattern | GoF Category | Key Class(es) | One-Liner |
|---|---------|-------------|---------------|-----------|
| 1 | Strategy (x2) | Behavioral | `PaymentProcessor` (CreditCard, UPI, Wallet), `FraudCheckStrategy` (RuleBased, ML) | Swap payment gateways and fraud algorithms without changing service code |
| 2 | Builder | Creational | `Payment.Builder`, `Refund.Builder` | Many optional fields (metadata, currency, description) -- Builder prevents telescoping constructors |
| 3 | Factory | Creational | `AppConfig` creates all objects and wires dependencies | Centralized object creation, only class that says `new ConcreteClass()` |
| 4 | Repository | Structural (DDD) | 6 repos: `PaymentRepository`, `LedgerRepository`, `MerchantRepository`, `AccountRepository`, `IdempotencyRepository`, `WebhookRepository` | Decouple domain from storage (swap in-memory to PostgreSQL/Redis) |
| 5 | Facade | Structural | `PaymentService` orchestrates idempotency -> fraud -> processing -> ledger -> webhook | One entry point hides the entire payment workflow |
| 6 | Observer | Behavioral | `WebhookService` observes payment events, notifies merchants | Decouple merchant notification from payment processing |
| 7 | State | Behavioral | `Payment` state machine: INITIATED -> PROCESSING -> AUTHORIZED -> CAPTURED -> SETTLED | Eliminates if-else state checks; each state knows its own transitions |
| 8 | Chain of Responsibility | Behavioral | `FraudService` chains multiple `FraudCheckStrategy` implementations | Each fraud check passes or escalates to the next check in the chain |
| 9 | Singleton | Creational | `CurrencyService` -- single exchange rate source | One source of truth for exchange rates across the entire system |
| 10 | Template Method | Behavioral | `PaymentProcessor` common flow: validate -> process -> respond | Each processor implements the steps differently but the skeleton is fixed |

---

## 1. Strategy Pattern (x2)

### What

Define a family of algorithms, encapsulate each behind a common interface, and make them interchangeable at runtime. This project uses Strategy TWICE -- payment processing and fraud detection -- each representing a different axis of variation.

### ASCII Diagram -- Both Strategy Hierarchies

```
  PAYMENT PROCESSOR STRATEGY                FRAUD CHECK STRATEGY
  ==========================                ====================

  +-----------------------------+           +-----------------------------+
  | <<interface>>               |           | <<interface>>               |
  | PaymentProcessor            |           | FraudCheckStrategy          |
  +-----------------------------+           +-----------------------------+
  | + validate(Payment): bool   |           | + checkFraud(Payment):      |
  | + process(Payment):         |           |   FraudResult               |
  |   PaymentResult             |           +-------------+---------------+
  | + supportsMethod(Method):   |                         |
  |   boolean                   |                   +-----+------+
  +-------------+---------------+                   |            |
                |                           +-------+------+ +---+----------+
          +-----+------+                   | RuleBased     | | MLBased      |
          |     |      |                   | FraudCheck    | | FraudCheck   |
   +------+--+ +--+------+ +--+------+    | Strategy      | | Strategy     |
   | Credit  | | UPI     | | Wallet  |    | (threshold,   | | (model       |
   | Card    | | Payment | | Payment |    |  velocity,    | |  inference,  |
   | Proc.   | | Proc.   | | Proc.   |    |  blacklist)   | |  risk score) |
   +---------+ +---------+ +---------+    +--------------+ +--------------+
```

### Ugly Code -- Without Strategy

```java
// ANTI-PATTERN: if-else chain in PaymentService
// Every new payment method or fraud check = modify this god class = OCP violation
public class PaymentService {

    private String paymentMethod = "CREDIT_CARD";   // magic string
    private String fraudCheckType = "RULE_BASED";   // another magic string

    public PaymentResult processPayment(Payment payment) {
        // Step 1: Fraud check
        boolean isFraudulent;
        if (fraudCheckType.equals("RULE_BASED")) {
            // 40 lines of rule-based fraud detection inline
            isFraudulent = payment.getAmount().compareTo(BigDecimal.valueOf(10000)) > 0;
            if (!isFraudulent) {
                // Check velocity -- 10+ transactions in 1 minute
                int recentTxns = countRecentTransactions(payment.getUserId(), 60);
                isFraudulent = recentTxns > 10;
            }
            if (!isFraudulent) {
                // Check blacklist
                isFraudulent = isBlacklisted(payment.getUserId());
            }
        } else if (fraudCheckType.equals("ML_BASED")) {
            // 60 lines of ML model inference
            double riskScore = mlModel.predict(extractFeatures(payment));
            isFraudulent = riskScore > 0.85;
        }
        // Adding geo-fencing fraud check? Biometric? -- more else-if...

        if (isFraudulent) {
            return PaymentResult.declined("Fraud detected");
        }

        // Step 2: Process payment
        if (paymentMethod.equals("CREDIT_CARD")) {
            // 50 lines of Stripe API calls with error handling
            return chargeStripe(payment);
        } else if (paymentMethod.equals("UPI")) {
            // 40 lines of NPCI API calls
            return chargeUPI(payment);
        } else if (paymentMethod.equals("WALLET")) {
            // 30 lines of internal wallet deduction
            return deductWallet(payment);
        }
        // Adding PayPal? Apple Pay? Crypto? -- more else-if...

        throw new IllegalStateException("Unknown payment method: " + paymentMethod);
    }
}
```

**Problems with this approach:**
- `PaymentService` knows about every payment gateway API AND every fraud detection algorithm (SRP violation)
- Adding a new payment method or fraud check requires modifying `PaymentService` (OCP violation)
- Cannot unit-test fraud checks or payment methods in isolation
- Magic strings for mode selection -- no compile-time safety
- Fraud detection and payment processing are tangled together in one 200-line method

### Clean Code -- With Strategy

```java
// --- Strategy 1: Payment Processing ---
public interface PaymentProcessor {
    // (1) Validate payment details specific to this method
    boolean validate(Payment payment);

    // (2) Process the payment through the gateway
    PaymentResult process(Payment payment);

    // (3) Check if this processor handles the given method
    boolean supportsMethod(PaymentMethod method);
}

public class CreditCardProcessor implements PaymentProcessor {
    @Override
    public boolean validate(Payment payment) {
        // (1) Validate card number (Luhn check)
        // (2) Validate expiry date
        // (3) Validate CVV format
        return payment.getCardNumber() != null
            && LuhnValidator.isValid(payment.getCardNumber());
    }

    @Override
    public PaymentResult process(Payment payment) {
        // (1) Tokenize card via PCI-compliant vault
        String token = tokenize(payment.getCardNumber());
        // (2) Send authorization request to card network (Visa/MC)
        // (3) Return authorization code or decline reason
        String authCode = "AUTH-" + UUID.randomUUID();
        return PaymentResult.authorized(authCode, "Credit card authorized");
    }

    @Override
    public boolean supportsMethod(PaymentMethod method) {
        return method == PaymentMethod.CREDIT_CARD;
    }
}

public class UPIPaymentProcessor implements PaymentProcessor {
    @Override
    public boolean validate(Payment payment) {
        // (1) Validate VPA format (user@bank)
        return payment.getUpiId() != null
            && payment.getUpiId().contains("@");
    }

    @Override
    public PaymentResult process(Payment payment) {
        // (1) Send collect request to NPCI
        // (2) Wait for user approval on banking app
        // (3) Return UPI reference number or timeout
        String upiRef = "UPI-" + UUID.randomUUID();
        return PaymentResult.authorized(upiRef, "UPI payment approved");
    }

    @Override
    public boolean supportsMethod(PaymentMethod method) {
        return method == PaymentMethod.UPI;
    }
}

public class WalletPaymentProcessor implements PaymentProcessor {
    @Override
    public boolean validate(Payment payment) {
        // (1) Check wallet exists for user
        // (2) Check sufficient balance
        return payment.getWalletId() != null;
    }

    @Override
    public PaymentResult process(Payment payment) {
        // (1) Deduct from wallet (atomic operation)
        // (2) Return new balance confirmation
        String walletRef = "WLT-" + UUID.randomUUID();
        return PaymentResult.authorized(walletRef, "Wallet debited");
    }

    @Override
    public boolean supportsMethod(PaymentMethod method) {
        return method == PaymentMethod.WALLET;
    }
}

// --- Strategy 2: Fraud Detection ---
public interface FraudCheckStrategy {
    FraudResult checkFraud(Payment payment);
}

public class RuleBasedFraudCheckStrategy implements FraudCheckStrategy {
    private final BigDecimal amountThreshold;
    private final int velocityLimit;

    @Override
    public FraudResult checkFraud(Payment payment) {
        // (1) Check amount threshold
        if (payment.getAmount().compareTo(amountThreshold) > 0) {
            return FraudResult.flagged("Amount exceeds threshold");
        }
        // (2) Check velocity (transactions per minute)
        // (3) Check blacklist
        return FraudResult.clean();
    }
}

public class MLBasedFraudCheckStrategy implements FraudCheckStrategy {
    @Override
    public FraudResult checkFraud(Payment payment) {
        // (1) Extract features (amount, time, geo, device)
        // (2) Run ML model inference
        // (3) Return risk score and decision
        double riskScore = 0.15; // simulated
        return riskScore > 0.85
            ? FraudResult.flagged("ML model risk score: " + riskScore)
            : FraudResult.clean();
    }
}
```

### PaymentService -- Uses Strategies (Doesn't Know the Algorithm)

```java
public class PaymentService {
    private final List<PaymentProcessor> processors;  // injected
    private final FraudService fraudService;           // uses FraudCheckStrategy chain

    public PaymentResult processPayment(Payment payment) {
        // (1) Find the right processor for this payment method
        PaymentProcessor processor = processors.stream()
            .filter(p -> p.supportsMethod(payment.getMethod()))
            .findFirst()
            .orElseThrow(() -> new UnsupportedPaymentMethodException(payment.getMethod()));

        // (2) Run fraud checks (delegates to FraudCheckStrategy chain)
        FraudResult fraudResult = fraudService.runChecks(payment);
        if (fraudResult.isFlagged()) {
            return PaymentResult.declined(fraudResult.getReason());
        }

        // (3) Validate payment details via the processor
        if (!processor.validate(payment)) {
            return PaymentResult.declined("Validation failed");
        }

        // (4) Process the payment -- we don't know WHICH gateway
        return processor.process(payment);
    }
}
```

### Numbered Call Chain -- processPayment() with CreditCard + RuleBased Fraud

```
  Client        PaymentService    FraudService     RuleBasedFraud   CreditCardProc
    |                |                |                 |                |
    | (1) process    |                |                 |                |
    |  Payment(pay)  |                |                 |                |
    |--------------->|                |                 |                |
    |                |                |                 |                |
    |                | (2) find       |                 |                |
    |                |  processor for |                 |                |
    |                |  CREDIT_CARD   |                 |                |
    |                |  (from list)   |                 |                |
    |                |                |                 |                |
    |                | (3) runChecks  |                 |                |
    |                |  (payment)     |                 |                |
    |                |--------------->|                 |                |
    |                |                |                 |                |
    |                |                | (4) checkFraud  |                |
    |                |                |  (payment)      |                |
    |                |                |---------------->|                |
    |                |                |                 |                |
    |                |                |                 | (5) check      |
    |                |                |                 |  amount < 10K  |
    |                |                |                 |  velocity < 10 |
    |                |                |                 |  not blacklist |
    |                |                |                 |                |
    |                |                |  FraudResult    |                |
    |                |                |  (CLEAN)        |                |
    |                |                |<----------------|                |
    |                |  FraudResult   |                 |                |
    |                |  (CLEAN)       |                 |                |
    |                |<---------------|                 |                |
    |                |                |                 |                |
    |                | (6) validate   |                 |                |
    |                |  (payment)     |                 |                |
    |                |---------------------------------------->|
    |                |                |                 |       |
    |                |                |                 | (7) Luhn check |
    |                |                |                 |  expiry check  |
    |                |                |                 |  CVV check     |
    |                |  true          |                 |                |
    |                |<----------------------------------------|
    |                |                |                 |                |
    |                | (8) process    |                 |                |
    |                |  (payment)     |                 |                |
    |                |---------------------------------------->|
    |                |                |                 |       |
    |                |                |                 | (9) tokenize   |
    |                |                |                 |  card number   |
    |                |                |                 | (10) send auth |
    |                |                |                 |  to Visa/MC    |
    |                |  PaymentResult |                 |                |
    |                |  (AUTH-uuid)   |                 |                |
    |                |<----------------------------------------|
    |                |                |                 |                |
    |  PaymentResult |                |                 |                |
    |  (AUTHORIZED,  |                |                 |                |
    |   AUTH-uuid)   |                |                 |                |
    |<---------------|                |                 |                |
```

### Interview One-Liner

> "We use Strategy twice: PaymentProcessor lets us swap CreditCard/UPI/Wallet gateways without touching PaymentService, and FraudCheckStrategy lets us plug in RuleBased/ML fraud detection. Each axis of variation -- how we charge money and how we detect fraud -- is independently swappable. Classic OCP."

### Cross-Reference
- **Chain of Responsibility** (Pattern 8) -- FraudService chains multiple FraudCheckStrategy instances
- **Factory** (Pattern 3) -- `AppConfig` decides which strategy implementations to wire
- **Template Method** (Pattern 10) -- Each PaymentProcessor follows a validate -> process -> respond skeleton
- **Facade** (Pattern 5) -- PaymentService orchestrates strategies together

---

## 2. Builder Pattern

### What

Separate the construction of a complex object from its representation. In a payment system, objects like `Payment` and `Refund` have many optional fields (currency, description, metadata, idempotencyKey) -- Builder prevents telescoping constructors and makes construction readable.

### ASCII Diagram

```
  +-------------------------------+       +-------------------------------+
  | Payment.Builder               |       | Payment (immutable)           |
  +-------------------------------+       +-------------------------------+
  | - paymentId: String           |       | - paymentId: String           |
  | - merchantId: String          |       | - merchantId: String          |
  | - amount: BigDecimal          | build | - amount: BigDecimal          |
  | - currency: Currency          |------>| - currency: Currency          |
  | - method: PaymentMethod       |       | - method: PaymentMethod       |
  | - status: PaymentStatus       |       | - status: PaymentStatus       |
  | - description: String         |       | - description: String         |
  | - idempotencyKey: String      |       | - idempotencyKey: String      |
  | - metadata: Map<String,String>|       | - metadata: Map<String,String>|
  | - createdAt: Instant          |       | - createdAt: Instant          |
  +-------------------------------+       +-------------------------------+
  | + paymentId(String): Builder  |
  | + merchantId(String): Builder |       Similarly for:
  | + amount(BigDecimal): Builder |       - Refund.Builder
  | + currency(Currency): Builder |
  | + method(Method): Builder     |
  | + description(String): Builder|
  | + idempotencyKey(String): Bldr|
  | + metadata(Map): Builder      |
  | + build(): Payment            |
  +-------------------------------+
```

### Ugly Code -- Without Builder

```java
// ANTI-PATTERN: Telescoping constructors
// Payment has 10+ fields, most optional -- how many constructors do you need?
public class Payment {
    // Constructor 1: bare minimum
    public Payment(String paymentId, String merchantId, BigDecimal amount) { ... }

    // Constructor 2: with currency
    public Payment(String paymentId, String merchantId, BigDecimal amount,
                   Currency currency) { ... }

    // Constructor 3: with currency and method
    public Payment(String paymentId, String merchantId, BigDecimal amount,
                   Currency currency, PaymentMethod method) { ... }

    // Constructor 4: with everything
    public Payment(String paymentId, String merchantId, BigDecimal amount,
                   Currency currency, PaymentMethod method, PaymentStatus status,
                   String description, String idempotencyKey,
                   Map<String, String> metadata, Instant createdAt) { ... }
}

// Caller: which argument is which? Completely unreadable.
Payment payment = new Payment("PAY-123", "MERCH-456",
    new BigDecimal("99.99"), Currency.USD, PaymentMethod.CREDIT_CARD,
    PaymentStatus.INITIATED, null, "idem-key-789", null, Instant.now());
//                           ^^^^                  ^^^^
//                     What's null here? description? metadata?
```

**Problems with this approach:**
- 4+ constructors, hard to maintain
- `null` for optional fields -- unreadable at the call site
- Swapping argument order compiles but introduces bugs (String, String, String... which is which?)
- Cannot enforce required vs optional fields at compile time

### Clean Code -- With Builder

```java
public class Payment {
    private final String paymentId;
    private final String merchantId;
    private final BigDecimal amount;
    private final Currency currency;
    private final PaymentMethod method;
    private PaymentStatus status;
    private final String description;
    private final String idempotencyKey;
    private final Map<String, String> metadata;
    private final Instant createdAt;

    private Payment(Builder builder) {
        this.paymentId = builder.paymentId;
        this.merchantId = builder.merchantId;
        this.amount = builder.amount;
        this.currency = builder.currency;
        this.method = builder.method;
        this.status = builder.status;
        this.description = builder.description;
        this.idempotencyKey = builder.idempotencyKey;
        this.metadata = Collections.unmodifiableMap(builder.metadata);
        this.createdAt = builder.createdAt;
    }

    // All getters...
    // Status transition method (see State pattern)
    public void transitionTo(PaymentStatus newStatus) { ... }

    public static class Builder {
        // Required
        private final String paymentId;
        private final String merchantId;
        private final BigDecimal amount;

        // Optional with defaults
        private Currency currency = Currency.USD;
        private PaymentMethod method = PaymentMethod.CREDIT_CARD;
        private PaymentStatus status = PaymentStatus.INITIATED;
        private String description = "";
        private String idempotencyKey;
        private Map<String, String> metadata = new HashMap<>();
        private Instant createdAt = Instant.now();

        public Builder(String paymentId, String merchantId, BigDecimal amount) {
            this.paymentId = Objects.requireNonNull(paymentId);
            this.merchantId = Objects.requireNonNull(merchantId);
            this.amount = Objects.requireNonNull(amount);
        }

        public Builder currency(Currency currency) {
            this.currency = currency;
            return this;
        }

        public Builder method(PaymentMethod method) {
            this.method = method;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder idempotencyKey(String key) {
            this.idempotencyKey = key;
            return this;
        }

        public Builder metadata(String key, String value) {
            this.metadata.put(key, value);
            return this;
        }

        public Payment build() {
            // Validate business rules
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Amount must be positive");
            }
            return new Payment(this);
        }
    }
}
```

### Usage -- Readable Construction

```java
// Clear, self-documenting, compile-time safe
Payment payment = new Payment.Builder("PAY-001", "MERCH-100", new BigDecimal("99.99"))
    .currency(Currency.INR)
    .method(PaymentMethod.UPI)
    .description("Monthly subscription")
    .idempotencyKey("idem-" + UUID.randomUUID())
    .metadata("orderId", "ORD-500")
    .metadata("customerTier", "PREMIUM")
    .build();

// Refund -- same pattern
Refund refund = new Refund.Builder("REF-001", "PAY-001", new BigDecimal("49.99"))
    .reason("Customer requested partial refund")
    .build();
```

### Interview One-Liner

> "Payment and Refund have 10+ fields -- idempotencyKey, metadata map, currency, description -- most optional. Builder gives us named, chainable setters, enforces required fields in the constructor, validates business rules in build(), and produces an immutable object."

### Cross-Reference
- **Factory** (Pattern 3) -- AppConfig may use builders when constructing Payment objects in tests
- **State** (Pattern 7) -- Payment built with INITIATED status, then transitions via state machine
- **Facade** (Pattern 5) -- PaymentService creates Payment objects using Builder internally

---

## 3. Factory Pattern

### What

Centralize object creation in a single place so no business logic class ever says `new ConcreteClass()`. `AppConfig` is the single source of truth for wiring -- it creates concrete strategies, repositories, and services, and injects them into each other.

### ASCII Diagram

```
  +----------------------------------------------------------------------+
  |                          AppConfig (Factory)                          |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Creates and wires ALL concrete implementations:                     |
  |                                                                      |
  |  REPOSITORIES:                                                       |
  |  +--------------------+  +--------------------+  +-----------------+ |
  |  | InMemoryPayment    |  | InMemoryLedger     |  | InMemoryMerch.  | |
  |  | Repository         |  | Repository         |  | Repository      | |
  |  +--------------------+  +--------------------+  +-----------------+ |
  |  +--------------------+  +--------------------+  +-----------------+ |
  |  | InMemoryAccount    |  | InMemoryIdempot.   |  | InMemoryWebhook | |
  |  | Repository         |  | Repository         |  | Repository      | |
  |  +--------------------+  +--------------------+  +-----------------+ |
  |                                                                      |
  |  STRATEGIES:                                                         |
  |  +--------------------+  +--------------------+  +-----------------+ |
  |  | CreditCard         |  | UPIPayment         |  | WalletPayment   | |
  |  | Processor          |  | Processor          |  | Processor       | |
  |  +--------------------+  +--------------------+  +-----------------+ |
  |  +--------------------+  +--------------------+                      |
  |  | RuleBasedFraud     |  | MLBasedFraud       |                      |
  |  | CheckStrategy      |  | CheckStrategy      |                      |
  |  +--------------------+  +--------------------+                      |
  |                                                                      |
  |  SERVICES:                                                           |
  |  +--------------------+  +--------------------+  +-----------------+ |
  |  | PaymentService     |  | FraudService       |  | LedgerService   | |
  |  | (Facade)           |  | (Chain of Resp.)   |  |                 | |
  |  +--------------------+  +--------------------+  +-----------------+ |
  |  +--------------------+  +--------------------+                      |
  |  | WebhookService     |  | CurrencyService    |                      |
  |  | (Observer)         |  | (Singleton)        |                      |
  |  +--------------------+  +--------------------+                      |
  |                                                                      |
  +----------------------------------------------------------------------+
                |
                | Only place in the codebase that says
                | "new CreditCardProcessor()"
                | "new RuleBasedFraudCheckStrategy()"
                | "new InMemoryPaymentRepository()"
                v
```

### Ugly Code -- Without Factory

```java
// ANTI-PATTERN: Every class creates its own dependencies
public class PaymentService {
    // Hardcoded concrete classes everywhere
    private PaymentRepository repo = new PostgresPaymentRepository(
        new HikariDataSource("jdbc:postgresql://prod-db:5432/payments"));
    private LedgerService ledger = new LedgerService(
        new PostgresLedgerRepository(
            new HikariDataSource("jdbc:postgresql://prod-db:5432/ledger")));
    private FraudService fraud = new FraudService(
        new RuleBasedFraudCheckStrategy(BigDecimal.valueOf(10000), 10));
    private WebhookService webhooks = new WebhookService(
        new PostgresWebhookRepository(
            new HikariDataSource("jdbc:postgresql://prod-db:5432/webhooks")));

    // Testing? You'd need a real PostgreSQL running.
    // Swap to DynamoDB? Change every service class.
}
```

**Problems:**
- Every service is coupled to concrete implementations (PostgreSQL, HikariCP)
- Cannot swap to in-memory storage for testing
- Database connection details scattered across 6 service classes
- Changing one infrastructure choice requires modifying multiple files

### Clean Code -- With Factory

```java
public class AppConfig {
    // --- Repositories ---
    private final PaymentRepository paymentRepo;
    private final LedgerRepository ledgerRepo;
    private final MerchantRepository merchantRepo;
    private final AccountRepository accountRepo;
    private final IdempotencyRepository idempotencyRepo;
    private final WebhookRepository webhookRepo;

    // --- Strategies ---
    private final List<PaymentProcessor> processors;
    private final List<FraudCheckStrategy> fraudStrategies;

    // --- Services ---
    private final CurrencyService currencyService;
    private final FraudService fraudService;
    private final LedgerService ledgerService;
    private final WebhookService webhookService;
    private final PaymentService paymentService;

    public AppConfig() {
        // (1) Create repositories -- only place that knows "InMemory" vs "Postgres"
        this.paymentRepo = new InMemoryPaymentRepository();
        this.ledgerRepo = new InMemoryLedgerRepository();
        this.merchantRepo = new InMemoryMerchantRepository();
        this.accountRepo = new InMemoryAccountRepository();
        this.idempotencyRepo = new InMemoryIdempotencyRepository();
        this.webhookRepo = new InMemoryWebhookRepository();

        // (2) Create strategies
        this.processors = List.of(
            new CreditCardProcessor(),
            new UPIPaymentProcessor(),
            new WalletPaymentProcessor()
        );
        this.fraudStrategies = List.of(
            new RuleBasedFraudCheckStrategy(BigDecimal.valueOf(10000), 10),
            new MLBasedFraudCheckStrategy()
        );

        // (3) Create services -- inject interfaces, not concrete classes
        this.currencyService = CurrencyService.getInstance();
        this.fraudService = new FraudService(fraudStrategies);
        this.ledgerService = new LedgerService(ledgerRepo, accountRepo);
        this.webhookService = new WebhookService(webhookRepo, merchantRepo);
        this.paymentService = new PaymentService(
            processors, fraudService, ledgerService, webhookService,
            paymentRepo, idempotencyRepo, currencyService
        );
    }

    // Getters for top-level services
    public PaymentService getPaymentService() { return paymentService; }
    public LedgerService getLedgerService() { return ledgerService; }
    // ...
}
```

### Interview One-Liner

> "AppConfig is our composition root -- the only class that says `new CreditCardProcessor()` or `new InMemoryPaymentRepository()`. Every service depends on interfaces. Swap to production? Change AppConfig. Swap to test? Create TestAppConfig. No business logic class changes."

### Cross-Reference
- **Strategy** (Pattern 1) -- Factory creates and injects CreditCardProcessor, UPIProcessor, etc.
- **Repository** (Pattern 4) -- Factory creates InMemory repos, could swap to Postgres repos
- **Singleton** (Pattern 9) -- Factory calls `CurrencyService.getInstance()`
- **Chain of Responsibility** (Pattern 8) -- Factory builds the fraud check chain

---

## 4. Repository Pattern

### What

Decouple domain logic from data access. Define an interface for data operations; the implementation can be in-memory (for tests/interviews), PostgreSQL (for ledger/payments), or Redis (for idempotency cache). The domain layer never knows where data lives.

### ASCII Diagram -- 6 Repositories

```
  +----------------------------------------------------------------------+
  |                         REPOSITORY LAYER                              |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  <<interface>>              <<interface>>            <<interface>>    |
  |  PaymentRepository          LedgerRepository        MerchantRepo     |
  |  +------------------+       +------------------+    +---------------+|
  |  | save(Payment)    |       | recordEntry(     |    | findById(id)  ||
  |  | findById(id)     |       |   LedgerEntry)   |    | save(Merchant)||
  |  | findByMerchant   |       | findByPaymentId  |    | findAll()     ||
  |  | (merchantId)     |       |   (paymentId)    |    +-------+-------+|
  |  +--------+---------+       | getBalance(acct) |            |        |
  |           |                 +--------+---------+    +-------+-------+|
  |    +------+------+                   |              | InMemory      ||
  |    | InMemory    |           +-------+------+       | MerchantRepo  ||
  |    | PaymentRepo |           | InMemory     |       +---------------+|
  |    +-------------+           | LedgerRepo   |                        |
  |                              +--------------+                        |
  |                                                                      |
  |  <<interface>>              <<interface>>            <<interface>>    |
  |  AccountRepository          IdempotencyRepo         WebhookRepo      |
  |  +------------------+       +------------------+    +---------------+|
  |  | findById(id)     |       | exists(key)      |    | save(Webhook) ||
  |  | save(Account)    |       | store(key, result)|   | findPending() ||
  |  | debit(id, amt)   |       | get(key)         |    | markDelivered ||
  |  | credit(id, amt)  |       +--------+---------+    | (webhookId)   ||
  |  +--------+---------+                |              +-------+-------+|
  |           |                  +-------+-------+              |        |
  |    +------+------+          | InMemory      |      +-------+-------+|
  |    | InMemory    |          | IdempotencyRepo|     | InMemory      ||
  |    | AccountRepo |          | (ConcurrentMap)|     | WebhookRepo   ||
  |    +-------------+          +----------------+     +---------------+|
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Ugly Code -- Without Repository

```java
// ANTI-PATTERN: SQL scattered across service classes
public class PaymentService {
    private Connection dbConn;

    public Payment getPayment(String paymentId) {
        // Raw JDBC in the service layer
        String sql = "SELECT * FROM payments WHERE payment_id = ?";
        PreparedStatement stmt = dbConn.prepareStatement(sql);
        stmt.setString(1, paymentId);
        ResultSet rs = stmt.executeQuery();
        if (rs.next()) {
            Payment p = new Payment();
            p.setPaymentId(rs.getString("payment_id"));
            p.setAmount(rs.getBigDecimal("amount"));
            p.setStatus(PaymentStatus.valueOf(rs.getString("status")));
            // ... 10 more fields manually mapped
            return p;
        }
        return null; // no Optional, null pointer waiting to happen
    }

    public void updatePaymentStatus(String paymentId, String newStatus) {
        // Another raw SQL query
        String sql = "UPDATE payments SET status = ?, updated_at = NOW() " +
                     "WHERE payment_id = ?";
        PreparedStatement stmt = dbConn.prepareStatement(sql);
        stmt.setString(1, newStatus);
        stmt.setString(2, paymentId);
        stmt.executeUpdate();
        // No transaction management, no retry, no consistency check
    }
}
```

**Problems:**
- SQL strings scattered across service classes
- ResultSet-to-object mapping duplicated everywhere
- No transaction management -- partial updates possible
- Cannot unit-test without a real database
- Changing from PostgreSQL to DynamoDB requires rewriting every service

### Clean Code -- With Repository

```java
// Interface -- domain layer only knows this
public interface PaymentRepository {
    void save(Payment payment);
    Optional<Payment> findById(String paymentId);
    List<Payment> findByMerchantId(String merchantId);
    void updateStatus(String paymentId, PaymentStatus status);
}

// In-memory implementation -- for interviews and testing
public class InMemoryPaymentRepository implements PaymentRepository {
    private final Map<String, Payment> store = new ConcurrentHashMap<>();

    @Override
    public void save(Payment payment) {
        store.put(payment.getPaymentId(), payment);
    }

    @Override
    public Optional<Payment> findById(String paymentId) {
        return Optional.ofNullable(store.get(paymentId));
    }

    @Override
    public List<Payment> findByMerchantId(String merchantId) {
        return store.values().stream()
            .filter(p -> p.getMerchantId().equals(merchantId))
            .collect(Collectors.toList());
    }

    @Override
    public void updateStatus(String paymentId, PaymentStatus status) {
        findById(paymentId).ifPresent(p -> p.transitionTo(status));
    }
}

// Idempotency repository -- critical for exactly-once semantics
public interface IdempotencyRepository {
    boolean exists(String idempotencyKey);
    void store(String idempotencyKey, PaymentResult result);
    Optional<PaymentResult> get(String idempotencyKey);
}

public class InMemoryIdempotencyRepository implements IdempotencyRepository {
    private final ConcurrentHashMap<String, PaymentResult> store = new ConcurrentHashMap<>();

    @Override
    public boolean exists(String idempotencyKey) {
        return store.containsKey(idempotencyKey);
    }

    @Override
    public void store(String idempotencyKey, PaymentResult result) {
        store.putIfAbsent(idempotencyKey, result); // atomic -- no race conditions
    }

    @Override
    public Optional<PaymentResult> get(String idempotencyKey) {
        return Optional.ofNullable(store.get(idempotencyKey));
    }
}
```

### Interview One-Liner

> "Six repositories decouple domain from storage. PaymentRepository and LedgerRepository will be PostgreSQL in production (ACID for financial data). IdempotencyRepository will be Redis (fast key lookup with TTL). The service layer only knows interfaces -- swap storage by changing AppConfig."

### Cross-Reference
- **Factory** (Pattern 3) -- AppConfig creates InMemory repos, could swap to Postgres/Redis
- **Facade** (Pattern 5) -- PaymentService uses multiple repos via injection
- **Singleton** (Pattern 9) -- CurrencyService doesn't use a repo (rates are ephemeral)

---

## 5. Facade Pattern

### What

Provide a single, simplified interface to a complex subsystem. `PaymentService` is the Facade -- clients call `processPayment()` and it orchestrates 5 steps internally: idempotency check, fraud detection, payment processing, ledger recording, and webhook notification. No client knows about these subsystems.

### ASCII Diagram -- The 5-Step Orchestration

```
  +----------------------------------------------------------------------+
  |                     PaymentService (FACADE)                           |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Client calls ONE method: processPayment(payment)                    |
  |                                                                      |
  |  Internally orchestrates:                                            |
  |                                                                      |
  |  +-------------+    +-------------+    +----------------+            |
  |  | Step 1:     |    | Step 2:     |    | Step 3:        |            |
  |  | Idempotency |--->| Fraud       |--->| Payment        |            |
  |  | Check       |    | Detection   |    | Processing     |            |
  |  | (Idempotency|    | (FraudSvc)  |    | (PaymentProc)  |            |
  |  |  Repository)|    |             |    |                |            |
  |  +------+------+    +------+------+    +-------+--------+            |
  |         |                  |                   |                      |
  |   [If duplicate,     [If fraud,          [If declined,               |
  |    return cached]     DECLINE]            return error]               |
  |                                                |                      |
  |                                         +------v--------+            |
  |                                         | Step 4:       |            |
  |                                         | Ledger        |            |
  |                                         | Recording     |            |
  |                                         | (LedgerSvc)   |            |
  |                                         +-------+-------+            |
  |                                                 |                    |
  |                                         +-------v-------+            |
  |                                         | Step 5:       |            |
  |                                         | Webhook       |            |
  |                                         | Notification  |            |
  |                                         | (WebhookSvc)  |            |
  |                                         +---------------+            |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Ugly Code -- Without Facade

```java
// ANTI-PATTERN: Client must orchestrate all 5 steps manually
public class PaymentController {

    public PaymentResponse handlePayment(PaymentRequest request) {
        // Step 1: Client manually checks idempotency
        IdempotencyRepository idemRepo = new IdempotencyRepository();
        if (idemRepo.exists(request.getIdempotencyKey())) {
            return idemRepo.get(request.getIdempotencyKey());
        }

        // Step 2: Client manually runs fraud checks
        FraudService fraud = new FraudService();
        FraudResult fraudResult = fraud.runChecks(request.getPayment());
        if (fraudResult.isFlagged()) {
            return PaymentResponse.declined("Fraud");
        }

        // Step 3: Client manually picks processor and processes
        PaymentProcessor processor;
        if (request.getMethod().equals("CREDIT_CARD")) {
            processor = new CreditCardProcessor();
        } else if (request.getMethod().equals("UPI")) {
            processor = new UPIPaymentProcessor();
        }
        PaymentResult result = processor.process(request.getPayment());

        // Step 4: Client manually records in ledger
        LedgerService ledger = new LedgerService();
        ledger.recordDebit(request.getPayment());
        ledger.recordCredit(request.getPayment());

        // Step 5: Client manually sends webhook
        WebhookService webhooks = new WebhookService();
        webhooks.notifyMerchant(request.getMerchantId(), result);

        // Step 6: Client manually stores idempotency result
        idemRepo.store(request.getIdempotencyKey(), result);

        return result;
    }
}
// Every controller that processes payments must duplicate all 5 steps.
// Miss step 1? Double-charge. Miss step 4? Ledger inconsistent. Miss step 5? Merchant not notified.
```

**Problems:**
- Client knows about all 5 subsystems (tight coupling)
- Duplicate orchestration in every controller/endpoint
- Easy to forget a step (missing idempotency = double charge)
- Ordering bugs: what if ledger records before payment succeeds?
- Cannot change the workflow without modifying every client

### Clean Code -- With Facade

```java
public class PaymentService {
    // Facade -- hides all subsystems behind ONE method
    private final List<PaymentProcessor> processors;
    private final FraudService fraudService;
    private final LedgerService ledgerService;
    private final WebhookService webhookService;
    private final PaymentRepository paymentRepo;
    private final IdempotencyRepository idempotencyRepo;
    private final CurrencyService currencyService;

    public PaymentResult processPayment(Payment payment) {
        // (1) IDEMPOTENCY CHECK -- return cached result if duplicate
        Optional<PaymentResult> cached = idempotencyRepo.get(payment.getIdempotencyKey());
        if (cached.isPresent()) {
            return cached.get();  // exact same result as first attempt
        }

        // (2) FRAUD CHECK -- decline early if suspicious
        FraudResult fraudResult = fraudService.runChecks(payment);
        if (fraudResult.isFlagged()) {
            payment.transitionTo(PaymentStatus.DECLINED);
            paymentRepo.save(payment);
            return PaymentResult.declined(fraudResult.getReason());
        }

        // (3) PAYMENT PROCESSING -- find processor, validate, charge
        PaymentProcessor processor = findProcessor(payment.getMethod());
        if (!processor.validate(payment)) {
            payment.transitionTo(PaymentStatus.DECLINED);
            paymentRepo.save(payment);
            return PaymentResult.declined("Validation failed");
        }

        payment.transitionTo(PaymentStatus.PROCESSING);
        PaymentResult result = processor.process(payment);

        if (result.isSuccessful()) {
            payment.transitionTo(PaymentStatus.AUTHORIZED);
        } else {
            payment.transitionTo(PaymentStatus.DECLINED);
        }

        // (4) LEDGER RECORDING -- double-entry bookkeeping
        if (result.isSuccessful()) {
            ledgerService.recordPayment(payment);
        }

        // (5) PERSIST -- save payment and idempotency result atomically
        paymentRepo.save(payment);
        idempotencyRepo.store(payment.getIdempotencyKey(), result);

        // (6) WEBHOOK NOTIFICATION -- notify merchant asynchronously
        if (result.isSuccessful()) {
            webhookService.notifyPaymentEvent(payment, "payment.authorized");
        }

        return result;
    }

    private PaymentProcessor findProcessor(PaymentMethod method) {
        return processors.stream()
            .filter(p -> p.supportsMethod(method))
            .findFirst()
            .orElseThrow(() -> new UnsupportedPaymentMethodException(method));
    }
}
```

### Numbered Call Chain -- Full Payment Lifecycle

```
  Client       PaymentService    IdempotencyRepo   FraudService   CreditCardProc  LedgerService  WebhookService
    |               |                 |                |               |               |               |
    | (1) process   |                 |                |               |               |               |
    |  Payment(pay) |                 |                |               |               |               |
    |-------------->|                 |                |               |               |               |
    |               |                 |                |               |               |               |
    |               | (2) get(idem    |                |               |               |               |
    |               |  Key)           |                |               |               |               |
    |               |---------------->|                |               |               |               |
    |               |  empty()        |                |               |               |               |
    |               |<----------------|                |               |               |               |
    |               |                 |                |               |               |               |
    |               | (3) runChecks   |                |               |               |               |
    |               |  (payment)      |                |               |               |               |
    |               |--------------------------------->|               |               |               |
    |               |  FraudResult    |                |               |               |               |
    |               |  (CLEAN)        |                |               |               |               |
    |               |<---------------------------------|               |               |               |
    |               |                 |                |               |               |               |
    |               | (4) validate    |                |               |               |               |
    |               |  (payment)      |                |               |               |               |
    |               |------------------------------------------------->|               |               |
    |               |  true           |                |               |               |               |
    |               |<-------------------------------------------------|               |               |
    |               |                 |                |               |               |               |
    |               | (5) transition  |                |               |               |               |
    |               |  to PROCESSING  |                |               |               |               |
    |               |                 |                |               |               |               |
    |               | (6) process     |                |               |               |               |
    |               |  (payment)      |                |               |               |               |
    |               |------------------------------------------------->|               |               |
    |               |  PaymentResult  |                |               |               |               |
    |               |  (AUTHORIZED)   |                |               |               |               |
    |               |<-------------------------------------------------|               |               |
    |               |                 |                |               |               |               |
    |               | (7) transition  |                |               |               |               |
    |               |  to AUTHORIZED  |                |               |               |               |
    |               |                 |                |               |               |               |
    |               | (8) record      |                |               |               |               |
    |               |  Payment(pay)   |                |               |               |               |
    |               |----------------------------------------------------------------->|               |
    |               |  (debit buyer,  |                |               |               |               |
    |               |   credit merch) |                |               |               |               |
    |               |<-----------------------------------------------------------------|               |
    |               |                 |                |               |               |               |
    |               | (9) store(idem  |                |               |               |               |
    |               |  Key, result)   |                |               |               |               |
    |               |---------------->|                |               |               |               |
    |               |                 |                |               |               |               |
    |               | (10) notify     |                |               |               |               |
    |               |  PaymentEvent   |                |               |               |               |
    |               |-----------------|----------------|---------------|-------------->|               |
    |               |                 |                |               |               |               |
    |  PaymentResult|                 |                |               |               |               |
    |  (AUTHORIZED) |                 |                |               |               |               |
    |<--------------|                 |                |               |               |               |
```

### Interview One-Liner

> "PaymentService is a Facade -- the client calls processPayment() and internally we orchestrate: (1) idempotency check, (2) fraud detection, (3) payment processing, (4) double-entry ledger recording, (5) webhook notification. No client knows about these 5 subsystems. Adding a new step (e.g., compliance check) is one line in the Facade."

### Cross-Reference
- **Strategy** (Pattern 1) -- Facade delegates to PaymentProcessor and FraudCheckStrategy
- **Observer** (Pattern 6) -- Facade triggers WebhookService notification
- **State** (Pattern 7) -- Facade manages Payment state transitions
- **Repository** (Pattern 4) -- Facade uses PaymentRepository and IdempotencyRepository

---

## 6. Observer Pattern

### What

Define a one-to-many dependency so that when one object changes state, all its dependents are notified. `WebhookService` observes payment events and notifies merchants asynchronously. The payment processing pipeline does not need to know who is listening.

### ASCII Diagram

```
  +----------------------------------------------------------------------+
  |                     OBSERVER: Payment Events                          |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  PaymentService (Subject)                                            |
  |  - processes payment                                                 |
  |  - fires event: "payment.authorized"                                 |
  |                                                                      |
  |       | fires event                                                  |
  |       v                                                              |
  |  +---------------------------+                                       |
  |  | <<interface>>             |                                       |
  |  | PaymentEventListener      |                                       |
  |  +---------------------------+                                       |
  |  | + onPaymentEvent(         |                                       |
  |  |   Payment, String event)  |                                       |
  |  +-------------+-------------+                                       |
  |                |                                                     |
  |       +--------+--------+                                            |
  |       |                 |                                            |
  |  +----+-------+  +------+------+                                     |
  |  | Webhook    |  | Audit       |   (Future observers:                |
  |  | Service    |  | Logger      |    Analytics, Compliance, etc.)     |
  |  | (HTTP POST |  | (log every  |                                     |
  |  |  to merch) |  |  event)     |                                     |
  |  +------------+  +-------------+                                     |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Ugly Code -- Without Observer

```java
// ANTI-PATTERN: PaymentService directly calls every notification channel
public class PaymentService {

    public PaymentResult processPayment(Payment payment) {
        PaymentResult result = doProcess(payment);

        if (result.isSuccessful()) {
            // Hardcoded webhook call
            HttpClient client = HttpClient.newHttpClient();
            String webhookUrl = getMerchantWebhookUrl(payment.getMerchantId());
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .POST(HttpRequest.BodyPublishers.ofString(toJson(result)))
                .build();
            client.send(request, HttpResponse.BodyHandlers.ofString());

            // Hardcoded audit log
            auditLogger.log("Payment " + payment.getPaymentId() + " authorized");

            // Hardcoded analytics event
            analyticsClient.track("payment.authorized", payment.getAmount());

            // Adding compliance notification? SMS alert? -- more hardcoded calls
        }
        return result;
    }
}
// PaymentService now knows about HTTP, audit logging, and analytics.
// Adding a new listener = modify PaymentService = OCP violation.
```

**Problems:**
- PaymentService coupled to every notification channel
- HTTP call in the payment processing path -- if webhook times out, payment is delayed
- Adding new observers requires modifying PaymentService (OCP violation)
- Cannot test payment processing without mocking HTTP, audit, analytics

### Clean Code -- With Observer

```java
// Event listener interface
public interface PaymentEventListener {
    void onPaymentEvent(Payment payment, String eventType);
}

// Observer 1: Webhook delivery to merchants
public class WebhookService implements PaymentEventListener {
    private final WebhookRepository webhookRepo;
    private final MerchantRepository merchantRepo;

    @Override
    public void onPaymentEvent(Payment payment, String eventType) {
        // (1) Look up merchant's webhook URL
        Merchant merchant = merchantRepo.findById(payment.getMerchantId())
            .orElseThrow();
        String webhookUrl = merchant.getWebhookUrl();

        // (2) Create webhook delivery record
        WebhookDelivery delivery = new WebhookDelivery(
            UUID.randomUUID().toString(),
            payment.getPaymentId(),
            payment.getMerchantId(),
            eventType,
            webhookUrl,
            buildPayload(payment, eventType),
            WebhookStatus.PENDING
        );

        // (3) Save for reliable delivery (retry if fails)
        webhookRepo.save(delivery);

        // (4) Attempt delivery (async in production)
        attemptDelivery(delivery);
    }

    private void attemptDelivery(WebhookDelivery delivery) {
        // In production: Kafka/SQS queue -> consumer -> HTTP POST -> retry with backoff
        // In our simulation: direct call with retry counter
        delivery.incrementAttempt();
        // ... HTTP POST to merchant URL ...
    }
}

// Observer 2: Audit logging
public class AuditLogService implements PaymentEventListener {
    @Override
    public void onPaymentEvent(Payment payment, String eventType) {
        // (1) Log event for compliance
        System.out.println("[AUDIT] " + eventType + " | " +
            payment.getPaymentId() + " | " + payment.getAmount());
    }
}

// Subject: PaymentService manages listeners
public class PaymentService {
    private final List<PaymentEventListener> listeners = new ArrayList<>();

    public void addListener(PaymentEventListener listener) {
        listeners.add(listener);
    }

    private void fireEvent(Payment payment, String eventType) {
        for (PaymentEventListener listener : listeners) {
            listener.onPaymentEvent(payment, eventType);
        }
    }

    public PaymentResult processPayment(Payment payment) {
        // ... idempotency, fraud, processing, ledger ...
        PaymentResult result = doProcess(payment);

        if (result.isSuccessful()) {
            // (1) Fire event -- we don't know or care who listens
            fireEvent(payment, "payment.authorized");
        } else {
            fireEvent(payment, "payment.declined");
        }
        return result;
    }
}
```

### Interview One-Liner

> "WebhookService implements PaymentEventListener and observes payment events. PaymentService fires events like 'payment.authorized' without knowing who listens. Adding a new observer (analytics, compliance) is zero changes to PaymentService -- just register a new listener."

### Cross-Reference
- **Facade** (Pattern 5) -- PaymentService (Facade) fires events as part of orchestration
- **Repository** (Pattern 4) -- WebhookService uses WebhookRepository for reliable delivery
- **State** (Pattern 7) -- Events are fired on state transitions

---

## 7. State Pattern

### What

Allow an object to alter its behavior when its internal state changes. `Payment` has a state machine: INITIATED -> PROCESSING -> AUTHORIZED -> CAPTURED -> SETTLED (with DECLINED and REFUNDED branches). Each state knows which transitions are valid -- no massive if-else blocks.

### ASCII Diagram -- Payment State Machine

```
                                 +------------------+
                                 |                  |
                                 v                  |
  +----------+    +----------+   +----------+   +----------+   +----------+
  |          |    |          |   |          |   |          |   |          |
  | INITIATED|--->|PROCESSING|-->|AUTHORIZED|-->| CAPTURED |-->| SETTLED  |
  |          |    |          |   |          |   |          |   |          |
  +-----+----+    +-----+----+   +-----+----+   +----+-----+   +----------+
        |              |               |              |
        |              |               |              |
        v              v               v              v
  +----------+   +----------+   +----------+   +----------+
  | EXPIRED  |   | DECLINED |   | VOIDED   |   | REFUNDED |
  | (timeout)|   | (fraud/  |   | (cancel  |   | (partial/|
  |          |   |  bank)   |   |  before  |   |  full)   |
  +----------+   +----------+   |  capture)|   +----------+
                                +----------+

  Valid transitions (enforced by State pattern):
  +--------------+------------------------------------------+
  | From         | Allowed To                                |
  +--------------+------------------------------------------+
  | INITIATED    | PROCESSING, EXPIRED                       |
  | PROCESSING   | AUTHORIZED, DECLINED                      |
  | AUTHORIZED   | CAPTURED, VOIDED                          |
  | CAPTURED     | SETTLED, REFUNDED                         |
  | SETTLED      | REFUNDED                                  |
  | DECLINED     | (terminal)                                |
  | EXPIRED      | (terminal)                                |
  | VOIDED       | (terminal)                                |
  | REFUNDED     | (terminal)                                |
  +--------------+------------------------------------------+
```

### Ugly Code -- Without State Pattern

```java
// ANTI-PATTERN: if-else state checks scattered across the codebase
public class Payment {
    private String status; // magic string

    public void authorize() {
        if (status.equals("PROCESSING")) {
            status = "AUTHORIZED";
        } else if (status.equals("INITIATED")) {
            throw new IllegalStateException("Cannot authorize from INITIATED");
        } else if (status.equals("DECLINED")) {
            throw new IllegalStateException("Cannot authorize from DECLINED");
        } else if (status.equals("AUTHORIZED")) {
            throw new IllegalStateException("Already authorized");
        }
        // ... 5 more states to check
    }

    public void capture() {
        if (status.equals("AUTHORIZED")) {
            status = "CAPTURED";
        } else if (status.equals("PROCESSING")) {
            throw new IllegalStateException("Cannot capture from PROCESSING");
        } else if (status.equals("INITIATED")) {
            throw new IllegalStateException("Cannot capture from INITIATED");
        }
        // ... 5 more states to check
    }

    public void refund() {
        if (status.equals("CAPTURED") || status.equals("SETTLED")) {
            status = "REFUNDED";
        } else {
            throw new IllegalStateException("Cannot refund from " + status);
        }
    }
    // Every new state = modify EVERY method. Every new transition = more if-else.
}
```

**Problems:**
- Every method has N if-else branches for N states
- Adding a new state requires modifying every state-transition method
- Magic strings -- typo in "AUTHROIZED" compiles fine, fails at runtime
- Invalid transitions are only caught by else branches you remember to write
- No compile-time enforcement of valid transitions

### Clean Code -- With State Pattern

```java
public enum PaymentStatus {
    INITIATED {
        @Override
        public Set<PaymentStatus> allowedTransitions() {
            return Set.of(PROCESSING, EXPIRED);
        }
    },
    PROCESSING {
        @Override
        public Set<PaymentStatus> allowedTransitions() {
            return Set.of(AUTHORIZED, DECLINED);
        }
    },
    AUTHORIZED {
        @Override
        public Set<PaymentStatus> allowedTransitions() {
            return Set.of(CAPTURED, VOIDED);
        }
    },
    CAPTURED {
        @Override
        public Set<PaymentStatus> allowedTransitions() {
            return Set.of(SETTLED, REFUNDED);
        }
    },
    SETTLED {
        @Override
        public Set<PaymentStatus> allowedTransitions() {
            return Set.of(REFUNDED);
        }
    },
    DECLINED {
        @Override
        public Set<PaymentStatus> allowedTransitions() {
            return Collections.emptySet(); // terminal
        }
    },
    EXPIRED {
        @Override
        public Set<PaymentStatus> allowedTransitions() {
            return Collections.emptySet(); // terminal
        }
    },
    VOIDED {
        @Override
        public Set<PaymentStatus> allowedTransitions() {
            return Collections.emptySet(); // terminal
        }
    },
    REFUNDED {
        @Override
        public Set<PaymentStatus> allowedTransitions() {
            return Collections.emptySet(); // terminal
        }
    };

    public abstract Set<PaymentStatus> allowedTransitions();

    public boolean canTransitionTo(PaymentStatus target) {
        return allowedTransitions().contains(target);
    }
}

// Payment uses the state machine
public class Payment {
    private PaymentStatus status;

    public void transitionTo(PaymentStatus newStatus) {
        if (!status.canTransitionTo(newStatus)) {
            throw new InvalidStateTransitionException(
                "Cannot transition from " + status + " to " + newStatus +
                ". Allowed: " + status.allowedTransitions());
        }
        this.status = newStatus;
    }
}
```

### Numbered Call Chain -- Payment Lifecycle

```
  Client        PaymentService    Payment         PaymentStatus
    |                |               |                |
    | (1) process    |               |                |
    |  Payment(pay)  |               |                |
    |--------------->|               |                |
    |                |               |                |
    |                | (2) transition|                |
    |                |  To(PROCESSING)|               |
    |                |-------------->|                |
    |                |               | (3) can       |
    |                |               |  TransitionTo |
    |                |               |  (PROCESSING)?|
    |                |               |-------------->|
    |                |               |  true (from   |
    |                |               |  INITIATED)   |
    |                |               |<--------------|
    |                |               |               |
    |                |               | status =      |
    |                |               | PROCESSING    |
    |                |               |               |
    |                | (4) [gateway  |               |
    |                |  authorizes]  |               |
    |                |               |               |
    |                | (5) transition|               |
    |                |  To(AUTHORIZED)|              |
    |                |-------------->|               |
    |                |               | (6) can      |
    |                |               |  TransitionTo|
    |                |               |  (AUTHORIZED)?|
    |                |               |------------->|
    |                |               |  true (from  |
    |                |               |  PROCESSING) |
    |                |               |<-------------|
    |                |               |              |
    |                |               | status =     |
    |                |               | AUTHORIZED   |
    |                |               |              |
    |                | (7) [later:   |              |
    |                |  capture]     |              |
    |                | transition    |              |
    |                |  To(CAPTURED) |              |
    |                |-------------->|              |
    |                |               | status =    |
    |                |               | CAPTURED    |
    |                |               |             |
    |                | (8) [settle-  |             |
    |                |  ment cycle]  |             |
    |                | transition    |             |
    |                |  To(SETTLED)  |             |
    |                |-------------->|             |
    |                |               | status =   |
    |                |               | SETTLED    |
    |                |               |            |
```

### Interview One-Liner

> "PaymentStatus enum defines allowed transitions per state: INITIATED can go to PROCESSING or EXPIRED, AUTHORIZED can go to CAPTURED or VOIDED. The transitionTo() method checks canTransitionTo() -- invalid transitions throw immediately. No if-else chains, no magic strings, adding a new state is one enum constant."

### Cross-Reference
- **Facade** (Pattern 5) -- PaymentService calls transitionTo() during orchestration
- **Observer** (Pattern 6) -- Events fired on state transitions
- **Builder** (Pattern 2) -- Payment built with INITIATED status

---

## 8. Chain of Responsibility Pattern

### What

Pass a request along a chain of handlers. Each handler either processes the request or passes it to the next handler. `FraudService` chains multiple `FraudCheckStrategy` implementations -- if any check flags the payment, the chain short-circuits.

### ASCII Diagram

```
  +----------------------------------------------------------------------+
  |              CHAIN OF RESPONSIBILITY: Fraud Detection                  |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Payment enters the chain                                            |
  |       |                                                              |
  |       v                                                              |
  |  +------------------+     +------------------+     +---------------+ |
  |  | RuleBased        |     | MLBased          |     | (Future:      | |
  |  | FraudCheck       |---->| FraudCheck       |---->|  GeoFencing,  | |
  |  | Strategy         |     | Strategy         |     |  Biometric)   | |
  |  +------------------+     +------------------+     +---------------+ |
  |  | Check:           |     | Check:           |     |               | |
  |  | - Amount > 10K?  |     | - ML risk > 0.85?|    |               | |
  |  | - Velocity > 10? |     | - Feature vector  |    |               | |
  |  | - Blacklisted?   |     |   analysis        |    |               | |
  |  +------------------+     +------------------+     +---------------+ |
  |       |                        |                        |            |
  |       v                        v                        v            |
  |  [FLAGGED?]              [FLAGGED?]               [FLAGGED?]         |
  |  Yes: short-circuit      Yes: short-circuit       Yes: short-circuit |
  |  No: pass to next        No: pass to next         No: CLEAN          |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Ugly Code -- Without Chain of Responsibility

```java
// ANTI-PATTERN: All fraud checks in one massive method
public class FraudService {

    public FraudResult checkFraud(Payment payment) {
        // Rule 1: Amount threshold
        if (payment.getAmount().compareTo(BigDecimal.valueOf(10000)) > 0) {
            return FraudResult.flagged("Amount too high");
        }

        // Rule 2: Velocity check
        int recentTxns = countRecent(payment.getUserId(), 60);
        if (recentTxns > 10) {
            return FraudResult.flagged("Too many transactions");
        }

        // Rule 3: Blacklist
        if (isBlacklisted(payment.getUserId())) {
            return FraudResult.flagged("Blacklisted user");
        }

        // Rule 4: ML model
        double riskScore = mlModel.predict(payment);
        if (riskScore > 0.85) {
            return FraudResult.flagged("ML risk: " + riskScore);
        }

        // Rule 5: Geo-fencing (added later, requires modifying this method)
        // Rule 6: Device fingerprint (added later, more modification)
        // ... 200 lines later ...

        return FraudResult.clean();
    }
}
// Adding a new fraud check = modify this 200-line method.
// Cannot disable individual checks for specific merchants.
// Cannot test individual checks in isolation.
```

### Clean Code -- With Chain of Responsibility

```java
public class FraudService {
    private final List<FraudCheckStrategy> checks; // the chain

    public FraudService(List<FraudCheckStrategy> checks) {
        this.checks = checks; // injected by AppConfig
    }

    public FraudResult runChecks(Payment payment) {
        // Walk the chain -- first FLAGGED result short-circuits
        for (FraudCheckStrategy check : checks) {
            FraudResult result = check.checkFraud(payment);
            if (result.isFlagged()) {
                return result; // short-circuit: no need to run remaining checks
            }
        }
        return FraudResult.clean(); // all checks passed
    }
}

// AppConfig builds the chain
List<FraudCheckStrategy> chain = List.of(
    new RuleBasedFraudCheckStrategy(BigDecimal.valueOf(10000), 10),  // fast, cheap
    new MLBasedFraudCheckStrategy()                                   // slow, expensive
);
// Order matters: put cheap checks first to short-circuit before expensive ML inference.
// Adding geo-fencing? Just add to the list. No existing code changes.
```

### Numbered Call Chain -- Fraud Check Chain

```
  PaymentService    FraudService     RuleBasedCheck     MLBasedCheck
       |                |                 |                  |
       | (1) runChecks  |                 |                  |
       |  (payment)     |                 |                  |
       |--------------->|                 |                  |
       |                |                 |                  |
       |                | (2) checkFraud  |                  |
       |                |  (payment)      |                  |
       |                |---------------->|                  |
       |                |                 |                  |
       |                |                 | (3) amount < 10K?|
       |                |                 |  YES             |
       |                |                 | (4) velocity < 10?
       |                |                 |  YES             |
       |                |                 | (5) blacklisted? |
       |                |                 |  NO              |
       |                |                 |                  |
       |                | FraudResult     |                  |
       |                | (CLEAN)         |                  |
       |                |<----------------|                  |
       |                |                 |                  |
       |                | (6) checkFraud  |                  |
       |                |  (payment)      |                  |
       |                |---------------------------------->|
       |                |                 |                  |
       |                |                 |           (7) extract features
       |                |                 |           (8) model.predict()
       |                |                 |           (9) score = 0.15
       |                |                 |               (< 0.85)
       |                |                 |                  |
       |                | FraudResult     |                  |
       |                | (CLEAN)         |                  |
       |                |<---------------------------------|
       |                |                 |                  |
       | FraudResult    |                 |                  |
       | (CLEAN -- all  |                 |                  |
       |  checks passed)|                 |                  |
       |<---------------|                 |                  |
```

### Interview One-Liner

> "FraudService chains FraudCheckStrategy implementations: RuleBased runs first (cheap, fast), MLBased runs second (expensive, slow). If any check flags the payment, the chain short-circuits. Adding geo-fencing fraud detection? Add one class, add to the list. No existing code changes."

### Cross-Reference
- **Strategy** (Pattern 1) -- Each link in the chain IS a FraudCheckStrategy
- **Factory** (Pattern 3) -- AppConfig builds the chain and controls ordering
- **Facade** (Pattern 5) -- PaymentService calls fraudService.runChecks() as one step

---

## 9. Singleton Pattern

### What

Ensure a class has only one instance. `CurrencyService` maintains exchange rates -- having multiple instances means different parts of the system could use different rates for the same payment, leading to accounting discrepancies.

### ASCII Diagram

```
  +----------------------------------------------------------------------+
  |                   SINGLETON: CurrencyService                          |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  PaymentService ----+                                                |
  |                     |                                                |
  |  LedgerService -----+----> CurrencyService.getInstance()            |
  |                     |      +---------------------------+             |
  |  RefundService -----+      | SINGLE instance           |             |
  |                            | - exchangeRates: Map      |             |
  |                            | - lastUpdated: Instant     |             |
  |                            +---------------------------+             |
  |                            | + convert(amount,          |             |
  |                            |   fromCurrency,            |             |
  |                            |   toCurrency): BigDecimal  |             |
  |                            | + getRate(from, to): rate  |             |
  |                            | + refreshRates(): void     |             |
  |                            +---------------------------+             |
  |                                                                      |
  |  WHY SINGLETON?                                                      |
  |  - Multiple instances = different exchange rates at same moment       |
  |  - Payment converts at 83.5 INR/USD, ledger converts at 83.7        |
  |  - Result: $0.20 discrepancy per transaction * 1M txns = $200K loss  |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Ugly Code -- Without Singleton

```java
// ANTI-PATTERN: Multiple CurrencyService instances with different rates
public class PaymentService {
    private CurrencyService currencyService = new CurrencyService(); // instance 1

    public PaymentResult processPayment(Payment payment) {
        BigDecimal usdAmount = currencyService.convert(
            payment.getAmount(), payment.getCurrency(), Currency.USD);
        // converts at rate loaded at time T1
        // ...
    }
}

public class LedgerService {
    private CurrencyService currencyService = new CurrencyService(); // instance 2!

    public void recordPayment(Payment payment) {
        BigDecimal usdAmount = currencyService.convert(
            payment.getAmount(), payment.getCurrency(), Currency.USD);
        // converts at rate loaded at time T2 (different from T1!)
        // PaymentService says $99.50, LedgerService says $99.70
        // The books don't balance!
    }
}
```

**Problems:**
- Two instances might load exchange rates at different times
- Payment charged at one rate, ledger recorded at another rate
- Books don't balance -- auditors will flag this
- In production: regulatory compliance violation

### Clean Code -- With Singleton

```java
public class CurrencyService {
    // Thread-safe lazy initialization (Bill Pugh Singleton)
    private static class Holder {
        private static final CurrencyService INSTANCE = new CurrencyService();
    }

    private final Map<String, BigDecimal> exchangeRates = new ConcurrentHashMap<>();
    private Instant lastUpdated;

    private CurrencyService() {
        // (1) Load initial exchange rates
        refreshRates();
    }

    public static CurrencyService getInstance() {
        return Holder.INSTANCE;
    }

    public BigDecimal convert(BigDecimal amount, Currency from, Currency to) {
        if (from == to) return amount;
        // (1) Get rate from single source of truth
        BigDecimal rate = getRate(from, to);
        // (2) Convert with banker's rounding (HALF_EVEN)
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_EVEN);
    }

    public BigDecimal getRate(Currency from, Currency to) {
        String key = from.name() + "_" + to.name();
        BigDecimal rate = exchangeRates.get(key);
        if (rate == null) {
            throw new UnsupportedCurrencyException(from + " to " + to);
        }
        return rate;
    }

    public synchronized void refreshRates() {
        // In production: call Forex API (Open Exchange Rates, etc.)
        // In our simulation: hardcoded rates
        exchangeRates.put("USD_INR", new BigDecimal("83.50"));
        exchangeRates.put("INR_USD", new BigDecimal("0.01198"));
        exchangeRates.put("USD_EUR", new BigDecimal("0.92"));
        exchangeRates.put("EUR_USD", new BigDecimal("1.087"));
        exchangeRates.put("USD_GBP", new BigDecimal("0.79"));
        exchangeRates.put("GBP_USD", new BigDecimal("1.266"));
        lastUpdated = Instant.now();
    }
}

// Both services use the SAME instance -- rates are always consistent
PaymentService paymentService = new PaymentService(
    ..., CurrencyService.getInstance());
LedgerService ledgerService = new LedgerService(
    ..., CurrencyService.getInstance());
```

### Interview One-Liner

> "CurrencyService is a Singleton because exchange rates must be consistent across the entire system. If PaymentService and LedgerService used different CurrencyService instances, they could convert at different rates -- the books wouldn't balance. One instance = one source of truth."

### Cross-Reference
- **Factory** (Pattern 3) -- AppConfig calls `CurrencyService.getInstance()`
- **Facade** (Pattern 5) -- PaymentService uses CurrencyService for cross-currency payments
- **Caching** -- Exchange rates are cached in the Singleton with a 5-minute TTL (see CACHING_STRATEGY.md)

---

## 10. Template Method Pattern

### What

Define the skeleton of an algorithm in a base class, letting subclasses override specific steps without changing the algorithm's structure. Each `PaymentProcessor` follows a common flow: validate -> process -> respond. But HOW each step works differs: CreditCard validates Luhn + CVV, UPI validates VPA format, Wallet checks balance.

### ASCII Diagram

```
  +----------------------------------------------------------------------+
  |               TEMPLATE METHOD: Payment Processing Flow                |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  +-----------------------------+                                     |
  |  | AbstractPaymentProcessor    |                                     |
  |  +-----------------------------+                                     |
  |  | + executePayment(payment):  |  <-- template method (final)        |
  |  |     (1) preValidate()       |  <-- common: null checks, amount>0  |
  |  |     (2) validate()          |  <-- abstract: subclass implements  |
  |  |     (3) process()           |  <-- abstract: subclass implements  |
  |  |     (4) postProcess()       |  <-- common: logging, metrics       |
  |  |     (5) buildResponse()     |  <-- abstract: subclass implements  |
  |  +-------------+---------------+                                     |
  |                |                                                     |
  |       +--------+--------+--------+                                   |
  |       |                 |        |                                   |
  |  +----+--------+  +----+-----+  +----+--------+                     |
  |  | CreditCard  |  | UPI      |  | Wallet      |                     |
  |  | Processor   |  | Processor|  | Processor   |                     |
  |  +-------------+  +----------+  +-------------+                     |
  |  | validate():  |  | validate:| | validate():  |                     |
  |  |  Luhn check  |  |  VPA fmt |  |  balance chk|                     |
  |  | process():   |  | process():|  | process():  |                     |
  |  |  Stripe API  |  |  NPCI API|  |  deduct()   |                     |
  |  | buildResp(): |  | buildR():|  | buildResp():|                     |
  |  |  auth code   |  |  UPI ref |  |  new balance|                     |
  |  +-------------+  +----------+  +-------------+                     |
  |                                                                      |
  +----------------------------------------------------------------------+
```

### Ugly Code -- Without Template Method

```java
// ANTI-PATTERN: Duplicated skeleton in every processor
public class CreditCardProcessor {
    public PaymentResult execute(Payment payment) {
        // Common step: null check (duplicated in every processor)
        if (payment == null) throw new IllegalArgumentException("Payment is null");
        if (payment.getAmount().compareTo(BigDecimal.ZERO) <= 0)
            throw new IllegalArgumentException("Amount must be positive");

        // Specific: Luhn check
        if (!LuhnValidator.isValid(payment.getCardNumber())) {
            return PaymentResult.declined("Invalid card number");
        }

        // Specific: Stripe API
        String authCode = callStripe(payment);

        // Common step: logging (duplicated in every processor)
        log.info("Payment {} processed via CreditCard", payment.getPaymentId());
        metricsCollector.record("payment.creditcard", payment.getAmount());

        return PaymentResult.authorized(authCode, "Card charged");
    }
}

public class UPIPaymentProcessor {
    public PaymentResult execute(Payment payment) {
        // Common step: SAME null check (copy-pasted!)
        if (payment == null) throw new IllegalArgumentException("Payment is null");
        if (payment.getAmount().compareTo(BigDecimal.ZERO) <= 0)
            throw new IllegalArgumentException("Amount must be positive");

        // Specific: VPA validation
        if (!payment.getUpiId().contains("@")) {
            return PaymentResult.declined("Invalid VPA");
        }

        // Specific: NPCI API
        String upiRef = callNPCI(payment);

        // Common step: SAME logging (copy-pasted!)
        log.info("Payment {} processed via UPI", payment.getPaymentId());
        metricsCollector.record("payment.upi", payment.getAmount());

        return PaymentResult.authorized(upiRef, "UPI approved");
    }
}
// Pre-validation and post-processing are copy-pasted in EVERY processor.
// Bug in logging? Fix in 3+ places. Miss one? Inconsistent metrics.
```

**Problems:**
- Common steps (null check, logging, metrics) duplicated in every processor
- Bug in common logic requires fixing N processors
- Easy to forget common steps when adding a new processor
- No enforced structure -- each processor might do steps in different order

### Clean Code -- With Template Method

```java
public abstract class AbstractPaymentProcessor implements PaymentProcessor {

    // Template method -- defines the skeleton. FINAL = subclasses cannot change the order.
    public final PaymentResult executePayment(Payment payment) {
        // (1) Common pre-validation (null, amount, currency)
        preValidate(payment);

        // (2) Processor-specific validation (Luhn, VPA, balance)
        if (!validate(payment)) {
            return PaymentResult.declined("Validation failed for " + getProcessorName());
        }

        // (3) Processor-specific processing (Stripe, NPCI, wallet deduction)
        PaymentResult result = process(payment);

        // (4) Common post-processing (logging, metrics)
        postProcess(payment, result);

        // (5) Processor-specific response building
        return result;
    }

    // Common step -- same for all processors
    private void preValidate(Payment payment) {
        Objects.requireNonNull(payment, "Payment cannot be null");
        if (payment.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
    }

    // Common step -- same for all processors
    private void postProcess(Payment payment, PaymentResult result) {
        System.out.println("[" + getProcessorName() + "] Payment " +
            payment.getPaymentId() + " -> " + result.getStatus());
        // In production: metrics.record(getProcessorName(), payment.getAmount());
    }

    // Abstract steps -- each subclass implements differently
    protected abstract boolean validate(Payment payment);
    protected abstract PaymentResult process(Payment payment);
    protected abstract String getProcessorName();
}

// CreditCard -- only implements the varying steps
public class CreditCardProcessor extends AbstractPaymentProcessor {
    @Override
    protected boolean validate(Payment payment) {
        return payment.getCardNumber() != null
            && LuhnValidator.isValid(payment.getCardNumber());
    }

    @Override
    protected PaymentResult process(Payment payment) {
        String authCode = "AUTH-" + UUID.randomUUID();
        return PaymentResult.authorized(authCode, "Credit card authorized");
    }

    @Override
    protected String getProcessorName() { return "CreditCard"; }

    @Override
    public boolean supportsMethod(PaymentMethod method) {
        return method == PaymentMethod.CREDIT_CARD;
    }
}

// UPI -- only implements the varying steps
public class UPIPaymentProcessor extends AbstractPaymentProcessor {
    @Override
    protected boolean validate(Payment payment) {
        return payment.getUpiId() != null && payment.getUpiId().contains("@");
    }

    @Override
    protected PaymentResult process(Payment payment) {
        String upiRef = "UPI-" + UUID.randomUUID();
        return PaymentResult.authorized(upiRef, "UPI payment approved");
    }

    @Override
    protected String getProcessorName() { return "UPI"; }

    @Override
    public boolean supportsMethod(PaymentMethod method) {
        return method == PaymentMethod.UPI;
    }
}

// Wallet -- only implements the varying steps
public class WalletPaymentProcessor extends AbstractPaymentProcessor {
    @Override
    protected boolean validate(Payment payment) {
        return payment.getWalletId() != null;
    }

    @Override
    protected PaymentResult process(Payment payment) {
        String walletRef = "WLT-" + UUID.randomUUID();
        return PaymentResult.authorized(walletRef, "Wallet debited");
    }

    @Override
    protected String getProcessorName() { return "Wallet"; }

    @Override
    public boolean supportsMethod(PaymentMethod method) {
        return method == PaymentMethod.WALLET;
    }
}
```

### Numbered Call Chain -- Template Method Execution

```
  PaymentService    AbstractProcessor    CreditCardProcessor
       |                  |                      |
       | (1) execute      |                      |
       |  Payment(pay)    |                      |
       |----------------->|                      |
       |                  |                      |
       |                  | (2) preValidate()    |
       |                  |  [common: null,      |
       |                  |   amount > 0]        |
       |                  |                      |
       |                  | (3) validate()       |
       |                  |  [ABSTRACT -- calls  |
       |                  |   subclass]          |
       |                  |--------------------->|
       |                  |                      |
       |                  |                      | (4) Luhn check
       |                  |                      |  card number
       |                  |  true                |
       |                  |<---------------------|
       |                  |                      |
       |                  | (5) process()        |
       |                  |  [ABSTRACT -- calls  |
       |                  |   subclass]          |
       |                  |--------------------->|
       |                  |                      |
       |                  |                      | (6) call Stripe
       |                  |                      |  get auth code
       |                  |  PaymentResult       |
       |                  |  (AUTH-uuid)         |
       |                  |<---------------------|
       |                  |                      |
       |                  | (7) postProcess()    |
       |                  |  [common: log,       |
       |                  |   metrics]           |
       |                  |                      |
       |  PaymentResult   |                      |
       |  (AUTHORIZED)    |                      |
       |<-----------------|                      |
```

### Interview One-Liner

> "AbstractPaymentProcessor defines the skeleton: preValidate (common null/amount checks) -> validate (abstract, subclass-specific) -> process (abstract, subclass-specific) -> postProcess (common logging/metrics). The template method is final -- subclasses implement the varying steps, the skeleton order is locked. Adding a new processor? Just implement 3 methods."

### Cross-Reference
- **Strategy** (Pattern 1) -- Each processor IS a Strategy; Template Method defines the internal algorithm skeleton
- **Factory** (Pattern 3) -- AppConfig creates concrete processors

---

## Special Focus: Idempotency Pattern

### Why This Matters in Payments

Idempotency is THE most important pattern in a payment system. Without it, network retries cause double-charges. The client sends the same request twice (timeout + retry), and the system charges the customer twice. This section shows the exact check-process-store flow with race condition handling.

### The Problem: Double-Charge Without Idempotency

```
  CLIENT              PAYMENT SERVICE              BANK
    |                      |                         |
    | (1) Pay $100         |                         |
    |  idemKey: "abc-123"  |                         |
    |--------------------->|                         |
    |                      | (2) Charge $100         |
    |                      |------------------------>|
    |                      |                         |
    |  [TIMEOUT -- client  |     $100 charged (OK)   |
    |   never gets resp.]  |<------------------------|
    |                      |                         |
    | (3) RETRY: Pay $100  |                         |
    |  idemKey: "abc-123"  |                         |
    |--------------------->|                         |
    |                      |                         |
    |     WITHOUT IDEMPOTENCY:                       |
    |                      | (4) Charge $100 AGAIN!  |
    |                      |------------------------>|
    |                      |     $100 charged AGAIN   |
    |                      |<------------------------|
    |  PaymentResult       |                         |
    |  ($200 charged!)     |                         |
    |<---------------------|                         |
    |                                                |
    |  Customer charged $200 for a $100 purchase!    |
```

### The Solution: Check-Process-Store Flow

```
  CLIENT              PAYMENT SERVICE         IDEMPOTENCY REPO        BANK
    |                      |                       |                    |
    | (1) Pay $100         |                       |                    |
    |  idemKey: "abc-123"  |                       |                    |
    |--------------------->|                       |                    |
    |                      |                       |                    |
    |                      | (2) CHECK: exists     |                    |
    |                      |  ("abc-123")?         |                    |
    |                      |---------------------->|                    |
    |                      |  false (first time)   |                    |
    |                      |<----------------------|                    |
    |                      |                       |                    |
    |                      | (3) LOCK: store       |                    |
    |                      |  ("abc-123", PENDING) |                    |
    |                      |---------------------->|                    |
    |                      |  (atomic SET NX)      |                    |
    |                      |                       |                    |
    |                      | (4) PROCESS: charge   |                    |
    |                      |--------------------------------------------->|
    |                      |                       |     $100 charged    |
    |                      |<---------------------------------------------|
    |                      |                       |                    |
    |                      | (5) STORE: update     |                    |
    |                      |  ("abc-123", SUCCESS, |                    |
    |                      |   AUTH-uuid)          |                    |
    |                      |---------------------->|                    |
    |                      |                       |                    |
    |  PaymentResult       |                       |                    |
    |  (SUCCESS, AUTH-uuid)|                       |                    |
    |<---------------------|                       |                    |
    |                      |                       |                    |
    |  [TIMEOUT -- retry]  |                       |                    |
    |                      |                       |                    |
    | (6) RETRY: Pay $100  |                       |                    |
    |  idemKey: "abc-123"  |                       |                    |
    |--------------------->|                       |                    |
    |                      |                       |                    |
    |                      | (7) CHECK: exists     |                    |
    |                      |  ("abc-123")?         |                    |
    |                      |---------------------->|                    |
    |                      |  true (already done!) |                    |
    |                      |<----------------------|                    |
    |                      |                       |                    |
    |                      | (8) GET: retrieve     |                    |
    |                      |  cached result        |                    |
    |                      |---------------------->|                    |
    |                      |  (SUCCESS, AUTH-uuid) |                    |
    |                      |<----------------------|                    |
    |                      |                       |                    |
    |  PaymentResult       |                       |   [BANK NOT CALLED |
    |  (SUCCESS, AUTH-uuid)|                       |    -- same result  |
    |<---------------------|                       |    returned]       |
```

### Race Condition: Two Concurrent Requests with Same Key

```
  REQUEST A             PAYMENT SERVICE         REDIS (Idempotency)      REQUEST B
    |                        |                       |                       |
    | (1) Pay $100           |                       |          (1) Pay $100 |
    |  idemKey: "abc-123"    |                       |   idemKey: "abc-123"  |
    |----------------------->|                       |<----------------------|
    |                        |                       |                       |
    |                        | (2A) SET NX           |                       |
    |                        |  "abc-123" PENDING    |                       |
    |                        |---------------------->|                       |
    |                        |  OK (acquired lock)   |                       |
    |                        |<----------------------|                       |
    |                        |                       |                       |
    |                        |                       | (2B) SET NX            |
    |                        |                       |  "abc-123" PENDING     |
    |                        |                       |  FAILS (key exists!)   |
    |                        |                       |----------------------->|
    |                        |                       |  NIL (lock not acquired|
    |                        |                       |<-----------------------|
    |                        |                       |                       |
    |                        | (3A) process payment  |                       |
    |                        |  (charge bank)        |  (3B) WAIT or         |
    |                        |                       |  return PENDING        |
    |                        |                       |                       |
    |                        | (4A) store result     |                       |
    |                        |  ("abc-123", SUCCESS) |                       |
    |                        |---------------------->|                       |
    |                        |                       |                       |
    |  PaymentResult         |                       |                       |
    |  (SUCCESS)             |                       |                       |
    |<-----------------------|                       |                       |
    |                        |                       |                       |
    |                        |                       | (4B) GET "abc-123"    |
    |                        |                       |  -> (SUCCESS)         |
    |                        |                       |--------------------->|
    |                        |                       |  PaymentResult       |
    |                        |                       |  (SUCCESS, same)     |
```

### Implementation: Atomic Check-and-Set

```java
public class PaymentService {

    public PaymentResult processPayment(Payment payment) {
        String idempotencyKey = payment.getIdempotencyKey();

        // STEP 1: Atomic check-and-set (Redis SET NX or ConcurrentHashMap.putIfAbsent)
        boolean acquired = idempotencyRepo.tryAcquire(idempotencyKey);

        if (!acquired) {
            // Another request is processing this key, or it's already done
            // Wait briefly, then return cached result
            return waitForResult(idempotencyKey);
        }

        try {
            // STEP 2: We hold the lock -- process the payment
            PaymentResult result = doProcess(payment);

            // STEP 3: Store the result (replaces PENDING with actual result)
            idempotencyRepo.store(idempotencyKey, result);

            return result;
        } catch (Exception e) {
            // STEP 4: On failure, remove the key so client can retry
            idempotencyRepo.remove(idempotencyKey);
            throw e;
        }
    }

    private PaymentResult waitForResult(String idempotencyKey) {
        // Poll for result (in production: use Redis pub/sub or short poll)
        for (int i = 0; i < 10; i++) {
            Optional<PaymentResult> result = idempotencyRepo.get(idempotencyKey);
            if (result.isPresent() && !result.get().isPending()) {
                return result.get();
            }
            Thread.sleep(100); // brief wait
        }
        throw new PaymentProcessingException("Timeout waiting for idempotent result");
    }
}

// Redis implementation (production)
public class RedisIdempotencyRepository implements IdempotencyRepository {
    @Override
    public boolean tryAcquire(String key) {
        // SET key "PENDING" NX EX 86400
        // NX = only set if not exists (atomic!)
        // EX 86400 = TTL of 24 hours
        String result = redis.set(key, "PENDING", SetParams.setParams().nx().ex(86400));
        return "OK".equals(result);
    }
}
```

### Interview One-Liner (Idempotency)

> "Every payment request includes an idempotency key. Before processing, we do an atomic SET NX in Redis -- if the key exists, we return the cached result. If not, we acquire the lock, process the payment, store the result. Two concurrent requests with the same key? SET NX guarantees only one wins. The loser waits for the result. No double-charges, ever."

---

## Pattern Interaction Map

```
  +----------------------------------------------------------------------+
  |                    HOW ALL 10 PATTERNS INTERACT                       |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  AppConfig (FACTORY)                                                 |
  |    |                                                                 |
  |    | creates & wires                                                 |
  |    v                                                                 |
  |  PaymentService (FACADE)                                             |
  |    |                                                                 |
  |    |-- (1) IdempotencyRepo (REPOSITORY) -- check-process-store       |
  |    |                                                                 |
  |    |-- (2) FraudService (CHAIN OF RESPONSIBILITY)                    |
  |    |         |-- RuleBasedFraudCheck (STRATEGY)                      |
  |    |         |-- MLBasedFraudCheck (STRATEGY)                        |
  |    |                                                                 |
  |    |-- (3) PaymentProcessor (STRATEGY + TEMPLATE METHOD)             |
  |    |         |-- CreditCardProcessor                                 |
  |    |         |-- UPIPaymentProcessor                                 |
  |    |         |-- WalletPaymentProcessor                              |
  |    |                                                                 |
  |    |-- (4) Payment (STATE machine + BUILDER)                         |
  |    |         INITIATED -> PROCESSING -> AUTHORIZED -> CAPTURED       |
  |    |                                                                 |
  |    |-- (5) LedgerService + LedgerRepo (REPOSITORY)                   |
  |    |                                                                 |
  |    |-- (6) CurrencyService (SINGLETON)                               |
  |    |                                                                 |
  |    |-- (7) WebhookService (OBSERVER)                                 |
  |    |         |-- WebhookRepo (REPOSITORY)                            |
  |    |         |-- MerchantRepo (REPOSITORY)                           |
  |                                                                      |
  +----------------------------------------------------------------------+
```

---

## Quick Reference: All Patterns at a Glance

| # | Pattern | GoF | Problem It Solves | Key Benefit |
|---|---------|-----|-------------------|-------------|
| 1 | Strategy (x2) | Behavioral | Hardcoded payment gateways and fraud algorithms | Swap algorithms without modifying services |
| 2 | Builder | Creational | 10+ field constructors for Payment/Refund | Readable, validated, immutable object creation |
| 3 | Factory | Creational | Scattered `new ConcreteClass()` everywhere | One place to change wiring (InMemory vs Postgres) |
| 4 | Repository | Structural | SQL/storage coupled to business logic | Swap storage without touching domain code |
| 5 | Facade | Structural | Clients must orchestrate 5 subsystems | One method hides entire payment workflow |
| 6 | Observer | Behavioral | Notification coupled to payment processing | Add listeners without modifying PaymentService |
| 7 | State | Behavioral | if-else chains for payment status transitions | Enum-based state machine with enforced transitions |
| 8 | Chain of Resp. | Behavioral | Monolithic fraud check method | Add/remove/reorder fraud checks without code changes |
| 9 | Singleton | Creational | Multiple exchange rate sources = books don't balance | Single source of truth for currency conversion |
| 10 | Template Method | Behavioral | Copy-pasted pre/post processing in each processor | Common skeleton locked; only varying steps overridden |
| -- | Idempotency | Enterprise | Network retries cause double-charges | Atomic check-and-set ensures exactly-once processing |
