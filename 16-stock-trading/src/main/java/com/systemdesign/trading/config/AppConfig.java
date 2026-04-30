package com.systemdesign.trading.config;

import com.systemdesign.trading.controller.TradingController;
import com.systemdesign.trading.display.TradingStatsDisplay;
import com.systemdesign.trading.engine.MatchingEngine;
import com.systemdesign.trading.model.*;
import com.systemdesign.trading.repository.*;
import com.systemdesign.trading.service.*;
import com.systemdesign.trading.strategy.order.LimitOrderStrategy;
import com.systemdesign.trading.strategy.order.MarketOrderStrategy;
import com.systemdesign.trading.strategy.pricing.FIFOPnLStrategy;
import com.systemdesign.trading.strategy.pricing.PnLStrategy;
import com.systemdesign.trading.strategy.risk.CircuitBreakerStrategy;
import com.systemdesign.trading.strategy.risk.MarginCheckStrategy;
import com.systemdesign.trading.strategy.risk.PositionLimitStrategy;
import com.systemdesign.trading.strategy.risk.RiskCheckStrategy;

import java.util.List;

/**
 * AppConfig is the FACTORY and composition root for the entire trading system.
 *
 * THIS IS THE ONLY PLACE WHERE "new ConcreteClass()" APPEARS.
 *
 * WHY centralized wiring:
 * - All dependencies are explicit and visible in one place.
 * - Easy to swap implementations (e.g., InMemoryXxxRepository → JdbcXxxRepository).
 * - In production, a DI framework (Spring, Guice) would do this automatically.
 * - For interview demo: shows you understand dependency injection without a framework.
 *
 * INITIALIZATION ORDER (dependencies flow downward):
 * 1. Repositories (no dependencies)
 * 2. Strategies (depend on repositories for circuit breaker)
 * 3. Engine (depends on strategies)
 * 4. Services (depend on repositories and engine)
 * 5. Controller (depends on TradingService)
 * 6. Display (depends on repositories and services)
 * 7. Seed data (stocks, users, accounts, market data)
 *
 * SEEDED DATA:
 * - 5 stocks: RELIANCE, TCS, INFY, HDFC, ICICI (with realistic prices and circuit limits)
 * - 3 users: trader1 (retail, 500K), trader2 (retail, 300K), institution1 (institutional, 5M)
 */
public class AppConfig {

    // --- Repositories ---
    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;
    private final PositionRepository positionRepository;
    private final AccountRepository accountRepository;
    private final StockRepository stockRepository;
    private final MarketDataRepository marketDataRepository;

    // --- Strategies ---
    private final MarginCheckStrategy marginCheckStrategy;
    private final PositionLimitStrategy positionLimitStrategy;
    private final CircuitBreakerStrategy circuitBreakerStrategy;
    private final PnLStrategy pnlStrategy;

    // --- Engine ---
    private final MatchingEngine matchingEngine;

    // --- Services ---
    private final OrderService orderService;
    private final RiskService riskService;
    private final AccountService accountService;
    private final MatchingService matchingService;
    private final PortfolioService portfolioService;
    private final MarketDataService marketDataService;
    private final NotificationService notificationService;
    private final SettlementService settlementService;
    private final TradingService tradingService;

    // --- Controller ---
    private final TradingController tradingController;

    // --- Display ---
    private final TradingStatsDisplay statsDisplay;

    public AppConfig() {
        // =================================================================
        // STEP 1: Create repositories (no dependencies — leaf nodes)
        // =================================================================
        this.orderRepository = new InMemoryOrderRepository();
        this.tradeRepository = new InMemoryTradeRepository();
        this.positionRepository = new InMemoryPositionRepository();
        this.accountRepository = new InMemoryAccountRepository();
        this.stockRepository = new InMemoryStockRepository();
        this.marketDataRepository = new InMemoryMarketDataRepository();

        // =================================================================
        // STEP 2: Seed stocks with realistic Indian market data
        // Circuit limits are +-20% from the opening price (standard NSE band)
        // =================================================================
        seedStocks();

        // =================================================================
        // STEP 3: Seed users and accounts
        // =================================================================
        seedUsers();

        // =================================================================
        // STEP 4: Seed market data (opening prices for each stock)
        // =================================================================
        seedMarketData();

        // =================================================================
        // STEP 5: Create strategies
        // =================================================================

        // Margin check: 100% margin required (full delivery — no leverage for simplicity)
        this.marginCheckStrategy = new MarginCheckStrategy(1.0);

        // Position limit: max 10,000 shares per symbol per user
        this.positionLimitStrategy = new PositionLimitStrategy(10_000);

        // Circuit breaker: reads limits from StockRepository
        this.circuitBreakerStrategy = new CircuitBreakerStrategy(stockRepository);

        // P&L strategy: FIFO (Indian tax rules)
        this.pnlStrategy = new FIFOPnLStrategy();

        // =================================================================
        // STEP 6: Create matching engine and register execution strategies
        // =================================================================
        this.matchingEngine = new MatchingEngine();

        // Register order execution strategies
        // WHY registered here: engine is strategy-agnostic. AppConfig decides which
        // strategies to use. Could swap MarketOrderStrategy for a smarter one.
        matchingEngine.registerStrategy(OrderType.MARKET, new MarketOrderStrategy());
        matchingEngine.registerStrategy(OrderType.LIMIT, new LimitOrderStrategy());
        // STOP_LOSS and STOP_LIMIT are handled by falling back to MARKET/LIMIT in the engine

        // =================================================================
        // STEP 7: Create services (dependency injection via constructor)
        // =================================================================
        this.notificationService = new NotificationService();
        this.accountService = new AccountService(accountRepository);
        this.orderService = new OrderService(orderRepository, stockRepository);
        this.matchingService = new MatchingService(matchingEngine, tradeRepository);
        this.marketDataService = new MarketDataService(marketDataRepository);
        this.portfolioService = new PortfolioService(positionRepository, tradeRepository, pnlStrategy);
        this.settlementService = new SettlementService(tradeRepository, accountService, notificationService);

        // Risk service: chain of checks in order (cheapest first)
        List<RiskCheckStrategy> riskChecks = List.of(
                circuitBreakerStrategy,    // Cheap: just a price comparison
                marginCheckStrategy,       // Medium: reads account balance
                positionLimitStrategy      // Medium: reads current position
        );
        this.riskService = new RiskService(riskChecks);

        // TradingService: the facade that orchestrates everything
        this.tradingService = new TradingService(
                orderService, riskService, accountService, matchingService,
                portfolioService, marketDataService, notificationService,
                positionLimitStrategy
        );

        // =================================================================
        // STEP 8: Create controller and display
        // =================================================================
        this.tradingController = new TradingController(tradingService);
        this.statsDisplay = new TradingStatsDisplay(
                orderRepository, tradeRepository, marketDataService,
                matchingService, portfolioService
        );
    }

    // =====================================================================
    // SEED DATA
    // =====================================================================

    private void seedStocks() {
        // Stock(symbol, name, exchange, lotSize, tickSize, upperCircuit, lowerCircuit)
        // Circuit limits: +-20% from previous close (standard NSE band for large-caps)

        stockRepository.save(new Stock("RELIANCE", "Reliance Industries Ltd", "NSE",
                1, 0.05, 3000.00, 2000.00)); // Previous close ~2500

        stockRepository.save(new Stock("TCS", "Tata Consultancy Services", "NSE",
                1, 0.05, 4560.00, 3040.00)); // Previous close ~3800

        stockRepository.save(new Stock("INFY", "Infosys Ltd", "NSE",
                1, 0.05, 1920.00, 1280.00)); // Previous close ~1600

        stockRepository.save(new Stock("HDFC", "HDFC Bank Ltd", "NSE",
                1, 0.05, 2040.00, 1360.00)); // Previous close ~1700

        stockRepository.save(new Stock("ICICI", "ICICI Bank Ltd", "NSE",
                1, 0.05, 1320.00, 880.00)); // Previous close ~1100

        System.out.println("  Seeded 5 stocks: RELIANCE, TCS, INFY, HDFC, ICICI");
    }

    private void seedUsers() {
        // User accounts with initial balances
        accountRepository.save(new Account("trader1", "Rahul Sharma", 5_000_000.00));
        accountRepository.save(new Account("trader2", "Priya Patel", 3_000_000.00));
        accountRepository.save(new Account("institution1", "ABC Mutual Fund", 50_000_000.00));

        System.out.println("  Seeded 3 users: trader1 (50L), trader2 (30L), institution1 (5Cr)");
    }

    private void seedMarketData() {
        // Initialize market data with opening prices
        marketDataRepository.save(new MarketData("RELIANCE", 2500.00));
        marketDataRepository.save(new MarketData("TCS", 3800.00));
        marketDataRepository.save(new MarketData("INFY", 1600.00));
        marketDataRepository.save(new MarketData("HDFC", 1700.00));
        marketDataRepository.save(new MarketData("ICICI", 1100.00));

        System.out.println("  Seeded market data for 5 symbols");
    }

    // =====================================================================
    // GETTERS — expose components for the demo app
    // =====================================================================

    public TradingService getTradingService() { return tradingService; }
    public TradingController getTradingController() { return tradingController; }
    public TradingStatsDisplay getStatsDisplay() { return statsDisplay; }
    public SettlementService getSettlementService() { return settlementService; }
    public AccountService getAccountService() { return accountService; }
    public MarketDataService getMarketDataService() { return marketDataService; }
    public PortfolioService getPortfolioService() { return portfolioService; }
    public StockRepository getStockRepository() { return stockRepository; }
    public OrderRepository getOrderRepository() { return orderRepository; }
    public TradeRepository getTradeRepository() { return tradeRepository; }
    public MatchingEngine getMatchingEngine() { return matchingEngine; }
}
