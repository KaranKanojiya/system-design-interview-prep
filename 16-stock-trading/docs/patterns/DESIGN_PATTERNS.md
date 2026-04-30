# Design Patterns -- Stock Trading Platform (Zerodha / Upstox)

> Quick reference for system design interviews. Each pattern includes the ugly
> anti-pattern first, then the clean solution, numbered call chain, ASCII diagram,
> and a one-liner you can drop in an interview.
>
> **Domain:** Online stock trading platform where users place orders (market, limit),
> orders pass risk checks, get matched in an order book, trades settle, positions
> update, and notifications fire. Three Strategy interfaces
> (OrderExecutionStrategy, RiskCheckStrategy, PnLStrategy) make this a
> strategy-heavy project. The matching engine with its TreeMap-based order book
> is THE core data structure -- interviewers will ask you to walk through the
> price-time priority matching flow.
>
> **This is Project 16 of 16 in the system design series.**

---

## Table of Contents

| # | Pattern | GoF Category | Primary Use |
|---|---------|-------------|-------------|
| 1 | Strategy (x3) | Behavioral | OrderExecutionStrategy (Market, Limit), RiskCheckStrategy (Margin, PositionLimit, CircuitBreaker), PnLStrategy (FIFO, AvgCost) |
| 2 | Builder | Creational | Order.Builder with symbol, side, quantity, price, immutable result |
| 3 | Factory | Creational | AppConfig wires strategies, repos, services |
| 4 | Repository (x6) | Structural (enterprise) | OrderRepository, TradeRepository, PositionRepository, AccountRepository, StockRepository, MarketDataRepository |
| 5 | Facade | Structural | TradingService orchestrates order -> risk -> match -> settle -> notify |
| 6 | Observer | Behavioral | NotificationService + MarketDataService observe trade events |
| 7 | State | Behavioral | Order state machine (PENDING_RISK -> OPEN -> PARTIALLY_FILLED -> FILLED -> SETTLED) |
| 8 | Chain of Responsibility | Behavioral | RiskService chains MarginCheck -> PositionLimit -> CircuitBreaker |
| 9 | Command | Behavioral | Order as command object (place / cancel / modify) |
| 10 | Singleton | Creational | MatchingEngine (one per symbol, managed centrally via ConcurrentHashMap) |

---

## 1. Strategy Pattern (Behavioral) -- THE KEY PATTERN

**Three independent Strategy interfaces** power the core of this system.
This is the pattern interviewers care about most -- it directly answers
"How do you execute different order types?", "How do you run risk checks?",
and "How do you calculate P&L?"

### Strategy Interface A: OrderExecutionStrategy

Determines **how** an order is matched against the order book.

```java
public interface OrderExecutionStrategy {
    /**
     * Execute an order against the order book for a given symbol.
     *
     * @param order     the order to execute (market, limit, etc.)
     * @param orderBook the order book for the symbol
     * @return          list of trades generated (empty if no match)
     */
    List<Trade> execute(Order order, OrderBook orderBook);
}
```

Two concrete strategies:

| Strategy | Algorithm | Use Case |
|----------|-----------|----------|
| MarketOrderStrategy | Match immediately at best available price, sweep levels until filled | "Buy RELIANCE at whatever price" -- speed over price |
| LimitOrderStrategy | Match only at limit price or better; if unfilled, rest in order book | "Buy RELIANCE at max 2500" -- price control, may not fill |

### Strategy Interface B: RiskCheckStrategy

Determines **which** risk validation to apply before an order enters the matching engine.

```java
public interface RiskCheckStrategy {
    /**
     * Validate an order against a specific risk rule.
     *
     * @param order   the order to validate
     * @param account the user's trading account (for margin, positions)
     * @param stock   the stock being traded (for circuit limits)
     * @return        RiskResult with pass/fail and reason
     */
    RiskResult check(Order order, Account account, Stock stock);
}
```

Three concrete strategies:

| Strategy | What It Checks | Rejection Reason |
|----------|---------------|-----------------|
| MarginCheckStrategy | account.availableMargin >= order.value + brokerage | "Insufficient margin: need 50000, have 30000" |
| PositionLimitStrategy | totalPositionQty + orderQty <= maxPositionLimit | "Position limit exceeded: max 10000 shares" |
| CircuitBreakerStrategy | order.price within [lowerCircuit, upperCircuit] | "Price 3000 outside circuit [2200-2800]" |

### Strategy Interface C: PnLStrategy

Determines **how** profit and loss is calculated when a position is partially or fully closed.

```java
public interface PnLStrategy {
    /**
     * Calculate realized P&L when a trade reduces an existing position.
     *
     * @param position the current position being reduced
     * @param trade    the closing trade
     * @return         realized P&L amount (positive = profit, negative = loss)
     */
    double calculateRealizedPnL(Position position, Trade trade);
}
```

Two concrete strategies:

| Strategy | Algorithm | Trade-off |
|----------|-----------|-----------|
| FifoPnLStrategy | First-In-First-Out: oldest lots closed first | Standard for tax in many jurisdictions, more complex to track |
| AverageCostPnLStrategy | Average all buy prices, use as cost basis | Simpler, used by most Indian brokers (Zerodha uses avg cost) |

### Ugly Anti-Pattern -- Hardcoded Everything

```java
// UGLY: Order type, risk checks, and P&L all hardcoded.
// Adding a stop-loss order type? Edit the monster if-else.
// Adding a new risk rule? Good luck finding all the places.

public class UglyTradingService {

    private final Map<String, List<Order>> orderBooks = new HashMap<>();
    private final Map<String, Double> balances = new HashMap<>();

    public String placeOrder(String userId, String symbol, String side,
                             String orderType, int qty, double price) {

        // Risk check #1: margin (hardcoded)
        double balance = balances.getOrDefault(userId, 0.0);
        double orderValue = qty * price;
        if (balance < orderValue) {
            return "REJECTED: insufficient balance";
        }

        // Risk check #2: circuit breaker (hardcoded)
        if (price > 3000 || price < 1000) {  // Magic numbers!
            return "REJECTED: price outside circuit";
        }

        // No position limit check -- forgot to add it

        // Order execution (hardcoded for market and limit)
        if ("MARKET".equals(orderType)) {
            // Sweep the entire book -- all inline
            List<Order> book = orderBooks.get(symbol);
            if (book != null) {
                for (Order o : book) {
                    // Match logic inline... 50 lines of matching
                }
            }
        } else if ("LIMIT".equals(orderType)) {
            // Different matching logic inline... another 50 lines
        } else if ("STOP_LOSS".equals(orderType)) {
            // Yet another inline block... growing forever
        }

        // P&L calculation (hardcoded FIFO)
        // 30 lines of FIFO logic inline
        // Want average cost? Copy-paste and modify

        // Notification (hardcoded)
        System.out.println("SMS: Order placed for " + userId);
        System.out.println("Email: Order placed for " + userId);
        // Want to add push notification? Edit this method

        return "OK";
    }

    // Adding a new order type = editing placeOrder()
    // Adding a new risk check = editing placeOrder()
    // Adding a new P&L method = editing placeOrder()
    // Adding a new notification channel = editing placeOrder()
    // Every change risks breaking existing logic
}
```

**Problems:**
1. Order execution logic hardcoded inline -- adding stop-loss means editing the monolith
2. Risk checks hardcoded with magic numbers -- no circuit limit from Stock object
3. P&L calculation hardcoded as FIFO -- cannot switch to average cost
4. Notification channels hardcoded -- cannot add push without editing placeOrder
5. No order state machine -- order goes from "placed" to... what?
6. No order book data structure -- just a flat list
7. Testing requires the full service -- no strategy injection

### Clean Solution -- Three Strategy Interfaces

```java
// CLEAN: OrderExecutionStrategy, RiskCheckStrategy, and PnLStrategy
// are all injected. Each can be swapped, tested, and evolved independently.
// TradingService is a thin Facade that orchestrates the flow.

public class CleanTradingService {

    private final OrderExecutionStrategy   executionStrategy;
    private final List<RiskCheckStrategy>  riskChecks;  // Chain of Responsibility
    private final PnLStrategy              pnlStrategy;
    private final MatchingEngine           matchingEngine;
    private final OrderRepository          orderRepository;
    private final TradeRepository          tradeRepository;
    private final PositionRepository       positionRepository;
    private final AccountRepository        accountRepository;
    private final StockRepository          stockRepository;
    private final NotificationService      notificationService;  // Observer

    public CleanTradingService(OrderExecutionStrategy executionStrategy,
                               List<RiskCheckStrategy> riskChecks,
                               PnLStrategy pnlStrategy,
                               MatchingEngine matchingEngine,
                               OrderRepository orderRepository,
                               TradeRepository tradeRepository,
                               PositionRepository positionRepository,
                               AccountRepository accountRepository,
                               StockRepository stockRepository,
                               NotificationService notificationService) {
        this.executionStrategy   = executionStrategy;
        this.riskChecks          = riskChecks;
        this.pnlStrategy         = pnlStrategy;
        this.matchingEngine      = matchingEngine;
        this.orderRepository     = orderRepository;
        this.tradeRepository     = tradeRepository;
        this.positionRepository  = positionRepository;
        this.accountRepository   = accountRepository;
        this.stockRepository     = stockRepository;
        this.notificationService = notificationService;
    }

    public OrderResult placeOrder(Order order) {
        // 1. Validate stock exists and is tradeable
        Stock stock = stockRepository.findBySymbol(order.getSymbol())
            .orElseThrow(() -> new IllegalArgumentException("Unknown symbol"));
        if (stock.isTradingHalted()) {
            return OrderResult.rejected("Trading halted for " + order.getSymbol());
        }

        // 2. Load account
        Account account = accountRepository.findByUserId(order.getUserId())
            .orElseThrow(() -> new IllegalArgumentException("Unknown account"));

        // 3. Run ALL risk checks (Chain of Responsibility)
        for (RiskCheckStrategy riskCheck : riskChecks) {
            RiskResult result = riskCheck.check(order, account, stock);
            if (!result.isPassed()) {
                order.reject(result.getReason());
                orderRepository.save(order);
                return OrderResult.rejected(result.getReason());
            }
        }

        // 4. Order passes risk -- transition to OPEN state
        order.open();
        orderRepository.save(order);

        // 5. Execute via matching engine (Strategy A)
        OrderBook orderBook = matchingEngine.getOrderBook(order.getSymbol());
        List<Trade> trades = executionStrategy.execute(order, orderBook);

        // 6. Process each trade
        for (Trade trade : trades) {
            tradeRepository.save(trade);

            // 7. Update positions and calculate P&L (Strategy C)
            Position position = positionRepository.findOrCreate(
                order.getUserId(), order.getSymbol());
            double pnl = pnlStrategy.calculateRealizedPnL(position, trade);
            position.applyTrade(trade, pnl);
            positionRepository.save(position);

            // 8. Update account balance
            account.settleTradeAmount(trade);
            accountRepository.save(account);
        }

        // 9. Update order state (State pattern)
        if (order.getRemainingQuantity() == 0) {
            order.fill();
        } else if (!trades.isEmpty()) {
            order.partiallyFill();
        }
        orderRepository.save(order);

        // 10. Notify user (Observer)
        notificationService.onOrderUpdate(order, trades);

        return OrderResult.success(order, trades);
    }
}
```

### ASCII Diagram -- Three Strategy Axes

```
  OrderExecutionStrategy         RiskCheckStrategy              PnLStrategy
 (how orders are matched)      (what risk rules apply)       (how P&L is computed)
        |                            |                            |
  +-----+------+          +---------+---------+            +-----+------+
  |            |           |         |         |            |            |
  Market     Limit      Margin  PositionLmt CircuitBrkr   FIFO      AvgCost
 (sweep      (match     (enough  (not over   (price in    (oldest    (weighted
  all        at limit    margin   max         circuit      lots       average
  levels,    or better,  to       shares?)    band?)       first)     of all
  immediate) rest in     cover?)                                      buys)
             book)
```

### Numbered Call Chain -- User Places a Limit Buy Order

```
1.  User clicks "Buy 100 RELIANCE at 2500 Limit" on Kite/Upstox app
2.  API gateway receives POST /orders with order details
3.  TradingService.placeOrder(order) called -- order state = CREATED
4.  StockRepository.findBySymbol("RELIANCE") -> returns Stock with circuit [2200-2800]
5.  Stock.isTradingHalted() = false -> proceed
6.  AccountRepository.findByUserId("user-1") -> Account with margin 300000
7.  RiskCheckStrategy #1: MarginCheckStrategy.check() -> 100 * 2500 = 250000 <= 300000 -> PASS
8.  RiskCheckStrategy #2: PositionLimitStrategy.check() -> current 500 + 100 = 600 <= 10000 -> PASS
9.  RiskCheckStrategy #3: CircuitBreakerStrategy.check() -> 2500 in [2200, 2800] -> PASS
10. All risk checks pass -> order.open() -> state = OPEN
11. OrderRepository.save(order) -- persists with OPEN state
12. MatchingEngine.getOrderBook("RELIANCE") -> returns TreeMap-based OrderBook
13. LimitOrderStrategy.execute(order, orderBook) -> check asks at 2500 or below
14. OrderBook has ask at 2490 for 60 shares -> MATCH! Trade #1: 60 @ 2490
15. OrderBook has ask at 2500 for 50 shares -> MATCH! Trade #2: 40 @ 2500 (only need 40 more)
16. 50-share ask becomes 10-share resting order at 2500
17. TradeRepository.save(trade1), TradeRepository.save(trade2)
18. PositionRepository.findOrCreate("user-1", "RELIANCE") -> Position(qty=500, avgCost=2450)
19. AverageCostPnLStrategy: no realized P&L (buying, not selling)
20. Position updated: qty = 600, avgCost recalculated
21. AccountRepository: debit margin for 60*2490 + 40*2500 = 249400 + brokerage
22. Order filled 100/100 -> order.fill() -> state = FILLED
23. NotificationService.onOrderUpdate() -> SMS + email + push notification
```

### Numbered Call Chain -- Market Sell with P&L Calculation

```
1.  User clicks "Sell 200 TCS at Market" on trading app
2.  TradingService.placeOrder(order) called -- order state = CREATED
3.  StockRepository.findBySymbol("TCS") -> Stock with circuit [3500-4200]
4.  AccountRepository.findByUserId("user-1") -> Account
5.  MarginCheckStrategy: sell order, sufficient shares in position -> PASS
6.  PositionLimitStrategy: reducing position -> PASS
7.  CircuitBreakerStrategy: market order, no price to check -> PASS (deferred to execution)
8.  Order.open() -> state = OPEN
9.  MarketOrderStrategy.execute(order, orderBook) -> sweep bid side
10. OrderBook bids: 200 @ 3900, 150 @ 3895, 100 @ 3890
11. Trade #1: 200 @ 3900 (fully filled from top of book)
12. Position was: qty=500, avgCost=3800
13. FifoPnLStrategy: oldest 200 shares had cost basis 3750
14.   Realized P&L = (3900 - 3750) * 200 = +30,000
15. Position updated: qty=300, lots adjusted
16. Account credited: 200 * 3900 = 780000 (minus brokerage + taxes)
17. Order fully filled -> state = FILLED
18. NotificationService: "Sold 200 TCS @ 3900, P&L: +30,000"
```

### Interview One-Liner

> "We inject three strategies -- OrderExecutionStrategy picks market vs. limit
> for matching, RiskCheckStrategy picks margin vs. position-limit vs. circuit-breaker
> for validation, and PnLStrategy picks FIFO vs. average-cost for P&L calculation.
> The Facade (TradingService) orchestrates all three plus the matching engine,
> settlement, and notification."

**Cross-reference:**
- Facade orchestration: see Pattern 5
- Observer notification: see Pattern 6
- State machine: see Pattern 7
- Chain of Responsibility: see Pattern 8
- Builder for Order: see Pattern 2
- Matching Engine singleton: see Pattern 10

---

## 2. Builder Pattern (Creational) -- Order.Builder

An Order has many fields (orderId, userId, symbol, side, type, quantity, price,
status, timestamps). The Builder ensures immutability and readable construction.

### Ugly Anti-Pattern -- Telescoping Constructor

```java
// UGLY: 11-parameter constructor. Which int is quantity and which is filled?
// Which double is price and which is triggerPrice?

public class UglyOrder {
    private String orderId;
    private String userId;
    private String symbol;
    private String side;       // "BUY" or "SELL"
    private String orderType;  // "MARKET" or "LIMIT"
    private int quantity;
    private int filledQuantity;
    private double price;
    private double triggerPrice;
    private String status;
    private long createdAt;

    public UglyOrder(String orderId, String userId, String symbol,
                     String side, String orderType, int quantity,
                     int filledQuantity, double price, double triggerPrice,
                     String status, long createdAt) {
        // Did you swap quantity and filledQuantity? Both are int.
        // Did you swap price and triggerPrice? Both are double.
        this.orderId = orderId;
        this.userId = userId;
        this.symbol = symbol;
        this.side = side;
        this.orderType = orderType;
        this.quantity = quantity;
        this.filledQuantity = filledQuantity;
        this.price = price;
        this.triggerPrice = triggerPrice;
        this.status = status;
        this.createdAt = createdAt;
    }

    // All fields mutable -- anyone can set filledQuantity to negative
    public void setStatus(String status) { this.status = status; }
    public void setFilledQuantity(int qty) { this.filledQuantity = qty; }
}
```

**Problems:**
1. 11-parameter constructor -- easy to swap int/double arguments silently
2. Status is a raw String -- "OPENN" compiles but is a bug
3. All fields mutable -- external code can set filledQuantity > quantity
4. No validation -- can create order with quantity = -5
5. No state machine -- status transitions are unchecked

### Clean Solution -- Builder with Immutable Result + State Machine

```java
// CLEAN: Builder pattern for construction, State pattern for transitions.

public class Order {
    private final String orderId;
    private final String userId;
    private final String symbol;
    private final Side side;            // enum: BUY, SELL
    private final OrderType orderType;  // enum: MARKET, LIMIT, STOP_LOSS
    private final int quantity;
    private final double price;         // 0 for market orders
    private final double triggerPrice;  // 0 if not stop-loss
    private final long createdAt;

    // Mutable state -- controlled by state machine methods
    private OrderStatus status;         // enum: CREATED, PENDING_RISK, OPEN, ...
    private int filledQuantity;

    private Order(Builder builder) {
        this.orderId       = builder.orderId;
        this.userId        = builder.userId;
        this.symbol        = builder.symbol;
        this.side          = builder.side;
        this.orderType     = builder.orderType;
        this.quantity      = builder.quantity;
        this.price         = builder.price;
        this.triggerPrice  = builder.triggerPrice;
        this.createdAt     = builder.createdAt;
        this.status        = OrderStatus.CREATED;
        this.filledQuantity = 0;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String orderId = UUID.randomUUID().toString();
        private String userId;
        private String symbol;
        private Side side;
        private OrderType orderType = OrderType.LIMIT;
        private int quantity;
        private double price;
        private double triggerPrice = 0;
        private long createdAt = System.currentTimeMillis();

        public Builder userId(String userId)       { this.userId = userId; return this; }
        public Builder symbol(String symbol)       { this.symbol = symbol; return this; }
        public Builder side(Side side)             { this.side = side; return this; }
        public Builder orderType(OrderType type)   { this.orderType = type; return this; }
        public Builder quantity(int quantity)       { this.quantity = quantity; return this; }
        public Builder price(double price)         { this.price = price; return this; }
        public Builder triggerPrice(double tp)     { this.triggerPrice = tp; return this; }

        public Order build() {
            Objects.requireNonNull(userId, "userId is required");
            Objects.requireNonNull(symbol, "symbol is required");
            Objects.requireNonNull(side, "side is required");
            if (quantity <= 0) throw new IllegalArgumentException("quantity must be > 0");
            if (orderType == OrderType.LIMIT && price <= 0) {
                throw new IllegalArgumentException("limit order requires price > 0");
            }
            return new Order(this);
        }
    }

    // State machine methods -- see Pattern 7 for full detail
    public void pendingRisk() { transition(OrderStatus.CREATED, OrderStatus.PENDING_RISK); }
    public void open()        { transition(OrderStatus.PENDING_RISK, OrderStatus.OPEN); }
    public void partiallyFill() { transition(OrderStatus.OPEN, OrderStatus.PARTIALLY_FILLED); }
    public void fill()        { /* OPEN or PARTIALLY_FILLED -> FILLED */ }
    public void reject(String reason) { this.status = OrderStatus.REJECTED; }
    public void cancel()      { /* OPEN or PARTIALLY_FILLED -> CANCELLED */ }

    private void transition(OrderStatus from, OrderStatus to) {
        if (this.status != from) {
            throw new IllegalStateException("Cannot transition from " + status + " to " + to);
        }
        this.status = to;
    }

    public int getRemainingQuantity() { return quantity - filledQuantity; }
}
```

### Numbered Call Chain -- Building an Order

```
1.  User submits "Buy 100 RELIANCE at 2500 Limit" via REST API
2.  Controller calls Order.builder() -- creates new Builder with UUID
3.  Calls .userId("user-1") -- sets user
4.  Calls .symbol("RELIANCE") -- sets symbol
5.  Calls .side(Side.BUY) -- sets direction
6.  Calls .orderType(OrderType.LIMIT) -- sets order type
7.  Calls .quantity(100) -- sets quantity
8.  Calls .price(2500.0) -- sets limit price
9.  Calls .build() -- validates all required fields and constraints
10. Builder creates Order with status = CREATED, filledQuantity = 0
11. Order passed to TradingService.placeOrder(order)
```

### Interview One-Liner

> "Order has 11+ fields so we use Builder for fluent construction with enum types
> for side/orderType/status. The result starts in CREATED state; only the
> state-machine methods can transition it -- no raw setStatus() exposed."

**Cross-reference:**
- State machine transitions: see Pattern 7
- Order used as Command: see Pattern 9
- Builder called before Facade entry: see Pattern 5

---

## 3. Factory Pattern (Creational) -- AppConfig

AppConfig is the central wiring point that creates all strategies, repositories,
and services. It decides which concrete strategies to use.

### Ugly Anti-Pattern -- Scattered `new` Calls

```java
// UGLY: Every class creates its own dependencies. Changing a strategy
// requires editing every class that uses it.

public class UglyMain {
    public static void main(String[] args) {
        // Risk check hardcoded in 3 different places
        var tradingService = new TradingService();
        var riskService = new RiskService();      // creates its own margin checker
        var matchingEngine = new MatchingEngine(); // creates its own strategies

        // Want to switch from FIFO to Average Cost P&L?
        // Find every class that does P&L -- 4 files to edit
        // Missed one? Bug in production.

        // Want to add a new risk check?
        // Edit TradingService, RiskService, and the test harness
    }
}
```

**Problems:**
1. Dependencies scattered -- no single place to see the wiring
2. Strategy changes require editing multiple files
3. Testing requires instantiating the entire system
4. No way to swap strategies for different markets (equity vs. derivatives)

### Clean Solution -- Central Factory

```java
// CLEAN: AppConfig is the ONLY place where concrete classes are instantiated.
// Every other class depends on interfaces.

public class AppConfig {

    // --- Repositories ---
    private final OrderRepository       orderRepository       = new InMemoryOrderRepository();
    private final TradeRepository       tradeRepository       = new InMemoryTradeRepository();
    private final PositionRepository    positionRepository    = new InMemoryPositionRepository();
    private final AccountRepository     accountRepository     = new InMemoryAccountRepository();
    private final StockRepository       stockRepository       = new InMemoryStockRepository();
    private final MarketDataRepository  marketDataRepository  = new InMemoryMarketDataRepository();

    // --- Strategies ---
    private final OrderExecutionStrategy marketStrategy = new MarketOrderStrategy();
    private final OrderExecutionStrategy limitStrategy  = new LimitOrderStrategy();
    private final PnLStrategy            pnlStrategy    = new AverageCostPnLStrategy();

    // --- Risk Checks (Chain of Responsibility) ---
    private final List<RiskCheckStrategy> riskChecks = List.of(
        new MarginCheckStrategy(),
        new PositionLimitStrategy(10_000),       // max 10k shares per symbol
        new CircuitBreakerStrategy(stockRepository)
    );

    // --- Matching Engine (Singleton per symbol) ---
    private final MatchingEngine matchingEngine = new MatchingEngine();

    // --- Services ---
    private final NotificationService notificationService = new NotificationService();
    private final MarketDataService   marketDataService   = new MarketDataService(marketDataRepository);

    private final TradingService tradingService = new TradingService(
        limitStrategy,       // default to limit; switch per order
        riskChecks,
        pnlStrategy,
        matchingEngine,
        orderRepository,
        tradeRepository,
        positionRepository,
        accountRepository,
        stockRepository,
        notificationService
    );

    // --- Getters ---
    public TradingService getTradingService()         { return tradingService; }
    public MatchingEngine getMatchingEngine()          { return matchingEngine; }
    public OrderRepository getOrderRepository()        { return orderRepository; }
    public AccountRepository getAccountRepository()    { return accountRepository; }
    public StockRepository getStockRepository()        { return stockRepository; }
    public MarketDataService getMarketDataService()    { return marketDataService; }

    // Seed initial data
    public void seedData() {
        stockRepository.save(new Stock("RELIANCE", "Reliance Industries",
            "NSE", 1, 0.05, 2800.0, 2200.0));
        stockRepository.save(new Stock("TCS", "Tata Consultancy Services",
            "NSE", 1, 0.05, 4200.0, 3500.0));
        stockRepository.save(new Stock("INFY", "Infosys",
            "NSE", 1, 0.05, 1800.0, 1400.0));

        accountRepository.save(new Account("user-1", "Karan", 500_000.0));
        accountRepository.save(new Account("user-2", "Priya", 300_000.0));
    }
}
```

### Numbered Call Chain -- Application Startup

```
1.  main() creates AppConfig
2.  AppConfig creates 6 InMemory repositories
3.  AppConfig creates MarketOrderStrategy and LimitOrderStrategy
4.  AppConfig creates AverageCostPnLStrategy
5.  AppConfig creates risk chain: [MarginCheck, PositionLimit, CircuitBreaker]
6.  AppConfig creates MatchingEngine (manages OrderBooks per symbol)
7.  AppConfig creates NotificationService and MarketDataService
8.  AppConfig creates TradingService, injecting all dependencies
9.  AppConfig.seedData() inserts stocks (RELIANCE, TCS, INFY) with circuit limits
10. AppConfig.seedData() inserts accounts (user-1, user-2) with margin
11. System ready -- TradingService.placeOrder() can be called
```

### Interview One-Liner

> "AppConfig is the only class that knows about concrete implementations.
> Swap AverageCostPnLStrategy for FifoPnLStrategy in one line; add a new
> risk check by adding one element to the riskChecks list."

**Cross-reference:**
- Strategies created here: see Pattern 1
- Risk chain assembled here: see Pattern 8
- MatchingEngine created here: see Pattern 10

---

## 4. Repository Pattern (Structural) -- Six Repositories

Six repositories abstract away data storage. In our simulation, they are
in-memory `ConcurrentHashMap`; in production, they map to PostgreSQL, Redis,
or a combination.

### Ugly Anti-Pattern -- Raw Maps Everywhere

```java
// UGLY: Every service accesses raw maps directly.
// No interface -- cannot swap InMemory for PostgreSQL.

public class UglyTradingApp {
    static Map<String, Order> orders = new HashMap<>();
    static Map<String, List<Trade>> trades = new HashMap<>();
    static Map<String, Double> balances = new HashMap<>();
    static Map<String, Integer> positions = new HashMap<>();

    public static void placeOrder(Order order) {
        // Direct map access -- no encapsulation
        orders.put(order.getId(), order);
        Double balance = balances.get(order.getUserId());
        // NPE if user not found -- no Optional
        if (balance < order.getValue()) {
            return;
        }
        // Thread safety? What thread safety?
    }
}
```

**Problems:**
1. No interface -- cannot swap storage backend
2. Raw maps exposed -- any code can corrupt data
3. No thread safety -- HashMap in concurrent environment
4. No Optional -- NPE on missing data
5. No query methods -- find by symbol, find by user, find by status all require inline filtering

### Clean Solution -- Repository Interface + InMemory Implementation

```java
// CLEAN: Interface for each aggregate root.
// InMemory implementation for simulation, JPA/JDBC for production.

public interface OrderRepository {
    void save(Order order);
    Optional<Order> findById(String orderId);
    List<Order> findByUserId(String userId);
    List<Order> findBySymbol(String symbol);
    List<Order> findByStatus(OrderStatus status);
    List<Order> findOpenOrdersByUserAndSymbol(String userId, String symbol);
}

public class InMemoryOrderRepository implements OrderRepository {
    private final Map<String, Order> store = new ConcurrentHashMap<>();

    @Override
    public void save(Order order) {
        store.put(order.getOrderId(), order);
    }

    @Override
    public Optional<Order> findById(String orderId) {
        return Optional.ofNullable(store.get(orderId));
    }

    @Override
    public List<Order> findByUserId(String userId) {
        return store.values().stream()
            .filter(o -> o.getUserId().equals(userId))
            .collect(Collectors.toList());
    }

    @Override
    public List<Order> findBySymbol(String symbol) {
        return store.values().stream()
            .filter(o -> o.getSymbol().equals(symbol))
            .collect(Collectors.toList());
    }

    @Override
    public List<Order> findByStatus(OrderStatus status) {
        return store.values().stream()
            .filter(o -> o.getStatus() == status)
            .collect(Collectors.toList());
    }

    @Override
    public List<Order> findOpenOrdersByUserAndSymbol(String userId, String symbol) {
        return store.values().stream()
            .filter(o -> o.getUserId().equals(userId)
                      && o.getSymbol().equals(symbol)
                      && (o.getStatus() == OrderStatus.OPEN
                          || o.getStatus() == OrderStatus.PARTIALLY_FILLED))
            .collect(Collectors.toList());
    }
}
```

### Repository Summary Table

| Repository | Key | Primary Queries | Production DB |
|-----------|-----|----------------|---------------|
| OrderRepository | orderId | byUserId, bySymbol, byStatus, openOrders | PostgreSQL (ACID) |
| TradeRepository | tradeId | byOrderId, bySymbol, byTimestamp | PostgreSQL (ACID) |
| PositionRepository | userId+symbol | byUserId, bySymbol, allOpen | PostgreSQL |
| AccountRepository | userId | byUserId | PostgreSQL (balance = ACID) |
| StockRepository | symbol | bySymbol, allActive, byExchange | PostgreSQL + Redis cache |
| MarketDataRepository | symbol | bySymbol (latest), bySymbolAndTimeRange | Redis + TimescaleDB |

### Numbered Call Chain -- Order Lifecycle Through Repositories

```
1.  TradingService receives Order -> OrderRepository.save(order) [status=CREATED]
2.  Risk checks pass -> order.open() -> OrderRepository.save(order) [status=OPEN]
3.  MatchingEngine generates Trade -> TradeRepository.save(trade)
4.  PositionRepository.findOrCreate(userId, symbol) -> Position
5.  Position updated with trade -> PositionRepository.save(position)
6.  AccountRepository.findByUserId(userId) -> Account
7.  Account debited/credited -> AccountRepository.save(account)
8.  Order fully filled -> order.fill() -> OrderRepository.save(order) [status=FILLED]
9.  MarketDataRepository updated with latest trade price
```

### Interview One-Liner

> "Six repositories behind interfaces. In-memory ConcurrentHashMap for the
> simulation; swap to JPA for PostgreSQL in production. OrderRepository and
> TradeRepository are ACID-critical; MarketDataRepository can be Redis."

**Cross-reference:**
- Repositories created in Factory: see Pattern 3
- Used throughout Facade: see Pattern 5
- StockRepository used by CircuitBreakerStrategy: see Pattern 1

---

## 5. Facade Pattern (Structural) -- TradingService

TradingService is the single entry point that orchestrates the entire
order lifecycle: validate -> risk check -> match -> settle -> notify.
Clients never interact with the matching engine, risk service, or
repositories directly.

### Ugly Anti-Pattern -- Client Orchestrates Everything

```java
// UGLY: The client (controller/main) manually calls every service
// in the right order. Miss a step? Bug. Reorder? Bug.

public class UglyController {
    public void handleOrder(OrderRequest req) {
        // Client must know the entire orchestration
        Order order = new Order(req);
        riskService.checkMargin(order);    // Step 1 -- what if this fails?
        riskService.checkPosition(order);  // Step 2 -- client remembers?
        riskService.checkCircuit(order);   // Step 3 -- easy to forget
        matchingEngine.match(order);       // Step 4
        settlementService.settle(order);   // Step 5 -- before or after notify?
        notificationService.notify(order); // Step 6

        // New developer adds a risk check -- must edit EVERY controller
        // Mobile app, web app, API -- 3 places to update
    }
}
```

### Clean Solution -- Facade Hides Complexity

```java
// CLEAN: TradingService is the Facade. One method call does everything.
// See Pattern 1 for the full placeOrder() implementation.

public class TradingService {  // THE FACADE

    // All 10 dependencies injected (see Pattern 3 for wiring)

    public OrderResult placeOrder(Order order)     { /* full flow */ }
    public OrderResult cancelOrder(String orderId) { /* cancel flow */ }
    public OrderResult modifyOrder(String orderId, int newQty, double newPrice) { /* modify flow */ }
    public List<Order> getOrderHistory(String userId) { /* query flow */ }
    public Position getPosition(String userId, String symbol) { /* query flow */ }
}

// Controller is now trivial:
public class CleanController {
    private final TradingService tradingService;

    public OrderResult handleOrder(OrderRequest req) {
        Order order = Order.builder()
            .userId(req.getUserId())
            .symbol(req.getSymbol())
            .side(req.getSide())
            .orderType(req.getOrderType())
            .quantity(req.getQuantity())
            .price(req.getPrice())
            .build();
        return tradingService.placeOrder(order);  // ONE call
    }
}
```

### ASCII Diagram -- Facade Orchestration Flow

```
  +-----------------------------------------------------------------------+
  |                    TradingService (FACADE)                             |
  +-----------------------------------------------------------------------+
  |                                                                       |
  |  Client                                                               |
  |    |                                                                  |
  |    | (1) placeOrder(order)                                            |
  |    |                                                                  |
  |    v                                                                  |
  |  +-------------------+                                                |
  |  | TradingService    |                                                |
  |  |                   |                                                |
  |  | (2) Validate      |--- StockRepository.findBySymbol()             |
  |  |     stock         |                                                |
  |  |                   |                                                |
  |  | (3) Load account  |--- AccountRepository.findByUserId()           |
  |  |                   |                                                |
  |  | (4) Risk checks   |--- RiskCheckStrategy chain                    |
  |  |     (CoR)         |    MarginCheck -> PositionLimit -> Circuit     |
  |  |                   |                                                |
  |  | (5) Match order   |--- MatchingEngine.getOrderBook()              |
  |  |                   |--- OrderExecutionStrategy.execute()            |
  |  |                   |                                                |
  |  | (6) Settle trades |--- PositionRepository + AccountRepository     |
  |  |                   |--- PnLStrategy.calculateRealizedPnL()         |
  |  |                   |                                                |
  |  | (7) Update state  |--- OrderRepository.save()                     |
  |  |                   |                                                |
  |  | (8) Notify        |--- NotificationService.onOrderUpdate()        |
  |  |                   |                                                |
  |  +-------------------+                                                |
  |    |                                                                  |
  |    v                                                                  |
  |  OrderResult (order + trades + status)                                |
  +-----------------------------------------------------------------------+
```

### Interview One-Liner

> "TradingService is the Facade -- one placeOrder() call orchestrates stock
> validation, risk checks (Chain of Responsibility), matching (Strategy),
> settlement (P&L Strategy), and notification (Observer). Clients never
> touch internals."

**Cross-reference:**
- Internal strategies: see Pattern 1
- Risk chain: see Pattern 8
- Observer notification: see Pattern 6
- Repositories: see Pattern 4

---

## 6. Observer Pattern (Behavioral) -- Trade Event Notifications

When a trade executes, multiple services need to react: notifications to the
user, market data updates, risk system updates, audit log entries.
Observer decouples the matching engine from all downstream consumers.

### Ugly Anti-Pattern -- Matching Engine Calls Everyone

```java
// UGLY: MatchingEngine directly calls every downstream service.
// Add a new consumer? Edit MatchingEngine.

public class UglyMatchingEngine {
    private NotificationService notificationService;
    private MarketDataService marketDataService;
    private AuditService auditService;
    private RiskMonitorService riskMonitorService;

    public Trade match(Order buyOrder, Order sellOrder) {
        Trade trade = new Trade(buyOrder, sellOrder);

        // MatchingEngine knows about EVERY consumer
        notificationService.sendSMS(trade);
        notificationService.sendEmail(trade);
        notificationService.sendPush(trade);
        marketDataService.updateLTP(trade);
        marketDataService.updateVolume(trade);
        auditService.logTrade(trade);
        riskMonitorService.updateExposure(trade);

        // New consumer? Edit this method. Again.
        // MatchingEngine now depends on 4 services -- tight coupling
    }
}
```

### Clean Solution -- Observer with TradeEvent

```java
// CLEAN: MatchingEngine publishes TradeEvent. Observers subscribe.
// Adding a new observer = zero changes to MatchingEngine.

public interface TradeEventListener {
    void onTradeExecuted(TradeEvent event);
}

public class TradeEvent {
    private final Trade trade;
    private final Order buyOrder;
    private final Order sellOrder;
    private final long timestamp;

    public TradeEvent(Trade trade, Order buyOrder, Order sellOrder) {
        this.trade = trade;
        this.buyOrder = buyOrder;
        this.sellOrder = sellOrder;
        this.timestamp = System.currentTimeMillis();
    }

    public Trade getTrade()      { return trade; }
    public Order getBuyOrder()   { return buyOrder; }
    public Order getSellOrder()  { return sellOrder; }
    public long getTimestamp()   { return timestamp; }
}

// NotificationService observes trade events
public class NotificationService implements TradeEventListener {
    @Override
    public void onTradeExecuted(TradeEvent event) {
        Trade trade = event.getTrade();
        sendSMS(trade.getBuyUserId(), "Buy executed: " + trade);
        sendSMS(trade.getSellUserId(), "Sell executed: " + trade);
        sendEmail(trade.getBuyUserId(), trade);
        sendEmail(trade.getSellUserId(), trade);
        sendPush(trade.getBuyUserId(), trade);
        sendPush(trade.getSellUserId(), trade);
    }

    private void sendSMS(String userId, String message) { /* ... */ }
    private void sendEmail(String userId, Trade trade)   { /* ... */ }
    private void sendPush(String userId, Trade trade)    { /* ... */ }
}

// MarketDataService observes trade events
public class MarketDataService implements TradeEventListener {
    private final MarketDataRepository marketDataRepository;

    public MarketDataService(MarketDataRepository repo) {
        this.marketDataRepository = repo;
    }

    @Override
    public void onTradeExecuted(TradeEvent event) {
        Trade trade = event.getTrade();
        MarketData data = marketDataRepository.findBySymbol(trade.getSymbol())
            .orElse(new MarketData(trade.getSymbol()));
        data.updateLTP(trade.getPrice());
        data.addVolume(trade.getQuantity());
        data.updateBidAsk(/* from order book */);
        marketDataRepository.save(data);
    }
}

// MatchingEngine publishes to observers
public class MatchingEngine {
    private final List<TradeEventListener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(TradeEventListener listener) {
        listeners.add(listener);
    }

    private void publishTradeEvent(Trade trade, Order buy, Order sell) {
        TradeEvent event = new TradeEvent(trade, buy, sell);
        for (TradeEventListener listener : listeners) {
            listener.onTradeExecuted(event);  // Sync in our simulation
        }
    }
}
```

### Numbered Call Chain -- Trade Fires Observers

```
1.  MatchingEngine matches Buy 100 RELIANCE @ 2500 with Sell 100 @ 2490
2.  Trade created: tradeId="T-001", symbol="RELIANCE", qty=100, price=2490
3.  MatchingEngine calls publishTradeEvent(trade, buyOrder, sellOrder)
4.  Creates TradeEvent with trade + both orders + timestamp
5.  Iterates over listeners (CopyOnWriteArrayList for thread safety)
6.  Listener #1: NotificationService.onTradeExecuted(event)
7.    -> sendSMS(buyer, "Buy 100 RELIANCE @ 2490 executed")
8.    -> sendSMS(seller, "Sell 100 RELIANCE @ 2490 executed")
9.    -> sendEmail(buyer, trade details)
10.   -> sendEmail(seller, trade details)
11.   -> sendPush(buyer, trade details)
12.   -> sendPush(seller, trade details)
13. Listener #2: MarketDataService.onTradeExecuted(event)
14.   -> updateLTP("RELIANCE", 2490) -- Last Traded Price
15.   -> addVolume("RELIANCE", 100) -- daily volume
16.   -> updateBidAsk from order book snapshot
17. (In production) Listener #3: AuditService.onTradeExecuted(event)
18.   -> log trade to immutable audit trail (regulatory requirement)
```

### Interview One-Liner

> "MatchingEngine publishes TradeEvents to a list of TradeEventListeners.
> NotificationService and MarketDataService subscribe. Adding a new consumer
> (audit, risk monitor) means implementing the interface -- zero changes
> to MatchingEngine."

**Cross-reference:**
- MatchingEngine: see Pattern 10
- Listeners registered in Factory: see Pattern 3
- Facade calls MatchingEngine which triggers Observers: see Pattern 5

---

## 7. State Pattern (Behavioral) -- Order State Machine

An order goes through a strict lifecycle. Invalid transitions must be
rejected at compile time (enum) or runtime (transition guard).

### Ugly Anti-Pattern -- String Status with No Guards

```java
// UGLY: Status is a String. Any code can set any status at any time.

public class UglyOrder {
    private String status = "NEW";

    public void setStatus(String status) {
        this.status = status;  // No validation AT ALL
    }
}

// Somewhere in the codebase:
order.setStatus("FILLLED");   // Typo -- compiles fine, breaks everything
order.setStatus("CANCELLED"); // From FILLED? That's illegal but allowed
order.setStatus("OPEN");      // From FILLED? Going backwards? Sure!
```

### Clean Solution -- Enum + Transition Guards

```java
// CLEAN: Enum for states, explicit transition table, guarded methods.

public enum OrderStatus {
    CREATED,           // Order built, not yet submitted to risk
    PENDING_RISK,      // Submitted to risk check chain
    OPEN,              // Risk passed, in order book / sent to exchange
    PARTIALLY_FILLED,  // Some quantity matched
    FILLED,            // All quantity matched (terminal)
    CANCELLED,         // User or system cancelled (terminal)
    REJECTED,          // Risk check failed (terminal)
    EXPIRED            // Time-in-force expired (terminal)
}
```

### State Transition Diagram

```
  +----------------------------------------------------------------------+
  |                     ORDER STATE MACHINE                               |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  CREATED                                                             |
  |    |                                                                 |
  |    | (1) submitToRisk()                                              |
  |    v                                                                 |
  |  PENDING_RISK                                                        |
  |    |         \                                                       |
  |    |          \ (2b) reject()                                        |
  |    | (2a)      \                                                     |
  |    | open()     v                                                    |
  |    |         REJECTED [terminal]                                     |
  |    v                                                                 |
  |  OPEN                                                                |
  |    |    \         \                                                  |
  |    |     \         \ (3c) cancel()                                   |
  |    |      \         \                                                |
  |    | (3a)  \ (3b)    v                                               |
  |    | fill() \ partialFill()  CANCELLED [terminal]                    |
  |    |         \                                                       |
  |    |          v                                                      |
  |    |       PARTIALLY_FILLED                                          |
  |    |          |    \          \                                       |
  |    |          |     \          \ (4c) cancel()                        |
  |    |          |      \          \                                     |
  |    |          | (4a)  \ (4b)     v                                    |
  |    |          | more   | fill()  CANCELLED [terminal]                 |
  |    |          | fills  |                                              |
  |    |          v        v                                              |
  |    |        (self)   FILLED [terminal]                                |
  |    |                                                                 |
  |    v                                                                 |
  |  FILLED [terminal]                                                   |
  |                                                                      |
  |  EXPIRED [terminal] -- from OPEN or PARTIALLY_FILLED if TTL reached  |
  +----------------------------------------------------------------------+
```

### Valid Transitions Table

| From | To | Trigger | Who |
|------|----|---------|-----|
| CREATED | PENDING_RISK | submitToRisk() | TradingService |
| PENDING_RISK | OPEN | open() | TradingService (risk passed) |
| PENDING_RISK | REJECTED | reject(reason) | RiskCheckStrategy |
| OPEN | PARTIALLY_FILLED | partiallyFill() | MatchingEngine |
| OPEN | FILLED | fill() | MatchingEngine |
| OPEN | CANCELLED | cancel() | User or system |
| OPEN | EXPIRED | expire() | Timer / scheduler |
| PARTIALLY_FILLED | PARTIALLY_FILLED | moreFill() | MatchingEngine |
| PARTIALLY_FILLED | FILLED | fill() | MatchingEngine (last fill) |
| PARTIALLY_FILLED | CANCELLED | cancel() | User (remaining qty cancelled) |

### Interview One-Liner

> "OrderStatus is an enum with 8 states. Each transition method validates
> the current state before moving -- calling fill() on a CANCELLED order
> throws IllegalStateException. Terminal states (FILLED, CANCELLED, REJECTED,
> EXPIRED) have no outgoing transitions."

**Cross-reference:**
- Order built with Builder: see Pattern 2
- Transitions triggered by Facade: see Pattern 5
- Transitions triggered by MatchingEngine: see Pattern 10

---

## 8. Chain of Responsibility (Behavioral) -- Risk Check Pipeline

Before any order enters the matching engine, it passes through a chain of
risk checks. Each check can REJECT the order or PASS it to the next check.
The chain is ordered: Margin -> PositionLimit -> CircuitBreaker.

### Ugly Anti-Pattern -- Giant If-Else Block

```java
// UGLY: All risk checks in one method. Adding a new check?
// Find the right place in the if-else chain. Hope you don't break others.

public class UglyRiskService {
    public boolean checkRisk(Order order, Account account, Stock stock) {
        // Check 1: Margin
        double required = order.getQuantity() * order.getPrice();
        if (account.getAvailableMargin() < required) {
            System.out.println("REJECTED: insufficient margin");
            return false;
        }

        // Check 2: Position limit
        // Hmm, where do I get current position? Inline query?
        int currentPos = /* inline DB query */ 500;
        if (currentPos + order.getQuantity() > 10000) {
            System.out.println("REJECTED: position limit");
            return false;
        }

        // Check 3: Circuit breaker
        if (order.getPrice() > stock.getUpperCircuitLimit()
            || order.getPrice() < stock.getLowerCircuitLimit()) {
            System.out.println("REJECTED: outside circuit");
            return false;
        }

        // Check 4: Want to add "duplicate order" check?
        // Edit this method. Growing forever.
        // Check 5: Want to add "quantity limit per order" check?
        // Edit this method. More if-else.

        return true;
    }
}
```

### Clean Solution -- Chain of Responsibility

```java
// CLEAN: Each risk check is an independent RiskCheckStrategy.
// The chain is a List<RiskCheckStrategy> iterated in order.
// Adding a new check = implement interface + add to list in AppConfig.

public class MarginCheckStrategy implements RiskCheckStrategy {
    @Override
    public RiskResult check(Order order, Account account, Stock stock) {
        double required = order.getQuantity() * order.getPrice()
                        + calculateBrokerage(order);
        if (account.getAvailableMargin() < required) {
            return RiskResult.fail("Insufficient margin: need "
                + required + ", have " + account.getAvailableMargin());
        }
        return RiskResult.pass();
    }
}

public class PositionLimitStrategy implements RiskCheckStrategy {
    private final int maxPositionPerSymbol;

    public PositionLimitStrategy(int maxPositionPerSymbol) {
        this.maxPositionPerSymbol = maxPositionPerSymbol;
    }

    @Override
    public RiskResult check(Order order, Account account, Stock stock) {
        int currentPosition = account.getPositionQuantity(order.getSymbol());
        int afterOrder = currentPosition + order.getQuantity();
        if (afterOrder > maxPositionPerSymbol) {
            return RiskResult.fail("Position limit exceeded: "
                + afterOrder + " > " + maxPositionPerSymbol);
        }
        return RiskResult.pass();
    }
}

public class CircuitBreakerStrategy implements RiskCheckStrategy {
    private final StockRepository stockRepository;

    public CircuitBreakerStrategy(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    @Override
    public RiskResult check(Order order, Account account, Stock stock) {
        if (order.getOrderType() == OrderType.MARKET) {
            return RiskResult.pass();  // Market orders skip price check
        }
        if (order.getPrice() > stock.getUpperCircuitLimit()) {
            return RiskResult.fail("Price " + order.getPrice()
                + " exceeds upper circuit " + stock.getUpperCircuitLimit());
        }
        if (order.getPrice() < stock.getLowerCircuitLimit()) {
            return RiskResult.fail("Price " + order.getPrice()
                + " below lower circuit " + stock.getLowerCircuitLimit());
        }
        return RiskResult.pass();
    }
}

// In TradingService (Facade):
for (RiskCheckStrategy riskCheck : riskChecks) {
    RiskResult result = riskCheck.check(order, account, stock);
    if (!result.isPassed()) {
        order.reject(result.getReason());
        return OrderResult.rejected(result.getReason());
    }
}
// All checks passed -- proceed to matching
```

### ASCII Diagram -- Risk Check Chain

```
  +----------------------------------------------------------------------+
  |                    RISK CHECK CHAIN                                   |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  Order                                                               |
  |    |                                                                 |
  |    v                                                                 |
  |  +------------------+     PASS     +------------------+              |
  |  | MarginCheck      |------------>| PositionLimit    |              |
  |  | "Can user afford |             | "Is total pos    |              |
  |  |  this order?"    |             |  within limit?"  |              |
  |  +------------------+             +------------------+              |
  |    | FAIL                           |     | FAIL                    |
  |    v                                |     v                         |
  |  REJECTED                           |   REJECTED                    |
  |  "Insufficient                      |   "Position limit             |
  |   margin: need                      |    exceeded"                  |
  |   50000, have 30000"                |                               |
  |                                     v                               |
  |                              +------------------+                   |
  |                              | CircuitBreaker   |                   |
  |                              | "Is price within |                   |
  |                              |  exchange bands?"|                   |
  |                              +------------------+                   |
  |                                |          | FAIL                    |
  |                                | PASS     v                         |
  |                                |        REJECTED                    |
  |                                |        "Price 3000 outside         |
  |                                |         circuit [2200-2800]"       |
  |                                v                                    |
  |                         ALL CHECKS PASSED                           |
  |                         Order -> OPEN state                         |
  |                         Enter Matching Engine                       |
  +----------------------------------------------------------------------+
```

### Numbered Call Chain -- Order Rejected by Second Check

```
1.  User places Buy 15000 RELIANCE @ 2500 (total value = 37,500,000)
2.  TradingService iterates riskChecks list
3.  Check #1: MarginCheckStrategy
4.    account.availableMargin = 50,000,000 >= 37,500,000 + brokerage -> PASS
5.  Check #2: PositionLimitStrategy
6.    currentPosition("RELIANCE") = 2000 shares
7.    2000 + 15000 = 17000 > 10000 (maxPositionPerSymbol) -> FAIL
8.    RiskResult.fail("Position limit exceeded: 17000 > 10000")
9.  TradingService receives FAIL -> order.reject("Position limit exceeded")
10. OrderRepository.save(order) with status = REJECTED
11. Return OrderResult.rejected("Position limit exceeded: 17000 > 10000")
12. Check #3 (CircuitBreaker) is NEVER reached -- short-circuit on first failure
```

### Interview One-Liner

> "Risk checks form a Chain of Responsibility -- MarginCheck, PositionLimit,
> CircuitBreaker run in order. First failure rejects the order immediately;
> adding a new check means implementing RiskCheckStrategy and appending
> to the list in AppConfig."

**Cross-reference:**
- Chain assembled in Factory: see Pattern 3
- Chain called from Facade: see Pattern 5
- CircuitBreaker uses Stock from Repository: see Pattern 4

---

## 9. Command Pattern (Behavioral) -- Order as Command Object

An Order is a command object: it encapsulates all information needed to
execute (or cancel, or modify) an action. The MatchingEngine is the invoker;
the OrderBook is the receiver.

### Ugly Anti-Pattern -- Positional Parameters

```java
// UGLY: Order actions are method calls with many parameters.
// No undo, no queue, no audit trail.

public class UglyMatchingEngine {
    public void placeOrder(String userId, String symbol, String side,
                           int qty, double price, String type) {
        // All parameters passed around loosely
        // Cannot queue this "command" -- it's just a method call
        // Cannot log it -- no serializable object
        // Cannot undo it -- no cancel() method
    }

    public void cancelOrder(String orderId) {
        // No relationship to the place command
        // No idempotency -- cancel twice = error or silent ignore?
    }
}
```

### Clean Solution -- Order as Command

```java
// CLEAN: Order is a command object with all info encapsulated.
// Can be queued, logged, serialized, retried, cancelled.

public class Order {
    // All fields from Builder pattern (see Pattern 2)
    // This IS the command -- contains everything needed to execute

    // The "execute" action depends on the order lifecycle:
    // - Place: send to matching engine
    // - Cancel: remove from order book
    // - Modify: cancel + re-place with new parameters
}

// TradingService as the Invoker:
public class TradingService {
    private final Queue<Order> orderQueue = new ConcurrentLinkedQueue<>();

    public OrderResult placeOrder(Order order) {
        // Log the command (audit trail)
        auditLog.log("PLACE", order);

        // Execute the command
        OrderResult result = executeOrder(order);

        // Command is a first-class object -- can be replayed, audited
        return result;
    }

    public OrderResult cancelOrder(String orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new IllegalArgumentException("Order not found"));

        // Log the cancel command
        auditLog.log("CANCEL", order);

        // Remove from order book
        OrderBook book = matchingEngine.getOrderBook(order.getSymbol());
        book.removeOrder(order);
        order.cancel();
        orderRepository.save(order);

        return OrderResult.cancelled(order);
    }

    public OrderResult modifyOrder(String orderId, int newQty, double newPrice) {
        // Modify = Cancel + Re-place (atomic from user's perspective)
        Order original = orderRepository.findById(orderId)
            .orElseThrow();

        // Cancel original
        cancelOrder(orderId);

        // Place new order with modified parameters
        Order modified = Order.builder()
            .userId(original.getUserId())
            .symbol(original.getSymbol())
            .side(original.getSide())
            .orderType(original.getOrderType())
            .quantity(newQty)
            .price(newPrice)
            .build();

        return placeOrder(modified);
    }
}
```

### Numbered Call Chain -- Cancel Order Flow

```
1.  User clicks "Cancel" on order ORD-001 (Buy 100 RELIANCE @ 2500, status OPEN)
2.  TradingService.cancelOrder("ORD-001")
3.  OrderRepository.findById("ORD-001") -> returns Order with status OPEN
4.  AuditLog.log("CANCEL", order) -- immutable audit record
5.  MatchingEngine.getOrderBook("RELIANCE") -> OrderBook
6.  OrderBook.removeOrder(order) -> removes from TreeMap bids at 2500
7.  Order.cancel() -> state transitions OPEN -> CANCELLED
8.  OrderRepository.save(order) with status = CANCELLED
9.  Account margin unfrozen: 100 * 2500 = 250000 returned to available margin
10. NotificationService: "Order ORD-001 cancelled successfully"
```

### Interview One-Liner

> "Order is a command object -- it encapsulates all info needed to place,
> cancel, or modify a trade. Commands can be queued (for matching),
> logged (for audit), and reversed (cancel). Modify is implemented as
> atomic cancel + re-place."

**Cross-reference:**
- Order built with Builder: see Pattern 2
- Command executed by Facade: see Pattern 5
- Command targets OrderBook in MatchingEngine: see Pattern 10

---

## 10. Singleton Pattern (Creational) -- MatchingEngine

### THE Core Data Structure -- The Order Book

The matching engine is the heart of a trading platform. Each symbol gets
ONE order book. The order book uses **TreeMap** for price-time priority.

This is what interviewers will drill into. Know this cold.

### Ugly Anti-Pattern -- Multiple Matching Engines

```java
// UGLY: Every service creates its own matching engine.
// Two matching engines for RELIANCE = two independent order books.
// Order placed via service A is invisible to service B.

public class UglyTradingServiceA {
    private MatchingEngine engine = new MatchingEngine();  // Instance 1

    public void placeOrder(Order order) {
        engine.match(order);
    }
}

public class UglyTradingServiceB {
    private MatchingEngine engine = new MatchingEngine();  // Instance 2!

    public void placeOrder(Order order) {
        engine.match(order);  // Completely separate order book!
    }
}

// User A's buy order and User B's sell order never meet.
// Two order books for the same symbol = WRONG prices, WRONG fills.
```

### Clean Solution -- Centrally Managed MatchingEngine + OrderBook with TreeMap

```java
// CLEAN: One MatchingEngine instance, manages one OrderBook per symbol.
// OrderBook uses TreeMap<Double, PriceLevel> for price-time priority.

public class MatchingEngine {
    // One order book per symbol -- ConcurrentHashMap for thread-safe access
    private final Map<String, OrderBook> orderBooks = new ConcurrentHashMap<>();
    private final List<TradeEventListener> listeners = new CopyOnWriteArrayList<>();

    public OrderBook getOrderBook(String symbol) {
        return orderBooks.computeIfAbsent(symbol, s -> new OrderBook(s));
    }

    public void addListener(TradeEventListener listener) {
        listeners.add(listener);
    }
}

/**
 * OrderBook: THE core data structure of a trading platform.
 *
 * Uses TreeMap for price levels:
 * - BIDS (buy orders): TreeMap with REVERSE order (highest price first)
 * - ASKS (sell orders): TreeMap with NATURAL order (lowest price first)
 *
 * Each PriceLevel holds a FIFO queue of orders at that price.
 * This gives us price-time priority: best price matched first,
 * within same price, earliest order matched first.
 *
 * WHY TreeMap:
 * - O(log N) insert and remove by price
 * - O(1) access to best bid (lastEntry) and best ask (firstEntry)
 * - Automatic sorting -- no manual sort needed
 * - NavigableMap operations: headMap, tailMap for sweep matching
 *
 * In production (C++): std::map with custom allocator for cache locality.
 * In our Java simulation: TreeMap is the closest equivalent.
 */
public class OrderBook {
    private final String symbol;

    // BIDS: highest price first (buyer wants best deal = lowest possible,
    //        but we match highest bid first for the seller)
    private final TreeMap<Double, PriceLevel> bids =
        new TreeMap<>(Comparator.reverseOrder());

    // ASKS: lowest price first (seller wants best deal = highest possible,
    //        but we match lowest ask first for the buyer)
    private final TreeMap<Double, PriceLevel> asks =
        new TreeMap<>();

    public OrderBook(String symbol) {
        this.symbol = symbol;
    }

    /**
     * Add an order to the book (resting order that wasn't fully matched).
     */
    public void addOrder(Order order) {
        TreeMap<Double, PriceLevel> book = (order.getSide() == Side.BUY) ? bids : asks;
        book.computeIfAbsent(order.getPrice(), p -> new PriceLevel(p))
            .addOrder(order);
    }

    /**
     * Remove an order from the book (cancelled or fully filled).
     */
    public void removeOrder(Order order) {
        TreeMap<Double, PriceLevel> book = (order.getSide() == Side.BUY) ? bids : asks;
        PriceLevel level = book.get(order.getPrice());
        if (level != null) {
            level.removeOrder(order);
            if (level.isEmpty()) {
                book.remove(order.getPrice());  // Clean up empty levels
            }
        }
    }

    /**
     * Get the best bid price (highest buy price).
     */
    public Optional<Double> getBestBid() {
        return bids.isEmpty() ? Optional.empty()
                              : Optional.of(bids.firstKey());
    }

    /**
     * Get the best ask price (lowest sell price).
     */
    public Optional<Double> getBestAsk() {
        return asks.isEmpty() ? Optional.empty()
                              : Optional.of(asks.firstKey());
    }

    /**
     * Get all ask levels at or below the given price (for buy matching).
     */
    public NavigableMap<Double, PriceLevel> getAsksAtOrBelow(double price) {
        return asks.headMap(price, true);
    }

    /**
     * Get all bid levels at or above the given price (for sell matching).
     */
    public NavigableMap<Double, PriceLevel> getBidsAtOrAbove(double price) {
        return bids.headMap(price, true);
    }
}

/**
 * PriceLevel: all orders at a single price point.
 * FIFO queue ensures time priority within the same price.
 */
public class PriceLevel {
    private final double price;
    private final Deque<Order> orders = new ArrayDeque<>();
    private int totalQuantity = 0;

    public PriceLevel(double price) {
        this.price = price;
    }

    public void addOrder(Order order) {
        orders.addLast(order);
        totalQuantity += order.getRemainingQuantity();
    }

    public void removeOrder(Order order) {
        orders.remove(order);
        totalQuantity -= order.getRemainingQuantity();
    }

    /**
     * Match against orders at this level in FIFO order.
     * Returns the list of trades generated.
     */
    public List<Trade> match(Order incomingOrder) {
        List<Trade> trades = new ArrayList<>();
        Iterator<Order> it = orders.iterator();

        while (it.hasNext() && incomingOrder.getRemainingQuantity() > 0) {
            Order restingOrder = it.next();
            int matchQty = Math.min(
                incomingOrder.getRemainingQuantity(),
                restingOrder.getRemainingQuantity()
            );

            Trade trade = new Trade(
                UUID.randomUUID().toString(),
                incomingOrder.getSymbol(),
                price,
                matchQty,
                incomingOrder.getOrderId(),
                restingOrder.getOrderId(),
                incomingOrder.getUserId(),
                restingOrder.getUserId()
            );
            trades.add(trade);

            incomingOrder.addFill(matchQty);
            restingOrder.addFill(matchQty);

            if (restingOrder.getRemainingQuantity() == 0) {
                it.remove();  // Fully filled, remove from book
            }
            totalQuantity -= matchQty;
        }
        return trades;
    }

    public boolean isEmpty() { return orders.isEmpty(); }
    public double getPrice() { return price; }
    public int getTotalQuantity() { return totalQuantity; }
}
```

### ASCII Diagram -- Order Book Data Structure

```
  +----------------------------------------------------------------------+
  |                    ORDER BOOK for RELIANCE                            |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  BIDS (Buy Orders)                  ASKS (Sell Orders)               |
  |  TreeMap<Double, PriceLevel>        TreeMap<Double, PriceLevel>      |
  |  Comparator.reverseOrder()          Natural order (ascending)        |
  |                                                                      |
  |  Price    Qty   Orders (FIFO)       Price    Qty   Orders (FIFO)     |
  |  ------   ----  ---------------     ------   ----  ---------------   |
  |  2500.00  300   [100, 200]          2501.00  150   [100, 50]         |
  |  2499.50  500   [200, 150, 150]     2502.00  200   [200]             |
  |  2498.00  100   [100]               2505.00  400   [250, 150]        |
  |  2495.00  250   [250]               2510.00  100   [100]             |
  |                                                                      |
  |  ^^ Best Bid: 2500.00              ^^ Best Ask: 2501.00              |
  |     (highest buy price)               (lowest sell price)            |
  |                                                                      |
  |  Spread = 2501.00 - 2500.00 = 1.00                                  |
  |                                                                      |
  |  TreeMap guarantees:                                                 |
  |  - Best bid = bids.firstKey() = O(log N) but cached = O(1)          |
  |  - Best ask = asks.firstKey() = O(log N) but cached = O(1)          |
  |  - Insert at price = O(log N) for TreeMap + O(1) for Deque append    |
  |  - Match = O(K) where K = number of resting orders consumed          |
  |                                                                      |
  |  PriceLevel internals (at 2500.00):                                  |
  |  +-------------------------------------------+                      |
  |  | price: 2500.00                             |                      |
  |  | totalQuantity: 300                         |                      |
  |  | orders: Deque<Order>                       |                      |
  |  |   [0]: Order{id=O-1, qty=100, time=09:15}  <-- matched first     |
  |  |   [1]: Order{id=O-5, qty=200, time=09:17}  <-- matched second    |
  |  +-------------------------------------------+                      |
  +----------------------------------------------------------------------+
```

### Numbered Call Chain -- Limit Buy Sweeps Multiple Ask Levels

```
1.  Incoming: Buy 350 RELIANCE @ 2505 Limit
2.  LimitOrderStrategy.execute(order, orderBook)
3.  orderBook.getAsksAtOrBelow(2505) returns:
      2501.00 -> PriceLevel [100, 50] (150 total)
      2502.00 -> PriceLevel [200]     (200 total)
      2505.00 -> PriceLevel [250, 150] (400 total)
4.  Level 2501.00: match 150 shares
      Trade #1: 100 @ 2501.00 (Order O-10 fully filled, removed)
      Trade #2:  50 @ 2501.00 (Order O-11 fully filled, removed)
      PriceLevel empty -> removed from TreeMap
      Remaining: 350 - 150 = 200
5.  Level 2502.00: match 200 shares
      Trade #3: 200 @ 2502.00 (Order O-12 fully filled, removed)
      PriceLevel empty -> removed from TreeMap
      Remaining: 200 - 200 = 0
6.  Order fully filled (350 shares, 3 trades)
7.  Weighted average price = (150*2501 + 200*2502) / 350 = 2501.57
8.  Level 2505.00 never touched (didn't need more shares)
9.  New best ask = 2505.00 (was 2501.00)
10. TradeEvents published for all 3 trades -> Observers notified
```

### Numbered Call Chain -- Market Sell Sweeps Bid Side

```
1.  Incoming: Sell 400 RELIANCE at Market
2.  MarketOrderStrategy.execute(order, orderBook)
3.  Start from best bid (highest price):
      2500.00 -> PriceLevel [100, 200] (300 total)
      2499.50 -> PriceLevel [200, 150, 150] (500 total)
4.  Level 2500.00: match 300 shares
      Trade #1: 100 @ 2500.00 (O-1 fully filled)
      Trade #2: 200 @ 2500.00 (O-5 fully filled)
      PriceLevel empty -> removed
      Remaining: 400 - 300 = 100
5.  Level 2499.50: match 100 shares
      Trade #3: 100 @ 2499.50 (O-7 partially filled, 100 left)
      PriceLevel NOT empty (still has O-8=150, O-9=150, O-7 reduced)
      Remaining: 100 - 100 = 0
6.  Order fully filled (400 shares, 3 trades)
7.  VWAP = (300*2500 + 100*2499.50) / 400 = 2499.875
8.  New best bid = 2499.50 (was 2500.00), but level is reduced
9.  TradeEvents published for all 3 trades
```

### Why TreeMap and Not HashMap

| Operation | TreeMap | HashMap | Why TreeMap Wins |
|-----------|---------|---------|-----------------|
| Best bid/ask | O(log N) | O(N) scan | Must find best price instantly |
| Insert at price | O(log N) | O(1) | HashMap is faster but can't find best |
| Range query (sweep) | O(log N + K) | O(N) filter | headMap/tailMap for level sweeping |
| Remove empty level | O(log N) | O(1) | Acceptable cost |
| Ordered iteration | Built-in | Not supported | Must iterate by price priority |

### Interview One-Liner

> "One MatchingEngine per system, one OrderBook per symbol. The OrderBook uses
> TreeMap<Double, PriceLevel> with reverse order for bids and natural order
> for asks. PriceLevel holds a FIFO Deque<Order> for time priority.
> This gives O(log N) insert and best-price access, with sweep matching
> via NavigableMap.headMap()."

**Cross-reference:**
- MatchingEngine created in Factory: see Pattern 3
- Called from Facade: see Pattern 5
- Trade events trigger Observer: see Pattern 6
- Resting orders use State pattern: see Pattern 7

---

## Quick Reference -- All Patterns at a Glance

```
  +----------------------------------------------------------------------+
  |                  TRADING PLATFORM PATTERN MAP                         |
  +----------------------------------------------------------------------+
  |                                                                      |
  |  [AppConfig]  (Factory, Pattern 3)                                   |
  |       |                                                              |
  |       | creates and wires all components:                            |
  |       |                                                              |
  |       +---> [Order.Builder]  (Builder, Pattern 2)                    |
  |       |       constructs Order (Command, Pattern 9)                  |
  |       |       with OrderStatus (State, Pattern 7)                    |
  |       |                                                              |
  |       +---> [TradingService]  (Facade, Pattern 5)                    |
  |       |       |                                                      |
  |       |       +---> Risk Chain (CoR, Pattern 8)                      |
  |       |       |     MarginCheck -> PositionLimit -> CircuitBreaker    |
  |       |       |     Each is a RiskCheckStrategy (Strategy, Pattern 1)|
  |       |       |                                                      |
  |       |       +---> [MatchingEngine] (Singleton, Pattern 10)         |
  |       |       |     OrderBook: TreeMap<Double, PriceLevel>           |
  |       |       |     Uses OrderExecutionStrategy (Strategy, Pattern 1)|
  |       |       |                                                      |
  |       |       +---> PnLStrategy (Strategy, Pattern 1)                |
  |       |       |     FIFO or AverageCost                              |
  |       |       |                                                      |
  |       |       +---> 6 Repositories (Repository, Pattern 4)           |
  |       |                                                              |
  |       +---> [Observers] (Observer, Pattern 6)                        |
  |             NotificationService + MarketDataService                   |
  |             listen to TradeEvents from MatchingEngine                 |
  |                                                                      |
  +----------------------------------------------------------------------+
```

---

## Interview Cheat Sheet

| Question | Pattern | Answer |
|----------|---------|--------|
| "How do you support different order types?" | Strategy | OrderExecutionStrategy with Market/Limit implementations |
| "How do you validate orders before matching?" | Chain of Responsibility | List<RiskCheckStrategy> iterated in order; first fail rejects |
| "How is the order book structured?" | Singleton + TreeMap | TreeMap<Double, PriceLevel> with reverse for bids, natural for asks |
| "How do you calculate P&L?" | Strategy | PnLStrategy with FIFO/AvgCost; injected, swappable |
| "How do you track order lifecycle?" | State | OrderStatus enum with guarded transitions |
| "How do downstream services know about trades?" | Observer | TradeEventListener interface; MatchingEngine publishes |
| "How do you build complex orders?" | Builder | Order.Builder with validation and immutable result |
| "How is everything wired together?" | Factory | AppConfig creates all concretes, injects interfaces |
| "What's the single entry point?" | Facade | TradingService.placeOrder() orchestrates everything |
| "Can an order be cancelled?" | Command | Order is a command object; cancel = remove from book + state transition |
