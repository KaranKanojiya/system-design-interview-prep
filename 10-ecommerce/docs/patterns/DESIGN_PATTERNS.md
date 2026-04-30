# Design Patterns in the E-Commerce System (Amazon)

> Interview-ready reference for a Senior Java developer.
> An e-commerce system is the most pattern-rich domain in system design -- it uses 10 patterns across all three GoF categories plus enterprise patterns.
> For each pattern: ugly anti-pattern code, clean pattern-based code, numbered call chain, and interview one-liner.

---

## Table of Contents

| # | Pattern | GoF Category | Key Class(es) | One-Liner |
|---|---------|-------------|---------------|-----------|
| 1 | Strategy (x3) | Behavioral | `PricingStrategy` (Standard, Discount), `PaymentStrategy` (CreditCard, Wallet, COD), `ShippingStrategy` (Standard, Express) | Swap pricing/payment/shipping algorithms without changing service code |
| 2 | Builder | Creational | `Order.Builder`, `Cart.Builder`, `Payment.Builder`, `SagaResult.Builder` | Many optional fields (discounts, notes, shipping address) -- Builder prevents telescoping constructors |
| 3 | Factory | Creational | `AppConfig` creates all objects and wires dependencies | Centralized object creation, only class that says `new ConcreteClass()` |
| 4 | Repository | Structural (DDD) | 5 repositories: `ProductRepository`, `OrderRepository`, `InventoryRepository`, `CartRepository`, `PaymentRepository` | Decouple domain from storage (swap in-memory to PostgreSQL/Redis/DynamoDB) |
| 5 | Facade | Structural | `OrderService` orchestrates cart -> saga -> payment -> shipping -> notification | One entry point hides the entire checkout workflow |
| 6 | Observer | Behavioral | `NotificationService` observes order state changes | Decouple notification from order processing -- adding SMS doesn't touch OrderService |
| 7 | State | Behavioral | `Order` state machine: CREATED -> INVENTORY_RESERVED -> PAYMENT_CONFIRMED -> SHIPPED -> DELIVERED | Eliminates if-else state checks; each state knows its own transitions |
| 8 | Decorator | Structural | `DiscountPricingStrategy` wraps `StandardPricingStrategy` | Add discount logic transparently without modifying base pricing |
| 9 | Saga | Enterprise | `OrderSagaOrchestrator` -- distributed transaction with compensation | Distributed consistency without 2PC; each step has execute + compensate |
| 10 | Command | Behavioral | Each `SagaStep` is a command object (execute + compensate) | Encapsulate saga steps as objects for undo/redo and logging |

---

## 1. Strategy Pattern (x3)

### What

Define a family of algorithms, encapsulate each behind a common interface, and make them interchangeable at runtime. This project uses Strategy THREE times -- pricing, payment, and shipping -- each representing a different axis of variation in an e-commerce checkout.

### ASCII Diagram -- All Three Strategy Hierarchies

```
  PRICING STRATEGY                  PAYMENT STRATEGY                 SHIPPING STRATEGY
  ================                  ================                 =================

  +-------------------------+       +-------------------------+      +-------------------------+
  | <<interface>>           |       | <<interface>>           |      | <<interface>>           |
  | PricingStrategy         |       | PaymentStrategy         |      | ShippingStrategy        |
  +-------------------------+       +-------------------------+      +-------------------------+
  | + calculatePrice(cart): |       | + pay(amount, order):   |      | + calculateCost(order): |
  |   BigDecimal            |       |   PaymentResult         |      |   BigDecimal            |
  +----------+--------------+       +----------+--------------+      | + estimateDays(order):  |
             |                                 |                     |   int                   |
       +-----+------+               +---------+----------+          +----------+--------------+
       |            |                |         |          |                     |
+------+------+ +---+----------+  +-+------+ +-+------+ ++-----+    +--------+------+
| Standard    | | Discount     |  | Credit | | Wallet | | COD  |    | Standard      |
| Pricing     | | Pricing      |  | Card   | | Pay    | | Pay  |    | Shipping      |
| Strategy    | | Strategy     |  | Pay    | | ment   | | ment |    | (5-7 days)    |
| (full price)| | (wraps std!) |  +--------+ +--------+ +------+    +-------+-------+
+-------------+ +--------------+                                            |
                                                                    +-------+-------+
                                                                    | Express       |
                                                                    | Shipping      |
                                                                    | (1-2 days)    |
                                                                    +---------------+
```

### Ugly Code -- Without Strategy

```java
// ANTI-PATTERN: if-else chain in OrderService
// Every new pricing/payment/shipping option = modify this god class = OCP violation
public class OrderService {

    private String pricingMode = "STANDARD";   // magic string
    private String paymentMethod = "CREDIT";    // another magic string
    private String shippingType = "STANDARD";   // yet another magic string

    public OrderResult checkout(Cart cart, String userId) {
        // Step 1: Calculate price
        BigDecimal total;
        if (pricingMode.equals("STANDARD")) {
            total = cart.getItems().stream()
                .map(item -> item.getProduct().getPrice()
                    .multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        } else if (pricingMode.equals("DISCOUNT")) {
            total = BigDecimal.ZERO;
            for (CartItem item : cart.getItems()) {
                BigDecimal price = item.getProduct().getPrice();
                if (item.getQuantity() >= 3) {
                    price = price.multiply(BigDecimal.valueOf(0.9)); // 10% off bulk
                }
                total = total.add(price.multiply(
                    BigDecimal.valueOf(item.getQuantity())));
            }
        } else if (pricingMode.equals("PRIME_MEMBER")) {
            // 50 lines of prime-specific pricing inline...
            total = calculatePrimePricing(cart, userId);
        }
        // Adding flash sale pricing? Holiday pricing? -- more else-if...

        // Step 2: Process payment
        if (paymentMethod.equals("CREDIT")) {
            // Call Stripe API inline with 40 lines of error handling
            chargeStripe(total, userId);
        } else if (paymentMethod.equals("WALLET")) {
            // Call internal wallet service
            deductWallet(total, userId);
        } else if (paymentMethod.equals("COD")) {
            // Just record the order, no charge now
            recordCODOrder(total, userId);
        }
        // Adding PayPal? Apple Pay? Crypto? -- more else-if...

        // Step 3: Calculate shipping
        BigDecimal shippingCost;
        int estimatedDays;
        if (shippingType.equals("STANDARD")) {
            shippingCost = BigDecimal.valueOf(5.99);
            estimatedDays = 7;
        } else if (shippingType.equals("EXPRESS")) {
            shippingCost = BigDecimal.valueOf(14.99);
            estimatedDays = 2;
        } else if (shippingType.equals("SAME_DAY")) {
            // 30 lines of zone-based same-day logic...
            shippingCost = calculateSameDayShipping(cart);
            estimatedDays = 0;
        }
        // Adding drone delivery? Locker pickup? -- more else-if...

        return new OrderResult(total, shippingCost, estimatedDays);
    }
}
```

**Problems with this approach:**
- `OrderService` knows about every pricing formula, payment gateway, and shipping algorithm (SRP violation)
- Adding a new pricing tier / payment method / shipping option requires modifying `OrderService` (OCP violation)
- Cannot unit-test pricing, payment, or shipping in isolation
- Magic strings for mode selection -- no compile-time safety
- Pricing, payment, and shipping logic are tangled together in one 200-line method

### Clean Code -- With Strategy

```java
// --- Strategy 1: Pricing ---
public interface PricingStrategy {
    BigDecimal calculatePrice(Cart cart);
}

public class StandardPricingStrategy implements PricingStrategy {
    @Override
    public BigDecimal calculatePrice(Cart cart) {
        // (1) Sum up price * quantity for each item -- no discounts
        return cart.getItems().stream()
            .map(item -> item.getProduct().getPrice()
                .multiply(BigDecimal.valueOf(item.getQuantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}

public class DiscountPricingStrategy implements PricingStrategy {
    private final PricingStrategy basePricing;         // wraps another strategy (Decorator!)
    private final BigDecimal discountPercent;

    public DiscountPricingStrategy(PricingStrategy basePricing,
                                   BigDecimal discountPercent) {
        this.basePricing = basePricing;
        this.discountPercent = discountPercent;
    }

    @Override
    public BigDecimal calculatePrice(Cart cart) {
        // (1) Delegate to base pricing strategy
        BigDecimal basePrice = basePricing.calculatePrice(cart);
        // (2) Apply discount percentage
        BigDecimal discount = basePrice.multiply(discountPercent)
            .divide(BigDecimal.valueOf(100), RoundingMode.HALF_UP);
        return basePrice.subtract(discount);
    }
}

// --- Strategy 2: Payment ---
public interface PaymentStrategy {
    PaymentResult pay(BigDecimal amount, Order order);
}

public class CreditCardPaymentStrategy implements PaymentStrategy {
    @Override
    public PaymentResult pay(BigDecimal amount, Order order) {
        // (1) Validate card details
        // (2) Call payment gateway (Stripe in production)
        // (3) Return success/failure with transaction ID
        String transactionId = "CC-" + UUID.randomUUID();
        return new PaymentResult(true, transactionId, "Credit card charged");
    }
}

public class WalletPaymentStrategy implements PaymentStrategy {
    @Override
    public PaymentResult pay(BigDecimal amount, Order order) {
        // (1) Check wallet balance
        // (2) Deduct from wallet (atomic operation)
        // (3) Return success/failure
        String transactionId = "WLT-" + UUID.randomUUID();
        return new PaymentResult(true, transactionId, "Wallet debited");
    }
}

public class CODPaymentStrategy implements PaymentStrategy {
    @Override
    public PaymentResult pay(BigDecimal amount, Order order) {
        // (1) No charge now -- just record the intent
        String transactionId = "COD-" + UUID.randomUUID();
        return new PaymentResult(true, transactionId, "Cash on delivery recorded");
    }
}

// --- Strategy 3: Shipping ---
public interface ShippingStrategy {
    BigDecimal calculateCost(Order order);
    int estimateDeliveryDays(Order order);
}

public class StandardShippingStrategy implements ShippingStrategy {
    @Override
    public BigDecimal calculateCost(Order order) {
        return BigDecimal.valueOf(5.99);
    }

    @Override
    public int estimateDeliveryDays(Order order) {
        return 7;  // 5-7 business days
    }
}

public class ExpressShippingStrategy implements ShippingStrategy {
    @Override
    public BigDecimal calculateCost(Order order) {
        return BigDecimal.valueOf(14.99);
    }

    @Override
    public int estimateDeliveryDays(Order order) {
        return 2;  // 1-2 business days
    }
}
```

### OrderService -- Uses Strategies (Doesn't Know the Algorithm)

```java
public class OrderService {
    private final PricingStrategy pricingStrategy;     // injected
    private final PaymentStrategy paymentStrategy;     // injected
    private final ShippingStrategy shippingStrategy;   // injected

    public OrderResult checkout(Cart cart, Order order) {
        // (1) Calculate price -- we don't know WHICH pricing
        BigDecimal total = pricingStrategy.calculatePrice(cart);

        // (2) Process payment -- we don't know WHICH payment method
        PaymentResult payResult = paymentStrategy.pay(total, order);

        // (3) Calculate shipping -- we don't know WHICH shipping
        BigDecimal shippingCost = shippingStrategy.calculateCost(order);
        int deliveryDays = shippingStrategy.estimateDeliveryDays(order);

        return new OrderResult(total, shippingCost, deliveryDays, payResult);
    }
}
```

### Numbered Call Chain -- checkout() with Discount + CreditCard + Express

```
  Client          OrderService      DiscountPricing    StandardPricing    CreditCardPay    ExpressShipping
    |                  |                  |                  |                  |                  |
    | (1) checkout     |                  |                  |                  |                  |
    |  (cart, order)   |                  |                  |                  |                  |
    |----------------->|                  |                  |                  |                  |
    |                  |                  |                  |                  |                  |
    |                  | (2) calculate    |                  |                  |                  |
    |                  |  Price(cart)     |                  |                  |                  |
    |                  |----------------->|                  |                  |                  |
    |                  |                  |                  |                  |                  |
    |                  |                  | (3) delegate to  |                  |                  |
    |                  |                  |  basePricing     |                  |                  |
    |                  |                  |  .calculatePrice |                  |                  |
    |                  |                  |----------------->|                  |                  |
    |                  |                  |   $150.00        |                  |                  |
    |                  |                  |<-----------------|                  |                  |
    |                  |                  |                  |                  |                  |
    |                  |                  | (4) apply 10%    |                  |                  |
    |                  |                  |  discount        |                  |                  |
    |                  |                  |  $150 - $15      |                  |                  |
    |                  |   $135.00        |  = $135.00       |                  |                  |
    |                  |<-----------------|                  |                  |                  |
    |                  |                  |                  |                  |                  |
    |                  | (5) pay($135,    |                  |                  |                  |
    |                  |  order)          |                  |                  |                  |
    |                  |----------------------------------------------->|                  |
    |                  |                  |                  |                  |                  |
    |                  |                  |                  |  (6) validate    |                  |
    |                  |                  |                  |  card + charge   |                  |
    |                  |  PaymentResult   |                  |  via gateway     |                  |
    |                  |  (CC-uuid, OK)   |                  |                  |                  |
    |                  |<-----------------------------------------------|                  |
    |                  |                  |                  |                  |                  |
    |                  | (7) calculateCost|                  |                  |                  |
    |                  |  (order)         |                  |                  |                  |
    |                  |------------------------------------------------------------->|
    |                  |   $14.99         |                  |                  |                  |
    |                  |<-------------------------------------------------------------|
    |                  |                  |                  |                  |                  |
    |                  | (8) estimateDays |                  |                  |                  |
    |                  |  (order)         |                  |                  |                  |
    |                  |------------------------------------------------------------->|
    |                  |   2 days         |                  |                  |                  |
    |                  |<-------------------------------------------------------------|
    |                  |                  |                  |                  |                  |
    |  OrderResult     |                  |                  |                  |                  |
    |  ($135, $14.99,  |                  |                  |                  |                  |
    |   2 days, CC-OK) |                  |                  |                  |                  |
    |<-----------------|                  |                  |                  |                  |
```

### Interview One-Liner

> "We use Strategy three times: PricingStrategy lets us swap Standard/Discount pricing without touching OrderService, PaymentStrategy lets us plug in CreditCard/Wallet/COD payment methods, and ShippingStrategy lets us swap Standard/Express shipping. Each axis of variation in checkout is independently swappable -- classic OCP."

### Cross-Reference
- **Decorator** (Pattern 8) -- `DiscountPricingStrategy` wraps `StandardPricingStrategy`, making it both a Strategy and a Decorator
- **Factory** (Pattern 3) -- `AppConfig` decides which strategy implementations to wire
- **Saga** (Pattern 9) -- Payment strategy is invoked as a saga step with compensation

---

## 2. Builder Pattern

### What

Separate the construction of a complex object from its representation. In an e-commerce system, objects like `Order`, `Cart`, `Payment`, and `SagaResult` have many optional fields -- Builder prevents telescoping constructors and makes construction readable.

### ASCII Diagram

```
  +---------------------------+       +---------------------------+
  | Order.Builder             |       | Order (immutable)         |
  +---------------------------+       +---------------------------+
  | - orderId: String         |       | - orderId: String         |
  | - userId: String          |       | - userId: String          |
  | - items: List<OrderItem>  | build | - items: List<OrderItem>  |
  | - shippingAddress: Addr   |------>| - shippingAddress: Addr   |
  | - totalAmount: BigDecimal |       | - totalAmount: BigDecimal |
  | - status: OrderStatus     |       | - status: OrderStatus     |
  | - notes: String           |       | - notes: String           |
  | - discountCode: String    |       | - discountCode: String    |
  | - paymentMethod: String   |       | - paymentMethod: String   |
  +---------------------------+       +---------------------------+
  | + orderId(String): Builder|
  | + userId(String): Builder |       Similarly for:
  | + items(List): Builder    |       - Cart.Builder
  | + shipping(Addr): Builder |       - Payment.Builder
  | + total(BigDecimal): Bldr |       - SagaResult.Builder
  | + status(Status): Builder |
  | + notes(String): Builder  |
  | + discount(String): Bldr  |
  | + build(): Order          |
  +---------------------------+
```

### Ugly Code -- Without Builder

```java
// ANTI-PATTERN: Telescoping constructors
// Order has 10 fields, most optional -- how many constructors do you need?
public class Order {
    // Constructor 1: bare minimum
    public Order(String orderId, String userId) { ... }

    // Constructor 2: with items
    public Order(String orderId, String userId, List<OrderItem> items) { ... }

    // Constructor 3: with items and address
    public Order(String orderId, String userId, List<OrderItem> items,
                 Address shippingAddress) { ... }

    // Constructor 4: with items, address, and total
    public Order(String orderId, String userId, List<OrderItem> items,
                 Address shippingAddress, BigDecimal totalAmount) { ... }

    // Constructor 5: with everything
    public Order(String orderId, String userId, List<OrderItem> items,
                 Address shippingAddress, BigDecimal totalAmount,
                 OrderStatus status, String notes, String discountCode,
                 String paymentMethod, Instant createdAt) { ... }
}

// Caller: which argument is which? Completely unreadable.
Order order = new Order("ORD-123", "USR-456", items, addr,
    new BigDecimal("135.00"), OrderStatus.CREATED, null, "SAVE10",
    "CREDIT_CARD", Instant.now());
//                                   ^^^^
//                             What's null here? notes? paymentMethod?
```

**Problems with this approach:**
- 5+ constructors, hard to maintain
- `null` for optional fields -- unreadable at the call site
- Swapping argument order compiles but introduces bugs (String, String, String... which is which?)
- Cannot enforce required vs optional fields at compile time

### Clean Code -- With Builder

```java
public class Order {
    private final String orderId;
    private final String userId;
    private final List<OrderItem> items;
    private final Address shippingAddress;
    private final BigDecimal totalAmount;
    private final OrderStatus status;
    private final String notes;
    private final String discountCode;
    private final Instant createdAt;

    private Order(Builder builder) {
        this.orderId = builder.orderId;
        this.userId = builder.userId;
        this.items = Collections.unmodifiableList(builder.items);
        this.shippingAddress = builder.shippingAddress;
        this.totalAmount = builder.totalAmount;
        this.status = builder.status;
        this.notes = builder.notes;
        this.discountCode = builder.discountCode;
        this.createdAt = builder.createdAt;
    }

    // All getters...

    public static class Builder {
        // Required
        private final String orderId;
        private final String userId;

        // Optional with defaults
        private List<OrderItem> items = new ArrayList<>();
        private Address shippingAddress;
        private BigDecimal totalAmount = BigDecimal.ZERO;
        private OrderStatus status = OrderStatus.CREATED;
        private String notes = "";
        private String discountCode;
        private Instant createdAt = Instant.now();

        public Builder(String orderId, String userId) {
            this.orderId = orderId;
            this.userId = userId;
        }

        public Builder items(List<OrderItem> items) {
            this.items = new ArrayList<>(items);
            return this;
        }

        public Builder shippingAddress(Address address) {
            this.shippingAddress = address;
            return this;
        }

        public Builder totalAmount(BigDecimal amount) {
            this.totalAmount = amount;
            return this;
        }

        public Builder status(OrderStatus status) {
            this.status = status;
            return this;
        }

        public Builder notes(String notes) {
            this.notes = notes;
            return this;
        }

        public Builder discountCode(String code) {
            this.discountCode = code;
            return this;
        }

        public Order build() {
            // Validate required fields
            Objects.requireNonNull(orderId, "orderId required");
            Objects.requireNonNull(userId, "userId required");
            return new Order(this);
        }
    }
}

// Caller: crystal clear, self-documenting
Order order = new Order.Builder("ORD-123", "USR-456")
    .items(cartItems)
    .shippingAddress(userAddress)
    .totalAmount(new BigDecimal("135.00"))
    .discountCode("SAVE10")
    .build();
// status defaults to CREATED, notes defaults to "", createdAt defaults to now()
```

### Numbered Call Chain -- Building an Order During Checkout

```
  OrderService          Order.Builder              Order
      |                      |                       |
      | (1) new Builder      |                       |
      |  ("ORD-123",         |                       |
      |   "USR-456")         |                       |
      |--------------------->|                       |
      |                      |                       |
      | (2) .items(items)    |                       |
      |--------------------->| set items             |
      |   <-- return this    |                       |
      |                      |                       |
      | (3) .shippingAddress |                       |
      |  (userAddr)          |                       |
      |--------------------->| set address           |
      |   <-- return this    |                       |
      |                      |                       |
      | (4) .totalAmount     |                       |
      |  ($135.00)           |                       |
      |--------------------->| set total             |
      |   <-- return this    |                       |
      |                      |                       |
      | (5) .discountCode    |                       |
      |  ("SAVE10")          |                       |
      |--------------------->| set discount          |
      |   <-- return this    |                       |
      |                      |                       |
      | (6) .build()         |                       |
      |--------------------->|                       |
      |                      | (7) validate fields   |
      |                      | (8) new Order(this)   |
      |                      |---------------------->|
      |   Order (immutable)  |                       |
      |<---------------------|                       |
```

### Interview One-Liner

> "Order, Cart, Payment, and SagaResult all use Builder because they have 8-10 fields with required vs optional semantics. Builder makes construction self-documenting, enforces validation in build(), and produces immutable objects -- critical when orders are passed through saga steps."

### Cross-Reference
- **Saga** (Pattern 9) -- `SagaResult.Builder` collects results from each saga step
- **Factory** (Pattern 3) -- `AppConfig` uses builders internally when constructing test data
- **State** (Pattern 7) -- Order built with initial state `CREATED`, then transitions via state machine

---

## 3. Factory Pattern

### What

Centralize object creation in one place so the rest of the codebase works with interfaces, never concrete classes. In this project, `AppConfig` is the single Factory that wires all dependencies.

### ASCII Diagram

```
  +------------------------------------------------------------------+
  |                        AppConfig (Factory)                        |
  +------------------------------------------------------------------+
  |                                                                    |
  |  Creates & wires:                                                  |
  |                                                                    |
  |  REPOSITORIES          STRATEGIES              SERVICES            |
  |  ============          ==========              ========            |
  |  ProductRepository     StandardPricing         InventoryService    |
  |  OrderRepository       DiscountPricing         PaymentService      |
  |  InventoryRepository   CreditCardPayment       CartService         |
  |  CartRepository        WalletPayment           OrderService        |
  |  PaymentRepository     CODPayment              NotificationService |
  |                        StandardShipping        OrderSagaOrch.      |
  |                        ExpressShipping                             |
  |                                                                    |
  |  ONLY class that says "new ConcreteClass()"                        |
  +------------------------------------------------------------------+
```

### Ugly Code -- Without Factory

```java
// ANTI-PATTERN: new ConcreteClass() scattered everywhere
// Changing a repository implementation means editing 15 files
public class OrderService {
    // Hard-coded to concrete implementations
    private InMemoryOrderRepository orderRepo = new InMemoryOrderRepository();
    private InMemoryInventoryRepository inventoryRepo = new InMemoryInventoryRepository();
    private StandardPricingStrategy pricing = new StandardPricingStrategy();
    private CreditCardPaymentStrategy payment = new CreditCardPaymentStrategy();
    private NotificationService notification = new NotificationService(
        new InMemoryOrderRepository()  // ANOTHER instance! Are they sharing state?
    );

    public void checkout(Cart cart) {
        BigDecimal total = pricing.calculatePrice(cart);
        payment.pay(total, order);
        // Tightly coupled to every concrete class
    }
}

public class CartService {
    // Same hard-coded mess
    private InMemoryCartRepository cartRepo = new InMemoryCartRepository();
    private InMemoryProductRepository productRepo = new InMemoryProductRepository();
    private StandardPricingStrategy pricing = new StandardPricingStrategy(); // duplicate!
}
```

**Problems with this approach:**
- Changing from `InMemory` to `PostgreSQL` repository = edit every service class
- Duplicate instances -- `NotificationService` creates its own repo, doesn't share state
- Cannot swap strategies for testing (e.g., mock payment gateway)
- Dependency graph is invisible -- you must read every class to understand wiring

### Clean Code -- With Factory

```java
public class AppConfig {
    // --- Repositories (one instance each, shared across services) ---
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final InventoryRepository inventoryRepository;
    private final CartRepository cartRepository;
    private final PaymentRepository paymentRepository;

    // --- Strategies ---
    private final PricingStrategy pricingStrategy;
    private final PaymentStrategy paymentStrategy;
    private final ShippingStrategy shippingStrategy;

    // --- Services ---
    private final InventoryService inventoryService;
    private final PaymentService paymentService;
    private final CartService cartService;
    private final NotificationService notificationService;
    private final OrderSagaOrchestrator sagaOrchestrator;
    private final OrderService orderService;

    public AppConfig() {
        // (1) Create repositories -- ONLY place that says "new InMemory..."
        this.productRepository = new InMemoryProductRepository();
        this.orderRepository = new InMemoryOrderRepository();
        this.inventoryRepository = new InMemoryInventoryRepository();
        this.cartRepository = new InMemoryCartRepository();
        this.paymentRepository = new InMemoryPaymentRepository();

        // (2) Create strategies -- ONLY place that picks concrete algorithms
        PricingStrategy basePricing = new StandardPricingStrategy();
        this.pricingStrategy = new DiscountPricingStrategy(basePricing,
            BigDecimal.TEN); // 10% discount
        this.paymentStrategy = new CreditCardPaymentStrategy();
        this.shippingStrategy = new ExpressShippingStrategy();

        // (3) Create services -- inject interfaces, not concrete classes
        this.inventoryService = new InventoryService(inventoryRepository);
        this.paymentService = new PaymentService(paymentRepository);
        this.cartService = new CartService(cartRepository, productRepository,
            pricingStrategy);
        this.notificationService = new NotificationService();

        // (4) Create saga orchestrator -- wires inventory + payment + notification
        this.sagaOrchestrator = new OrderSagaOrchestrator(
            inventoryService, paymentService, notificationService);

        // (5) Create order service (facade) -- wires everything together
        this.orderService = new OrderService(
            orderRepository, cartService, sagaOrchestrator,
            pricingStrategy, paymentStrategy, shippingStrategy,
            notificationService);
    }

    // Getters for each component...
    public OrderService getOrderService() { return orderService; }
    public CartService getCartService() { return cartService; }
    // ...
}
```

### Numbered Call Chain -- Application Startup

```
  Main             AppConfig           Repositories          Strategies          Services
    |                  |                    |                    |                    |
    | (1) new          |                    |                    |                    |
    |  AppConfig()     |                    |                    |                    |
    |----------------->|                    |                    |                    |
    |                  | (2) new InMemory   |                    |                    |
    |                  |  ProductRepo()     |                    |                    |
    |                  |------------------->| (5 repos created)  |                    |
    |                  |                    |                    |                    |
    |                  | (3) new Standard   |                    |                    |
    |                  |  PricingStrategy() |                    |                    |
    |                  |---------------------------------------->| (strategies)     |
    |                  |                    |                    |                    |
    |                  | (4) new Discount   |                    |                    |
    |                  |  PricingStrategy   |                    |                    |
    |                  |  (wraps standard)  |                    |                    |
    |                  |---------------------------------------->|                    |
    |                  |                    |                    |                    |
    |                  | (5) new Inventory  |                    |                    |
    |                  |  Service(repo)     |                    |                    |
    |                  |--------------------------------------------------------------->|
    |                  |                    |                    |                    |
    |                  | (6) new OrderSaga  |                    |                    |
    |                  |  Orchestrator      |                    |                    |
    |                  |  (inv, pay, notif) |                    |                    |
    |                  |--------------------------------------------------------------->|
    |                  |                    |                    |                    |
    |                  | (7) new Order      |                    |                    |
    |                  |  Service (facade)  |                    |                    |
    |                  |  (repo, cart, saga,|                    |                    |
    |                  |   price, pay, ship,|                    |                    |
    |                  |   notif)           |                    |                    |
    |                  |--------------------------------------------------------------->|
    |   AppConfig      |                    |                    |                    |
    |   (fully wired)  |                    |                    |                    |
    |<-----------------|                    |                    |                    |
```

### Interview One-Liner

> "AppConfig is a pure Factory -- it's the ONLY class that says `new ConcreteClass()`. All services depend on interfaces. To switch from in-memory to PostgreSQL, we edit one file. In production, this is Spring Boot's @Configuration; we simulate it with a plain Java factory."

### Cross-Reference
- **Strategy** (Pattern 1) -- Factory decides which pricing/payment/shipping strategy to inject
- **Repository** (Pattern 4) -- Factory creates all 5 repository instances
- **Facade** (Pattern 5) -- Factory wires OrderService with all its dependencies

---

## 4. Repository Pattern

### What

Abstract the data access layer behind an interface so the domain layer doesn't know (or care) whether data lives in a HashMap, PostgreSQL, Redis, or DynamoDB. In this project, we have 5 repositories.

### ASCII Diagram -- 5 Repository Interfaces

```
  +---------------------+   +---------------------+   +---------------------+
  | <<interface>>       |   | <<interface>>       |   | <<interface>>       |
  | ProductRepository   |   | OrderRepository     |   | InventoryRepository |
  +---------------------+   +---------------------+   +---------------------+
  | + findById(id)      |   | + save(order)       |   | + getStock(prodId)  |
  | + findByCategory()  |   | + findById(id)      |   | + reserve(id, qty)  |
  | + search(keyword)   |   | + findByUserId(uid) |   | + release(id, qty)  |
  | + save(product)     |   | + updateStatus(s)   |   | + deduct(id, qty)   |
  +----------+----------+   +----------+----------+   +----------+----------+
             |                         |                         |
  +----------+----------+   +----------+----------+   +----------+----------+
  | InMemoryProduct     |   | InMemoryOrder       |   | InMemoryInventory   |
  | Repository          |   | Repository          |   | Repository          |
  | (ConcurrentHashMap) |   | (ConcurrentHashMap) |   | (ConcurrentHashMap) |
  +---------------------+   +---------------------+   | (synchronized for   |
                                                       |  reserve/release)   |
                                                       +---------------------+

  +---------------------+   +---------------------+
  | <<interface>>       |   | <<interface>>       |
  | CartRepository      |   | PaymentRepository   |
  +---------------------+   +---------------------+
  | + getCart(userId)    |   | + save(payment)     |
  | + saveCart(cart)     |   | + findById(id)      |
  | + clearCart(userId)  |   | + findByOrderId(id) |
  +----------+----------+   +----------+----------+
             |                         |
  +----------+----------+   +----------+----------+
  | InMemoryCart         |   | InMemoryPayment     |
  | Repository          |   | Repository          |
  | (ConcurrentHashMap) |   | (ConcurrentHashMap) |
  +---------------------+   +---------------------+
```

### Ugly Code -- Without Repository

```java
// ANTI-PATTERN: SQL and data access logic in the service layer
public class OrderService {
    private Connection connection;

    public Order getOrder(String orderId) {
        try {
            PreparedStatement stmt = connection.prepareStatement(
                "SELECT o.*, oi.product_id, oi.quantity, oi.price " +
                "FROM orders o " +
                "JOIN order_items oi ON o.id = oi.order_id " +
                "WHERE o.id = ?");
            stmt.setString(1, orderId);
            ResultSet rs = stmt.executeQuery();

            Order order = null;
            List<OrderItem> items = new ArrayList<>();
            while (rs.next()) {
                if (order == null) {
                    order = new Order();
                    order.setOrderId(rs.getString("id"));
                    order.setUserId(rs.getString("user_id"));
                    order.setStatus(OrderStatus.valueOf(rs.getString("status")));
                }
                OrderItem item = new OrderItem();
                item.setProductId(rs.getString("product_id"));
                item.setQuantity(rs.getInt("quantity"));
                item.setPrice(rs.getBigDecimal("price"));
                items.add(item);
            }
            // 30 more lines of ResultSet mapping...
            order.setItems(items);
            return order;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get order", e);
        }
    }

    // Same SQL mess for every other method...
    // Want to switch to DynamoDB? Rewrite EVERY method in EVERY service.
}
```

**Problems with this approach:**
- SQL embedded in business logic (SRP violation)
- Cannot test OrderService without a database
- Switching storage requires rewriting every service method
- No abstraction boundary between domain and persistence

### Clean Code -- With Repository

```java
// Interface -- domain layer depends on this
public interface OrderRepository {
    Order save(Order order);
    Optional<Order> findById(String orderId);
    List<Order> findByUserId(String userId);
    void updateStatus(String orderId, OrderStatus status);
}

// In-memory implementation -- used in our simulation
public class InMemoryOrderRepository implements OrderRepository {
    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    @Override
    public Order save(Order order) {
        orders.put(order.getOrderId(), order);
        return order;
    }

    @Override
    public Optional<Order> findById(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    @Override
    public List<Order> findByUserId(String userId) {
        return orders.values().stream()
            .filter(o -> o.getUserId().equals(userId))
            .collect(Collectors.toList());
    }

    @Override
    public void updateStatus(String orderId, OrderStatus status) {
        Order order = orders.get(orderId);
        if (order != null) {
            order.setStatus(status);
        }
    }
}

// InventoryRepository -- special: needs synchronized for reserve/release
public interface InventoryRepository {
    int getStock(String productId);
    boolean reserve(String productId, int quantity);
    void release(String productId, int quantity);
    void deduct(String productId, int quantity);
}

public class InMemoryInventoryRepository implements InventoryRepository {
    private final Map<String, Integer> stock = new ConcurrentHashMap<>();

    @Override
    public synchronized boolean reserve(String productId, int quantity) {
        int current = stock.getOrDefault(productId, 0);
        if (current >= quantity) {
            stock.put(productId, current - quantity);
            return true;
        }
        return false;  // insufficient stock
    }

    @Override
    public synchronized void release(String productId, int quantity) {
        stock.merge(productId, quantity, Integer::sum);  // add back
    }
    // ...
}
```

### Interview One-Liner

> "Five repositories (Product, Order, Inventory, Cart, Payment) decouple domain logic from storage. In our simulation, everything is ConcurrentHashMap. In production, Order goes to PostgreSQL, Cart goes to Redis, Inventory to DynamoDB with conditional writes. InventoryRepository.reserve() is synchronized to prevent overselling."

### Cross-Reference
- **Factory** (Pattern 3) -- `AppConfig` creates all 5 repository instances
- **Saga** (Pattern 9) -- Saga steps call repositories for reserve, deduct, release
- **Facade** (Pattern 5) -- `OrderService` uses repositories through service layer, never directly

---

## 5. Facade Pattern

### What

Provide a simplified interface to a complex subsystem. `OrderService` is the Facade -- it orchestrates cart retrieval, saga execution, payment processing, shipping calculation, and notification sending behind a single `placeOrder()` method.

### ASCII Diagram

```
  +------------------------------------------------------------------+
  |                     CLIENT (Controller)                           |
  +------------------------------------------------------------------+
                              |
                              | placeOrder(userId, paymentMethod)
                              v
  +------------------------------------------------------------------+
  |                    OrderService (FACADE)                          |
  +------------------------------------------------------------------+
  | Orchestrates:                                                      |
  |   (1) CartService.getCart()                                        |
  |   (2) PricingStrategy.calculatePrice()                             |
  |   (3) Order.Builder.build()                                        |
  |   (4) OrderSagaOrchestrator.execute()                              |
  |         (4a) InventoryService.reserve()                            |
  |         (4b) PaymentService.charge()                               |
  |         (4c) compensate on failure                                 |
  |   (5) ShippingStrategy.calculateCost()                             |
  |   (6) NotificationService.notify()                                 |
  |   (7) CartService.clearCart()                                      |
  +------------------------------------------------------------------+
                |          |          |          |          |
        +-------+    +-----+    +-----+    +-----+    +---+----+
        |            |          |          |          |         |
  CartService  PricingStr  SagaOrch  ShippingStr  NotifSvc  OrderRepo
```

### Ugly Code -- Without Facade

```java
// ANTI-PATTERN: Controller does all the orchestration
// Business logic leaks into the controller layer
public class OrderController {

    public void placeOrder(String userId, String paymentMethod) {
        // Controller is doing EVERYTHING -- 80 lines of orchestration
        Cart cart = cartRepository.getCart(userId);
        if (cart == null || cart.getItems().isEmpty()) {
            throw new RuntimeException("Cart is empty");
        }

        BigDecimal total = BigDecimal.ZERO;
        for (CartItem item : cart.getItems()) {
            total = total.add(item.getProduct().getPrice()
                .multiply(BigDecimal.valueOf(item.getQuantity())));
        }

        // Reserve inventory -- what if payment fails? No compensation!
        for (CartItem item : cart.getItems()) {
            int stock = inventoryRepo.getStock(item.getProduct().getId());
            if (stock < item.getQuantity()) {
                throw new RuntimeException("Out of stock: " + item.getProduct().getName());
                // BUG: previous items were reserved but not released!
            }
            inventoryRepo.reserve(item.getProduct().getId(), item.getQuantity());
        }

        // Charge payment
        boolean paid;
        if (paymentMethod.equals("CREDIT")) {
            paid = stripeService.charge(total);
        } else if (paymentMethod.equals("WALLET")) {
            paid = walletService.deduct(total);
        }
        // If payment fails, inventory is stuck as reserved -- data inconsistency!

        // Create order
        Order order = new Order(UUID.randomUUID().toString(), userId, /*...10 args*/);
        orderRepo.save(order);

        // Send notification -- blocking the response!
        emailService.sendOrderConfirmation(userId, order);
        smsService.sendOrderSMS(userId, order);

        cartRepository.clearCart(userId);
    }
}
```

**Problems with this approach:**
- Controller knows every detail of checkout (SRP violation)
- No compensation if payment fails after inventory reservation
- Notification is blocking the HTTP response
- Adding a new step (fraud check, loyalty points) means editing the controller

### Clean Code -- With Facade

```java
public class OrderService {  // THE FACADE
    private final OrderRepository orderRepository;
    private final CartService cartService;
    private final OrderSagaOrchestrator sagaOrchestrator;
    private final PricingStrategy pricingStrategy;
    private final PaymentStrategy paymentStrategy;
    private final ShippingStrategy shippingStrategy;
    private final NotificationService notificationService;

    public Order placeOrder(String userId) {
        // (1) Get cart
        Cart cart = cartService.getCart(userId);
        if (cart.getItems().isEmpty()) {
            throw new EmptyCartException(userId);
        }

        // (2) Calculate price
        BigDecimal total = pricingStrategy.calculatePrice(cart);

        // (3) Build order
        Order order = new Order.Builder(generateOrderId(), userId)
            .items(cart.toOrderItems())
            .totalAmount(total)
            .shippingAddress(cart.getShippingAddress())
            .build();

        // (4) Execute saga (reserve inventory -> charge payment)
        //     If any step fails, previous steps are compensated automatically
        SagaResult result = sagaOrchestrator.execute(order);
        if (!result.isSuccess()) {
            throw new OrderFailedException(result.getFailureReason());
        }

        // (5) Calculate shipping
        BigDecimal shippingCost = shippingStrategy.calculateCost(order);
        int deliveryDays = shippingStrategy.estimateDeliveryDays(order);

        // (6) Save order
        order = orderRepository.save(order);

        // (7) Notify (async via observer)
        notificationService.onOrderPlaced(order);

        // (8) Clear cart
        cartService.clearCart(userId);

        return order;
    }
}
```

### Numbered Call Chain -- placeOrder("USR-456")

```
  Controller     OrderService     CartService    PricingStr    SagaOrch     ShippingStr   NotifService
      |               |               |              |             |             |             |
      | (1) placeOrder|               |              |             |             |             |
      |  ("USR-456")  |               |              |             |             |             |
      |-------------->|               |              |             |             |             |
      |               |               |              |             |             |             |
      |               | (2) getCart   |              |             |             |             |
      |               |  ("USR-456")  |              |             |             |             |
      |               |-------------->|              |             |             |             |
      |               |  Cart(3 items)|              |             |             |             |
      |               |<-------------|              |             |             |             |
      |               |               |              |             |             |             |
      |               | (3) calculate |              |             |             |             |
      |               |  Price(cart)  |              |             |             |             |
      |               |---------------------------->|             |             |             |
      |               |  $135.00      |              |             |             |             |
      |               |<----------------------------|             |             |             |
      |               |               |              |             |             |             |
      |               | (4) Order.Builder.build()    |             |             |             |
      |               |  (orderId, userId, items,    |             |             |             |
      |               |   total, address)            |             |             |             |
      |               |               |              |             |             |             |
      |               | (5) saga      |              |             |             |             |
      |               |  .execute     |              |             |             |             |
      |               |  (order)      |              |             |             |             |
      |               |------------------------------------------>|             |             |
      |               |               |              |             |             |             |
      |               |               |   (5a) reserveInventory   |             |             |
      |               |               |   (5b) chargePayment      |             |             |
      |               |               |   (5c) if fail: compensate|             |             |
      |               |               |              |             |             |             |
      |               |  SagaResult   |              |             |             |             |
      |               |  (SUCCESS)    |              |             |             |             |
      |               |<------------------------------------------|             |             |
      |               |               |              |             |             |             |
      |               | (6) calculate |              |             |             |             |
      |               |  Cost(order)  |              |             |             |             |
      |               |-------------------------------------------------------->|             |
      |               |  $14.99       |              |             |             |             |
      |               |<--------------------------------------------------------|             |
      |               |               |              |             |             |             |
      |               | (7) onOrder   |              |             |             |             |
      |               |  Placed(order)|              |             |             |             |
      |               |--------------------------------------------------------------------->|
      |               |               |              |             |             |             |
      |               | (8) clearCart |              |             |             |             |
      |               |  ("USR-456")  |              |             |             |             |
      |               |-------------->|              |             |             |             |
      |               |               |              |             |             |             |
      |  Order        |               |              |             |             |             |
      |  (complete)   |               |              |             |             |             |
      |<--------------|               |              |             |             |             |
```

### Interview One-Liner

> "OrderService is a Facade -- the controller calls one method `placeOrder()`, and behind it the facade orchestrates cart retrieval, pricing, saga execution with compensation, shipping, notification, and cart clearing. The client never sees the 7-step workflow."

### Cross-Reference
- **Saga** (Pattern 9) -- Facade delegates the transactional part to `OrderSagaOrchestrator`
- **Strategy** (Pattern 1) -- Facade uses injected pricing/payment/shipping strategies
- **Observer** (Pattern 6) -- Facade triggers notification as the last step

---

## 6. Observer Pattern

### What

Define a one-to-many dependency between objects so that when one object changes state, all its dependents are notified and updated automatically. `NotificationService` observes order state changes -- when an order is placed, shipped, or delivered, it sends notifications without `OrderService` knowing the notification details.

### ASCII Diagram

```
  +---------------------------+       +---------------------------+
  | OrderService (Subject)    |       | NotificationService       |
  |                           |       | (Observer)                |
  +---------------------------+       +---------------------------+
  | - observers: List<Obs>    |       |                           |
  +---------------------------+       | + onOrderPlaced(order)    |
  | + placeOrder()            |------>| + onOrderShipped(order)   |
  | + shipOrder()             |------>| + onOrderDelivered(order) |
  | + deliverOrder()          |------>| + onPaymentFailed(order)  |
  +---------------------------+       +---------------------------+
                                              |
                                      +-------+--------+
                                      |                |
                              +-------+------+  +------+-------+
                              | Email Notif  |  | SMS Notif    |
                              | (simulated)  |  | (simulated)  |
                              +--------------+  +--------------+
```

### Ugly Code -- Without Observer

```java
// ANTI-PATTERN: OrderService directly calls every notification channel
// Adding push notifications = edit OrderService = OCP violation
public class OrderService {

    public Order placeOrder(String userId) {
        Order order = /* ... create order ... */;

        // OrderService shouldn't know about notification channels!
        emailService.send(userId, "Order " + order.getId() + " placed");
        smsService.send(userId, "Order " + order.getId() + " placed");
        // Adding push notification? Edit this method.
        // Adding Slack notification for ops team? Edit this method.
        // Adding analytics event? Edit this method.

        return order;
    }

    public void shipOrder(String orderId) {
        // Same notification code duplicated here
        emailService.send(userId, "Order " + orderId + " shipped");
        smsService.send(userId, "Order " + orderId + " shipped");
        // Copy-paste of every notification channel AGAIN
    }
}
```

**Problems with this approach:**
- `OrderService` coupled to every notification channel (SRP violation)
- Adding a new notification type requires editing `OrderService` (OCP violation)
- Notification logic duplicated in every state transition method
- Notification calls are synchronous -- slow down the checkout flow

### Clean Code -- With Observer

```java
// Observer interface
public interface OrderEventListener {
    void onOrderPlaced(Order order);
    void onOrderShipped(Order order);
    void onOrderDelivered(Order order);
    void onPaymentFailed(Order order, String reason);
}

// Concrete observer -- handles all notification channels
public class NotificationService implements OrderEventListener {

    @Override
    public void onOrderPlaced(Order order) {
        // (1) Send email confirmation
        System.out.printf("[EMAIL] Order %s confirmed for user %s. Total: $%s%n",
            order.getOrderId(), order.getUserId(), order.getTotalAmount());

        // (2) Send SMS
        System.out.printf("[SMS] Order %s placed. Estimated delivery: %d days%n",
            order.getOrderId(), order.getEstimatedDeliveryDays());
    }

    @Override
    public void onOrderShipped(Order order) {
        System.out.printf("[EMAIL] Order %s has shipped! Tracking: %s%n",
            order.getOrderId(), order.getTrackingNumber());
    }

    @Override
    public void onOrderDelivered(Order order) {
        System.out.printf("[EMAIL] Order %s delivered. Rate your experience!%n",
            order.getOrderId());
    }

    @Override
    public void onPaymentFailed(Order order, String reason) {
        System.out.printf("[EMAIL] Payment failed for order %s: %s%n",
            order.getOrderId(), reason);
        System.out.printf("[SMS] Payment issue with order %s. Please retry.%n",
            order.getOrderId());
    }
}

// OrderService -- notifies observers, doesn't know the details
public class OrderService {
    private final List<OrderEventListener> listeners = new ArrayList<>();

    public void addListener(OrderEventListener listener) {
        listeners.add(listener);
    }

    public Order placeOrder(String userId) {
        Order order = /* ... create order ... */;

        // Notify all observers -- OrderService doesn't know WHO listens
        listeners.forEach(l -> l.onOrderPlaced(order));

        return order;
    }
}
```

### Numbered Call Chain -- Order Placed Notification

```
  OrderService     NotificationService     EmailChannel     SMSChannel
      |                   |                     |               |
      | (1) order placed  |                     |               |
      |  (state = PLACED) |                     |               |
      |                   |                     |               |
      | (2) notify all    |                     |               |
      |  listeners        |                     |               |
      |------------------>|                     |               |
      |                   |                     |               |
      |                   | (3) send email      |               |
      |                   |  confirmation       |               |
      |                   |-------------------->|               |
      |                   |                     |               |
      |                   | (4) send SMS        |               |
      |                   |  notification       |               |
      |                   |------------------------------------>|
      |                   |                     |               |
      |  (fire and forget)|                     |               |
      |<------------------|                     |               |
```

### Interview One-Liner

> "NotificationService observes order state changes via the Observer pattern. OrderService just calls `listeners.forEach(l -> l.onOrderPlaced(order))` -- it doesn't know if we send email, SMS, push, or Slack. Adding a new channel means adding a new listener, not editing OrderService."

### Cross-Reference
- **Facade** (Pattern 5) -- OrderService (facade) triggers observers as part of the workflow
- **State** (Pattern 7) -- State transitions (PLACED -> SHIPPED -> DELIVERED) trigger observer notifications
- **Saga** (Pattern 9) -- Payment failure in saga triggers `onPaymentFailed` observer

---

## 7. State Pattern

### What

Allow an object to alter its behavior when its internal state changes. The object will appear to change its class. In an e-commerce system, an `Order` transitions through a well-defined state machine: CREATED -> INVENTORY_RESERVED -> PAYMENT_CONFIRMED -> SHIPPED -> DELIVERED.

### ASCII Diagram -- Order State Machine

```
  +----------+    reserve     +--------------------+    pay      +--------------------+
  |          | inventory OK   |                    | confirmed   |                    |
  | CREATED  |--------------->| INVENTORY_RESERVED |------------>| PAYMENT_CONFIRMED  |
  |          |                |                    |             |                    |
  +----+-----+                +--------+-----------+             +--------+-----------+
       |                               |                                  |
       | reserve fails                 | payment fails                    | ship
       |                               |                                  |
       v                               v                                  v
  +----------+                +--------------------+             +--------------------+
  |          |                |                    |             |                    |
  | FAILED   |                | PAYMENT_FAILED     |             | SHIPPED            |
  |          |                | (compensate:       |             |                    |
  +----------+                |  release inventory)|             +--------+-----------+
                              +--------------------+                      |
                                                                          | deliver
                                                                          v
                                                                 +--------------------+
                                                                 |                    |
                                                                 | DELIVERED          |
                                                                 |                    |
                                                                 +--------+-----------+
                                                                          |
                                                                          | return
                                                                          v
                                                                 +--------------------+
                                                                 |                    |
                                                                 | RETURNED           |
                                                                 |                    |
                                                                 +--------------------+
```

### Valid Transitions Table

| From State | To State | Trigger | Compensation on Failure |
|-----------|----------|---------|------------------------|
| CREATED | INVENTORY_RESERVED | Inventory reserved successfully | N/A |
| CREATED | FAILED | Inventory reservation failed (out of stock) | N/A |
| INVENTORY_RESERVED | PAYMENT_CONFIRMED | Payment charged successfully | N/A |
| INVENTORY_RESERVED | PAYMENT_FAILED | Payment declined | Release reserved inventory |
| PAYMENT_CONFIRMED | SHIPPED | Shipping label generated | Refund payment, release inventory |
| SHIPPED | DELIVERED | Package delivered | N/A |
| DELIVERED | RETURNED | Return initiated | Refund payment, restock inventory |

### Ugly Code -- Without State Pattern

```java
// ANTI-PATTERN: if-else spaghetti for state transitions
public class Order {
    private String status; // magic string

    public void processNext() {
        if (status.equals("CREATED")) {
            if (inventoryAvailable()) {
                status = "INVENTORY_RESERVED";
                reserveInventory();
            } else {
                status = "FAILED";
            }
        } else if (status.equals("INVENTORY_RESERVED")) {
            if (chargePayment()) {
                status = "PAYMENT_CONFIRMED";
            } else {
                status = "PAYMENT_FAILED";
                releaseInventory(); // easy to forget this compensation!
            }
        } else if (status.equals("PAYMENT_CONFIRMED")) {
            status = "SHIPPED";
            generateShippingLabel();
        } else if (status.equals("SHIPPED")) {
            status = "DELIVERED";
        } else if (status.equals("DELIVERED")) {
            // Can we return? Only from DELIVERED...
            if (withinReturnWindow()) {
                status = "RETURNED";
                refundPayment();
                restockInventory();
            }
        }
        // Adding PARTIALLY_SHIPPED? CANCELLED? -- more else-if...
        // Each new state multiplies the complexity of EVERY existing branch
    }

    public boolean canCancel() {
        // Another if-else chain for every query about the order
        return status.equals("CREATED") || status.equals("INVENTORY_RESERVED");
    }
}
```

**Problems with this approach:**
- State transition logic scattered across one giant if-else
- Easy to miss compensation steps (e.g., release inventory on payment failure)
- Invalid transitions not prevented (can accidentally go DELIVERED -> CREATED)
- Adding a new state requires modifying every if-else branch

### Clean Code -- With State Enum and Transition Map

```java
public enum OrderStatus {
    CREATED,
    INVENTORY_RESERVED,
    PAYMENT_CONFIRMED,
    SHIPPED,
    DELIVERED,
    RETURNED,
    FAILED,
    PAYMENT_FAILED;

    // Define valid transitions as a map
    private static final Map<OrderStatus, Set<OrderStatus>> VALID_TRANSITIONS =
        Map.of(
            CREATED,              Set.of(INVENTORY_RESERVED, FAILED),
            INVENTORY_RESERVED,   Set.of(PAYMENT_CONFIRMED, PAYMENT_FAILED),
            PAYMENT_CONFIRMED,    Set.of(SHIPPED),
            SHIPPED,              Set.of(DELIVERED),
            DELIVERED,            Set.of(RETURNED),
            FAILED,               Set.of(),  // terminal
            PAYMENT_FAILED,       Set.of(),  // terminal
            RETURNED,             Set.of()   // terminal
        );

    public boolean canTransitionTo(OrderStatus next) {
        return VALID_TRANSITIONS.getOrDefault(this, Set.of()).contains(next);
    }

    public OrderStatus transitionTo(OrderStatus next) {
        if (!canTransitionTo(next)) {
            throw new InvalidStateTransitionException(
                "Cannot transition from " + this + " to " + next);
        }
        return next;
    }
}

// Order uses the state enum
public class Order {
    private OrderStatus status;

    public void transitionTo(OrderStatus newStatus) {
        // (1) Validate the transition is legal
        this.status = this.status.transitionTo(newStatus);
        // (2) State machine guarantees no illegal jumps
    }

    public boolean canCancel() {
        // Clean: delegate to enum
        return status == OrderStatus.CREATED
            || status == OrderStatus.INVENTORY_RESERVED;
    }
}
```

### Numbered Call Chain -- State Transitions During Saga

```
  SagaOrchestrator      Order          OrderStatus          NotificationService
        |                  |                |                       |
        | (1) transition   |                |                       |
        |  CREATED ->      |                |                       |
        |  INV_RESERVED    |                |                       |
        |----------------->|                |                       |
        |                  | (2) canTrans   |                       |
        |                  |  itionTo?      |                       |
        |                  |--------------->|                       |
        |                  |  true          |                       |
        |                  |<---------------|                       |
        |                  |                |                       |
        |                  | (3) status =   |                       |
        |                  |  INV_RESERVED  |                       |
        |                  |                |                       |
        | (4) transition   |                |                       |
        |  INV_RESERVED -> |                |                       |
        |  PAY_CONFIRMED   |                |                       |
        |----------------->|                |                       |
        |                  | (5) canTrans   |                       |
        |                  |  itionTo?      |                       |
        |                  |--------------->|                       |
        |                  |  true          |                       |
        |                  |<---------------|                       |
        |                  |                |                       |
        |                  | (6) status =   |                       |
        |                  |  PAY_CONFIRMED |                       |
        |                  |                |                       |
        |                  | (7) notify     |                       |
        |                  |  observers     |                       |
        |                  |--------------------------------------->|
        |                  |                |                       |
```

### Interview One-Liner

> "Order uses a State pattern with an enum-based transition map. Each OrderStatus knows which states it can transition to via `VALID_TRANSITIONS`. Illegal jumps like DELIVERED->CREATED throw InvalidStateTransitionException. The saga orchestrator drives transitions; observers react to them."

### Cross-Reference
- **Saga** (Pattern 9) -- Saga steps drive order state transitions
- **Observer** (Pattern 6) -- State changes trigger notifications
- **Builder** (Pattern 2) -- Order built with initial CREATED state

---

## 8. Decorator Pattern

### What

Attach additional responsibilities to an object dynamically. Decorators provide a flexible alternative to subclassing for extending functionality. In this project, `DiscountPricingStrategy` wraps `StandardPricingStrategy` -- it delegates to the base strategy, then applies a discount on top.

### ASCII Diagram

```
  +---------------------------+
  | <<interface>>             |
  | PricingStrategy           |
  +---------------------------+
  | + calculatePrice(cart):   |
  |   BigDecimal              |
  +-------------+-------------+
                |
        +-------+---------+
        |                 |
  +-----+--------+  +----+-----------+
  | Standard     |  | Discount       |
  | Pricing      |  | Pricing        |
  | Strategy     |  | Strategy       |
  | (base)       |  | (DECORATOR)    |
  +--------------+  +----------------+
                    | - basePricing:  |
                    |   PricingStr.   |---wraps---> StandardPricingStrategy
                    | - discountPct:  |
                    |   BigDecimal    |
                    +----------------+

  CALL FLOW:
  DiscountPricingStrategy.calculatePrice(cart)
    |
    +-- (1) basePricing.calculatePrice(cart)  -->  StandardPricingStrategy: $150.00
    |
    +-- (2) discount = $150.00 * 10% = $15.00
    |
    +-- (3) return $150.00 - $15.00 = $135.00
```

### Ugly Code -- Without Decorator

```java
// ANTI-PATTERN: Subclass explosion for every pricing combination
public class StandardPricingStrategy implements PricingStrategy { ... }
public class DiscountPricingStrategy extends StandardPricingStrategy { ... }  // copy-paste
public class PrimePricingStrategy extends StandardPricingStrategy { ... }    // more copy-paste
public class PrimeDiscountPricingStrategy extends StandardPricingStrategy { ... }  // combination!
public class FlashSalePricingStrategy extends StandardPricingStrategy { ... }
public class FlashSaleDiscountPricingStrategy extends StandardPricingStrategy { ... }
public class PrimeFlashSaleDiscountPricingStrategy extends StandardPricingStrategy { ... }
// Combinatorial explosion: n features = 2^n subclasses!
```

**Problems with this approach:**
- Every combination of pricing rules = a new subclass
- 3 pricing features = 8 subclasses; 4 features = 16 subclasses
- Code duplication across subclasses
- Cannot compose pricing rules at runtime

### Clean Code -- With Decorator

```java
// Base strategy (the "component")
public class StandardPricingStrategy implements PricingStrategy {
    @Override
    public BigDecimal calculatePrice(Cart cart) {
        return cart.getItems().stream()
            .map(item -> item.getProduct().getPrice()
                .multiply(BigDecimal.valueOf(item.getQuantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}

// Decorator -- wraps any PricingStrategy and applies discount
public class DiscountPricingStrategy implements PricingStrategy {
    private final PricingStrategy basePricing;    // the wrapped strategy
    private final BigDecimal discountPercent;

    public DiscountPricingStrategy(PricingStrategy basePricing,
                                   BigDecimal discountPercent) {
        this.basePricing = basePricing;
        this.discountPercent = discountPercent;
    }

    @Override
    public BigDecimal calculatePrice(Cart cart) {
        // (1) Delegate to base -- Decorator's signature move
        BigDecimal basePrice = basePricing.calculatePrice(cart);

        // (2) Apply discount on top
        BigDecimal discount = basePrice.multiply(discountPercent)
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        // (3) Return decorated result
        return basePrice.subtract(discount);
    }
}

// Runtime composition -- no subclass explosion!
PricingStrategy pricing = new StandardPricingStrategy();
pricing = new DiscountPricingStrategy(pricing, BigDecimal.TEN);        // wrap with 10% off
// Could further wrap: pricing = new PrimePricingDecorator(pricing);
// Could further wrap: pricing = new FlashSaleDecorator(pricing, saleId);
// Each decorator adds behavior, total composition is dynamic
```

### Interview One-Liner

> "DiscountPricingStrategy is both a Strategy AND a Decorator. It wraps StandardPricingStrategy, delegates calculatePrice() to get the base total, then subtracts the discount. We can stack multiple decorators at runtime -- discount + prime + flash sale -- without subclass explosion."

### Cross-Reference
- **Strategy** (Pattern 1) -- The Decorator IS a Strategy (same interface), just wraps another one
- **Factory** (Pattern 3) -- `AppConfig` wires `DiscountPricingStrategy(StandardPricingStrategy)`

---

## 9. Saga Pattern (The Star of This Project)

### What

The Saga pattern manages distributed transactions in a microservices architecture by breaking a long-running transaction into a sequence of local transactions, each with a compensating action. If any step fails, the saga executes compensating actions in reverse order to undo the effects of completed steps.

### Why Saga? The Problem with 2PC (Two-Phase Commit)

```
  THE PROBLEM: Checkout spans multiple services

  +-----------+       +-----------+       +-----------+       +-----------+
  | Order     |       | Inventory |       | Payment   |       | Shipping  |
  | Service   |       | Service   |       | Service   |       | Service   |
  +-----------+       +-----------+       +-----------+       +-----------+
       |                   |                   |                   |
       | We need ALL of these to succeed, or NONE of them.
       | This is a distributed transaction.
       |
       | In a monolith: BEGIN TRANSACTION ... COMMIT/ROLLBACK
       | In microservices: each service has its own database.
       | SQL transactions don't span databases!
```

### Why 2PC Doesn't Work for Microservices

```
  TWO-PHASE COMMIT (2PC):
  ========================

  Coordinator          Inventory DB         Payment DB          Shipping DB
       |                   |                    |                    |
       | (1) PREPARE       |                    |                    |
       |------------------>|                    |                    |
       |-------------------|------ PREPARE ---->|                    |
       |-------------------|-------|-- PREPARE ->|                    |
       |                   |       |             |                    |
       | (2) All vote YES  |       |             |                    |
       |<------ YES -------|       |             |                    |
       |<------------- YES --------|             |                    |
       |<--------------------- YES --------------|                    |
       |                   |       |             |                    |
       | (3) COMMIT        |       |             |                    |
       |---- COMMIT ------>|       |             |                    |
       |---- COMMIT -------|------>|             |                    |
       |---- COMMIT -------|-------|------------>|                    |
       |                   |       |             |                    |

  PROBLEMS WITH 2PC:
  +-----------------------------------------------------------------+
  | Problem            | Why It's Fatal for Microservices            |
  +--------------------+--------------------------------------------+
  | Synchronous locks  | All participants hold DB locks during       |
  |                    | PREPARE -> COMMIT. At Amazon scale (100K    |
  |                    | orders/sec), this blocks everything.        |
  +--------------------+--------------------------------------------+
  | Single point of    | Coordinator crashes between PREPARE and    |
  | failure            | COMMIT = all participants stuck with locks  |
  |                    | held indefinitely (blocking protocol).      |
  +--------------------+--------------------------------------------+
  | Availability       | ANY participant is down = entire tx blocks. |
  |                    | With 4 services at 99.9% uptime each,      |
  |                    | combined: 99.6% = 14 hours downtime/year.  |
  +--------------------+--------------------------------------------+
  | Latency            | Network round-trips to ALL participants     |
  |                    | before commit. p99 = slowest participant.  |
  +--------------------+--------------------------------------------+
  | Not supported      | Most NoSQL databases (DynamoDB, Cassandra)  |
  |                    | don't support distributed prepare/commit.   |
  +--------------------+--------------------------------------------+
```

### Choreography vs Orchestration

```
  CHOREOGRAPHY (event-driven):
  ============================
  Each service publishes an event; the next service listens and acts.

  (1) OrderService          (2) InventoryService       (3) PaymentService
       |                         |                          |
       |-- OrderCreated -------->|                          |
       |   (Kafka event)        |                          |
       |                        |-- InventoryReserved ---->|
       |                        |   (Kafka event)         |
       |                        |                         |-- PaymentCharged ------>
       |                        |                         |   (Kafka event)

  PROS: No single point of failure, services are fully decoupled
  CONS: Hard to debug (no central view), hard to add new steps,
        cyclic event dependencies, difficult to track saga state

  ORCHESTRATION (centralized coordinator):
  =========================================
  A central orchestrator tells each service what to do and handles failures.

  (1)             (2)             (3)             (4)
   SagaOrchestrator ---reserve---> InventoryService
       |                               |
       |<------- reserved OK ----------|
       |
       |--------charge-------> PaymentService
       |                            |
       |<------ charged OK ---------|
       |
       |--------ship---------> ShippingService
       |                            |
       |<------ shipped OK ---------|

  PROS: Central view of saga state, easy to add/remove steps,
        clear compensation flow, easy to debug and monitor
  CONS: Single point of failure (mitigate with replicas),
        orchestrator can become a bottleneck

  WE CHOSE ORCHESTRATION because:
  - Easier to reason about compensation (reverse order)
  - Central place to log saga state for debugging
  - Interview-friendly: easier to explain and diagram
```

### Saga Compensation -- Rollback in Reverse Order

```
  HAPPY PATH (all steps succeed):
  ================================

  SagaOrchestrator     InventoryService    PaymentService    ShippingService
       |                     |                  |                  |
       | (1) reserve         |                  |                  |
       |  inventory          |                  |                  |
       |-------------------->|                  |                  |
       |  OK                 |                  |                  |
       |<--------------------|                  |                  |
       |                     |                  |                  |
       | (2) charge          |                  |                  |
       |  payment            |                  |                  |
       |---------------------------------------->|                  |
       |  OK                 |                  |                  |
       |<----------------------------------------|                  |
       |                     |                  |                  |
       | (3) arrange         |                  |                  |
       |  shipping           |                  |                  |
       |---------------------------------------------------------->|
       |  OK                 |                  |                  |
       |<----------------------------------------------------------|
       |                     |                  |                  |
       |  SagaResult:        |                  |                  |
       |  SUCCESS            |                  |                  |


  FAILURE PATH (payment fails at step 2):
  =========================================

  SagaOrchestrator     InventoryService    PaymentService    ShippingService
       |                     |                  |                  |
       | (1) reserve         |                  |                  |
       |  inventory          |                  |                  |
       |-------------------->|                  |                  |
       |  OK                 |                  |                  |
       |<--------------------|                  |                  |
       |                     |                  |                  |
       | (2) charge          |                  |                  |
       |  payment            |                  |                  |
       |---------------------------------------->|                  |
       |  FAILED!            |                  |                  |
       |  (card declined)    |                  |                  |
       |<----------------------------------------|                  |
       |                     |                  |                  |
       |  COMPENSATION       |                  |                  |
       |  (reverse order)    |                  |                  |
       |                     |                  |                  |
       | (3) COMPENSATE:     |                  |                  |
       |  release inventory  |                  |                  |
       |  (undo step 1)      |                  |                  |
       |-------------------->|                  |                  |
       |  released           |                  |                  |
       |<--------------------|                  |                  |
       |                     |                  |                  |
       |  SagaResult:        |                  |                  |
       |  FAILED             |                  |                  |
       |  (reason: card      |                  |                  |
       |   declined,         |                  |                  |
       |   compensated:      |                  |                  |
       |   inventory         |                  |                  |
       |   released)         |                  |                  |
```

### Ugly Code -- Without Saga

```java
// ANTI-PATTERN: Manual try-catch compensation -- easy to miss steps
public class OrderService {

    public Order placeOrder(String userId, Cart cart) {
        boolean inventoryReserved = false;
        boolean paymentCharged = false;

        try {
            // Step 1: Reserve inventory
            for (CartItem item : cart.getItems()) {
                inventoryService.reserve(item.getProductId(), item.getQuantity());
            }
            inventoryReserved = true;

            // Step 2: Charge payment
            paymentService.charge(cart.getTotal(), userId);
            paymentCharged = true;

            // Step 3: Create shipping
            shippingService.createShipment(userId, cart);

            return createOrder(userId, cart);

        } catch (Exception e) {
            // Manual compensation -- FRAGILE!
            if (paymentCharged) {
                try {
                    paymentService.refund(cart.getTotal(), userId);
                } catch (Exception refundEx) {
                    // Refund failed! Now what? Log and pray?
                    logger.error("CRITICAL: Refund failed for user " + userId, refundEx);
                    // Money charged but order failed = unhappy customer
                }
            }
            if (inventoryReserved) {
                try {
                    for (CartItem item : cart.getItems()) {
                        inventoryService.release(item.getProductId(), item.getQuantity());
                    }
                } catch (Exception releaseEx) {
                    // Release failed! Inventory stuck as reserved.
                    logger.error("CRITICAL: Inventory release failed", releaseEx);
                }
            }
            throw new OrderFailedException("Order failed: " + e.getMessage(), e);
        }
    }
}
```

**Problems with this approach:**
- Compensation logic is ad-hoc, buried in catch blocks
- Easy to forget a compensation step when adding new steps
- Compensation failures are silently swallowed (log and pray)
- No record of what was compensated
- Adding a new step means carefully threading more boolean flags and try-catch blocks
- Compensation order is manually maintained (easy to get wrong)

### Clean Code -- With Saga Orchestrator

```java
// SagaStep -- a command object with execute and compensate
public interface SagaStep {
    String getName();
    boolean execute(Order order);
    void compensate(Order order);
}

// Concrete step: Reserve Inventory
public class ReserveInventoryStep implements SagaStep {
    private final InventoryService inventoryService;

    public ReserveInventoryStep(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @Override
    public String getName() {
        return "Reserve Inventory";
    }

    @Override
    public boolean execute(Order order) {
        // (1) Reserve stock for each item in the order
        for (OrderItem item : order.getItems()) {
            boolean reserved = inventoryService.reserve(
                item.getProductId(), item.getQuantity());
            if (!reserved) {
                return false;  // out of stock
            }
        }
        return true;
    }

    @Override
    public void compensate(Order order) {
        // (1) Release reserved stock -- undo execute()
        for (OrderItem item : order.getItems()) {
            inventoryService.release(item.getProductId(), item.getQuantity());
        }
    }
}

// Concrete step: Charge Payment
public class ChargePaymentStep implements SagaStep {
    private final PaymentService paymentService;

    @Override
    public String getName() {
        return "Charge Payment";
    }

    @Override
    public boolean execute(Order order) {
        return paymentService.charge(order.getTotalAmount(), order.getUserId());
    }

    @Override
    public void compensate(Order order) {
        paymentService.refund(order.getTotalAmount(), order.getUserId());
    }
}

// The Orchestrator -- executes steps in order, compensates on failure
public class OrderSagaOrchestrator {
    private final List<SagaStep> steps;

    public OrderSagaOrchestrator(InventoryService inventoryService,
                                  PaymentService paymentService,
                                  NotificationService notificationService) {
        this.steps = List.of(
            new ReserveInventoryStep(inventoryService),
            new ChargePaymentStep(paymentService)
            // Add more steps here -- order matters!
        );
    }

    public SagaResult execute(Order order) {
        List<SagaStep> completedSteps = new ArrayList<>();

        // (1) Execute each step in order
        for (SagaStep step : steps) {
            boolean success = step.execute(order);

            if (success) {
                completedSteps.add(step);
            } else {
                // (2) Step failed! Compensate in REVERSE order
                compensate(completedSteps, order);

                return new SagaResult.Builder()
                    .success(false)
                    .failedStep(step.getName())
                    .completedSteps(completedSteps.stream()
                        .map(SagaStep::getName)
                        .collect(Collectors.toList()))
                    .build();
            }
        }

        // (3) All steps succeeded
        return new SagaResult.Builder()
            .success(true)
            .completedSteps(steps.stream()
                .map(SagaStep::getName)
                .collect(Collectors.toList()))
            .build();
    }

    private void compensate(List<SagaStep> completedSteps, Order order) {
        // Compensate in REVERSE order -- undo the most recent step first
        List<SagaStep> reversed = new ArrayList<>(completedSteps);
        Collections.reverse(reversed);

        for (SagaStep step : reversed) {
            try {
                step.compensate(order);
            } catch (Exception e) {
                // Log compensation failure -- in production, retry or dead-letter queue
                System.err.printf("WARN: Compensation failed for step '%s': %s%n",
                    step.getName(), e.getMessage());
            }
        }
    }
}
```

### SagaResult -- Built with Builder

```java
public class SagaResult {
    private final boolean success;
    private final String failedStep;
    private final String failureReason;
    private final List<String> completedSteps;
    private final List<String> compensatedSteps;

    private SagaResult(Builder builder) {
        this.success = builder.success;
        this.failedStep = builder.failedStep;
        this.failureReason = builder.failureReason;
        this.completedSteps = builder.completedSteps;
        this.compensatedSteps = builder.compensatedSteps;
    }

    public static class Builder {
        private boolean success;
        private String failedStep;
        private String failureReason;
        private List<String> completedSteps = new ArrayList<>();
        private List<String> compensatedSteps = new ArrayList<>();

        public Builder success(boolean success) { this.success = success; return this; }
        public Builder failedStep(String step) { this.failedStep = step; return this; }
        public Builder failureReason(String reason) { this.failureReason = reason; return this; }
        public Builder completedSteps(List<String> steps) { this.completedSteps = steps; return this; }
        public Builder compensatedSteps(List<String> steps) { this.compensatedSteps = steps; return this; }

        public SagaResult build() { return new SagaResult(this); }
    }

    // Getters...
    public boolean isSuccess() { return success; }
    public String getFailedStep() { return failedStep; }
}
```

### Numbered Call Chain -- Saga with Payment Failure

```
  OrderService    SagaOrchestrator    ReserveInvStep    ChargePayStep    InventoryService
      |                 |                  |                 |                  |
      | (1) execute     |                  |                 |                  |
      |  (order)        |                  |                 |                  |
      |---------------->|                  |                 |                  |
      |                 |                  |                 |                  |
      |                 | (2) step[0]      |                 |                  |
      |                 |  .execute(order) |                 |                  |
      |                 |----------------->|                 |                  |
      |                 |                  |                 |                  |
      |                 |                  | (3) inventory   |                  |
      |                 |                  |  Service.reserve|                  |
      |                 |                  |  (prodId, qty)  |                  |
      |                 |                  |---------------------------------->|
      |                 |                  |  true           |                  |
      |                 |                  |<----------------------------------|
      |                 |  true            |                 |                  |
      |                 |<-----------------|                 |                  |
      |                 |                  |                 |                  |
      |                 | add to           |                 |                  |
      |                 | completedSteps   |                 |                  |
      |                 |                  |                 |                  |
      |                 | (4) step[1]      |                 |                  |
      |                 |  .execute(order) |                 |                  |
      |                 |-------------------------------------->|                  |
      |                 |                  |                 |                  |
      |                 |                  |  (5) payment    |                  |
      |                 |                  |  Service.charge |                  |
      |                 |                  |  FAILS! (card   |                  |
      |                 |                  |  declined)      |                  |
      |                 |  false           |                 |                  |
      |                 |<--------------------------------------|                  |
      |                 |                  |                 |                  |
      |                 | COMPENSATION     |                 |                  |
      |                 | (reverse order)  |                 |                  |
      |                 |                  |                 |                  |
      |                 | (6) compensate   |                 |                  |
      |                 |  completedSteps  |                 |                  |
      |                 |  [ReserveInv]    |                 |                  |
      |                 |                  |                 |                  |
      |                 | (7) ReserveInv   |                 |                  |
      |                 |  .compensate     |                 |                  |
      |                 |  (order)         |                 |                  |
      |                 |----------------->|                 |                  |
      |                 |                  | (8) inventory   |                  |
      |                 |                  |  Service.release|                  |
      |                 |                  |  (prodId, qty)  |                  |
      |                 |                  |---------------------------------->|
      |                 |                  |  released       |                  |
      |                 |                  |<----------------------------------|
      |                 |<-----------------|                 |                  |
      |                 |                  |                 |                  |
      |  SagaResult     |                  |                 |                  |
      |  (FAILED,       |                  |                 |                  |
      |   step: Payment,|                  |                 |                  |
      |   compensated:  |                  |                 |                  |
      |   [Inventory])  |                  |                 |                  |
      |<----------------|                  |                 |                  |
```

### Interview One-Liner

> "We use the Saga pattern with orchestration because 2PC doesn't work in microservices (synchronous locks at Amazon scale = impossible). The OrderSagaOrchestrator runs steps in order -- reserve inventory, charge payment. If any step fails, it compensates in reverse order. Each SagaStep is a Command object with execute() and compensate(). The result is eventual consistency without distributed locks."

### Cross-Reference
- **Command** (Pattern 10) -- Each `SagaStep` is a Command with execute + compensate
- **Facade** (Pattern 5) -- `OrderService` delegates to saga orchestrator
- **State** (Pattern 7) -- Saga drives order state transitions
- **Builder** (Pattern 2) -- `SagaResult.Builder` collects step results

---

## 10. Command Pattern

### What

Encapsulate a request as an object, thereby letting you parameterize clients with different requests, queue requests, and support undoable operations. In this project, each `SagaStep` is a Command -- it has `execute()` (do the action) and `compensate()` (undo the action).

### ASCII Diagram

```
  +---------------------------+
  | <<interface>>             |
  | SagaStep (Command)        |
  +---------------------------+
  | + getName(): String       |
  | + execute(order): boolean |  <-- "do"
  | + compensate(order): void |  <-- "undo"
  +-------------+-------------+
                |
        +-------+----------+
        |                  |
  +-----+--------+   +----+---------+
  | ReserveInv   |   | ChargePayment|
  | entoryStep   |   | Step         |
  +--------------+   +--------------+
  | execute:     |   | execute:     |
  |  reserve()   |   |  charge()    |
  | compensate:  |   | compensate:  |
  |  release()   |   |  refund()    |
  +--------------+   +--------------+

  The SagaOrchestrator is the INVOKER.
  It doesn't know WHAT each step does -- it just calls execute/compensate.
```

### Ugly Code -- Without Command

```java
// ANTI-PATTERN: Hard-coded step logic in the orchestrator
public class OrderSagaOrchestrator {

    public SagaResult execute(Order order) {
        // Every step's logic is inline -- no abstraction, no reuse
        // Adding a new step = edit this 200-line method

        // Step 1 inline
        boolean invReserved = false;
        for (OrderItem item : order.getItems()) {
            if (!inventoryService.reserve(item.getProductId(), item.getQuantity())) {
                return SagaResult.failed("Inventory");
            }
        }
        invReserved = true;

        // Step 2 inline
        boolean paymentCharged = false;
        if (!paymentService.charge(order.getTotalAmount(), order.getUserId())) {
            // Compensate step 1 -- inline!
            for (OrderItem item : order.getItems()) {
                inventoryService.release(item.getProductId(), item.getQuantity());
            }
            return SagaResult.failed("Payment");
        }
        paymentCharged = true;

        // Step 3 inline... Step 4 inline... compensation logic duplicated everywhere
    }
}
```

**Problems with this approach:**
- Cannot add/remove/reorder saga steps without editing the orchestrator
- Compensation logic is duplicated (release inventory appears in every later step's catch block)
- Cannot test individual steps in isolation
- Cannot reuse steps across different sagas (e.g., return saga, cancel saga)

### Clean Code -- With Command (SagaStep)

```java
// Already shown in Saga pattern -- the key point:
// SagaStep IS the Command pattern.
// The orchestrator loops through steps, calling execute/compensate.
// Adding a new step = implement SagaStep, add to the list.

// Example: Adding a FraudCheckStep to the saga
public class FraudCheckStep implements SagaStep {
    private final FraudService fraudService;

    @Override
    public String getName() { return "Fraud Check"; }

    @Override
    public boolean execute(Order order) {
        return fraudService.checkOrder(order);  // returns false if suspicious
    }

    @Override
    public void compensate(Order order) {
        fraudService.clearFlag(order.getOrderId());  // undo fraud flag
    }
}

// Adding to saga -- ONE line change in the orchestrator
this.steps = List.of(
    new FraudCheckStep(fraudService),         // NEW! Added without touching other steps
    new ReserveInventoryStep(inventoryService),
    new ChargePaymentStep(paymentService)
);
```

### Interview One-Liner

> "Each SagaStep is a Command object with execute() and compensate(). The orchestrator iterates through commands without knowing their internals. Adding a fraud check step is one line -- implement SagaStep, add to the list. Commands are also key for logging: we record which commands executed and which were compensated."

### Cross-Reference
- **Saga** (Pattern 9) -- SagaStep IS the command; saga orchestrator is the invoker
- **Strategy** (Pattern 1) -- Like Strategy, Command encapsulates behavior behind an interface. Difference: Strategy swaps algorithms for the same operation; Command encapsulates different operations (reserve vs charge vs ship)

---

## Pattern Interaction Map

```
  +------------------------------------------------------------------+
  |                  HOW ALL 10 PATTERNS FIT TOGETHER                 |
  +------------------------------------------------------------------+
  |                                                                    |
  |  FACTORY (AppConfig)                                               |
  |     |                                                              |
  |     |--- creates ---> REPOSITORIES (5x)                            |
  |     |--- creates ---> STRATEGIES (3x: pricing, payment, shipping)  |
  |     |--- creates ---> SAGA ORCHESTRATOR (with COMMAND steps)       |
  |     |--- creates ---> SERVICES (with OBSERVER listeners)           |
  |     |--- creates ---> FACADE (OrderService, wired with everything) |
  |                                                                    |
  |  CLIENT calls FACADE.placeOrder()                                  |
  |     |                                                              |
  |     +---> STRATEGY (pricing) --- DECORATOR wraps base strategy     |
  |     |                                                              |
  |     +---> BUILDER creates Order with initial STATE (CREATED)       |
  |     |                                                              |
  |     +---> SAGA orchestrator runs COMMAND steps:                    |
  |     |         Step 1: Reserve inventory (REPOSITORY)               |
  |     |         Step 2: Charge payment (STRATEGY: payment)           |
  |     |         On failure: compensate in reverse (COMMAND.undo)     |
  |     |         Each step: STATE transition on Order                 |
  |     |                                                              |
  |     +---> OBSERVER notified of state changes (notifications)       |
  |     |                                                              |
  |     +---> STRATEGY (shipping) calculates cost                      |
  |                                                                    |
  +------------------------------------------------------------------+
```

---

## Quick Reference -- Interview Cheat Sheet

| Question | Answer |
|----------|--------|
| "How do you handle distributed transactions?" | Saga with orchestration. Each step has execute + compensate. On failure, compensate in reverse order. |
| "Why not use 2PC?" | Synchronous locks don't scale. Any participant down = everything blocks. NoSQL databases don't support it. |
| "How do you prevent overselling?" | InventoryRepository.reserve() is synchronized. Saga compensates (releases) if payment fails. |
| "How do you add a new payment method?" | Implement PaymentStrategy interface. Wire in AppConfig. Zero changes to OrderService. |
| "How is pricing extensible?" | Decorator pattern: DiscountPricingStrategy wraps StandardPricingStrategy. Stack decorators for combo deals. |
| "How do you handle notification?" | Observer pattern: NotificationService listens for order events. Adding push notifications = new listener, no changes to OrderService. |
| "How do you manage order state?" | State pattern with enum transition map. Invalid transitions throw exception. Saga drives transitions. |
| "How do you test this?" | Repository pattern: swap InMemory for mock. Strategy pattern: inject test strategies. Factory: create test AppConfig. |
| "What if compensation fails?" | Log to dead-letter queue. Retry with exponential backoff. Alert ops team. Eventually consistent. |
| "Choreography vs Orchestration?" | We chose orchestration: central view of saga state, easier compensation flow, simpler debugging. Choreography = no SPOF but harder to reason about. |
