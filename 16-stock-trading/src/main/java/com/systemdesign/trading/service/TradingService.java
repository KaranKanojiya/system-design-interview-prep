package com.systemdesign.trading.service;

import com.systemdesign.trading.engine.OrderBook;
import com.systemdesign.trading.exception.OrderRejectedException;
import com.systemdesign.trading.model.*;
import com.systemdesign.trading.strategy.risk.PositionLimitStrategy;

import java.util.List;

/**
 * TradingService is the FACADE that orchestrates the entire order lifecycle.
 *
 * This is the single entry point for all trading operations. It coordinates:
 * 1. Order validation (OrderService)
 * 2. Risk checks (RiskService)
 * 3. Margin management (AccountService)
 * 4. Order matching (MatchingService)
 * 5. Position updates (PortfolioService)
 * 6. Market data updates (MarketDataService)
 * 7. User notifications (NotificationService)
 *
 * WHY a Facade:
 * - Clients (TradingController, demo app) only interact with TradingService.
 * - All the complex orchestration is hidden behind simple methods.
 * - Adding a new step (e.g., audit logging) only requires modifying this class.
 * - The order of operations is critical and centralized here.
 *
 * FULL CALL CHAIN for placeOrder():
 * 1. OrderService.validateAndSave(order) — validate fields, save to repo
 * 2. RiskService.runChecks(order, account, marketData) — margin, position limit, circuit breaker
 * 3. AccountService.blockMargin(userId, amount) — reserve funds
 * 4. Order.markOpen() — transition from PENDING_RISK to OPEN
 * 5. MatchingService.submitOrder(order) — match against order book
 * 6. For each trade:
 *    a. PortfolioService.updatePositionOnTrade(trade) — update buyer/seller positions
 *    b. AccountService logic handled during settlement
 * 7. MarketDataService.updateOnTrade(symbol, price, qty) — update LTP, volume
 * 8. MarketDataService.updateBidAsk(symbol, book) — update bid/ask
 * 9. NotificationService — notify users of fills/partial fills
 * 10. If order not fully filled and unfillable → release blocked margin for unfilled portion
 */
public class TradingService {

    private final OrderService orderService;
    private final RiskService riskService;
    private final AccountService accountService;
    private final MatchingService matchingService;
    private final PortfolioService portfolioService;
    private final MarketDataService marketDataService;
    private final NotificationService notificationService;
    private final PositionLimitStrategy positionLimitStrategy;

    public TradingService(OrderService orderService,
                          RiskService riskService,
                          AccountService accountService,
                          MatchingService matchingService,
                          PortfolioService portfolioService,
                          MarketDataService marketDataService,
                          NotificationService notificationService,
                          PositionLimitStrategy positionLimitStrategy) {
        this.orderService = orderService;
        this.riskService = riskService;
        this.accountService = accountService;
        this.matchingService = matchingService;
        this.portfolioService = portfolioService;
        this.marketDataService = marketDataService;
        this.notificationService = notificationService;
        this.positionLimitStrategy = positionLimitStrategy;
    }

    // =====================================================================
    // PLACE ORDER — The main orchestration method
    // =====================================================================

    /**
     * Place a new order through the full lifecycle.
     *
     * @return The order after processing (check status for outcome)
     * @throws OrderRejectedException if risk checks fail
     */
    public Order placeOrder(String userId, String symbol, OrderSide side,
                            OrderType type, double price, int quantity) {
        // Step 0: Build the order
        Order.Builder builder = new Order.Builder(userId, symbol, side, type)
                .quantity(quantity);
        if (price > 0) {
            builder.price(price);
        }
        Order order = builder.build();

        return processOrder(order);
    }

    /**
     * Place a stop-loss order with a trigger price.
     */
    public Order placeStopLossOrder(String userId, String symbol, OrderSide side,
                                    OrderType type, double price, double triggerPrice, int quantity) {
        Order order = new Order.Builder(userId, symbol, side, type)
                .price(price)
                .triggerPrice(triggerPrice)
                .quantity(quantity)
                .build();

        return processOrder(order);
    }

    /**
     * Core order processing pipeline.
     */
    private Order processOrder(Order order) {
        // Step 1: Validate order fields and save to repository
        orderService.validateAndSave(order);
        System.out.printf("  Order created: %s%n", order);

        // Step 2: Run risk checks
        Account account = accountService.getAccount(order.getUserId());
        MarketData marketData = marketDataService.getMarketData(order.getSymbol());

        // Set current position for position limit check
        Position currentPosition = portfolioService.getPosition(order.getUserId(), order.getSymbol());
        if (positionLimitStrategy != null) {
            positionLimitStrategy.setCurrentPositionQuantity(
                    currentPosition != null ? currentPosition.getQuantity() : 0);
        }

        RiskResult riskResult = riskService.runChecks(order, account, marketData);

        if (!riskResult.isPassed()) {
            // Risk check failed — reject the order
            order.reject();
            orderService.updateOrder(order);
            notificationService.notifyOrderRejected(order.getUserId(), order, riskResult.getRejectionReason());
            throw new OrderRejectedException(riskResult.getRejectionReason());
        }

        // Step 3: Block margin for the order
        double marginRequired = calculateMarginRequired(order, marketData);
        if (marginRequired > 0 && order.getSide() == OrderSide.BUY) {
            accountService.blockMargin(order.getUserId(), marginRequired);
        }

        // Step 4: Mark order as OPEN (passed risk checks)
        order.markOpen();
        orderService.updateOrder(order);

        // Step 5: Submit to matching engine
        List<Trade> trades = matchingService.submitOrder(order);

        // Step 6: Process trades
        for (Trade trade : trades) {
            // Update positions for both buyer and seller
            portfolioService.updatePositionOnTrade(trade);

            // Update market data
            marketDataService.updateOnTrade(order.getSymbol(), trade.getPrice(), trade.getQuantity());
        }

        // Step 7: Update bid/ask from order book
        OrderBook book = matchingService.getOrderBook(order.getSymbol());
        if (book != null) {
            marketDataService.updateBidAsk(order.getSymbol(), book);
        }

        // Step 8: Notifications
        if (order.isFilled()) {
            notificationService.notifyOrderFilled(order.getUserId(), order);
        } else if (order.getFilledQuantity() > 0 && !trades.isEmpty()) {
            notificationService.notifyOrderPartialFill(order.getUserId(), order, trades.get(trades.size() - 1));
        }

        // Step 9: Release excess margin for unfilled market orders
        if (order.getType() == OrderType.MARKET && !order.isFilled() && order.getSide() == OrderSide.BUY) {
            double unfilledMargin = marginRequired * ((double) order.getRemainingQuantity() / order.getQuantity());
            if (unfilledMargin > 0) {
                accountService.releaseMargin(order.getUserId(), unfilledMargin);
            }
        }

        // Update order in repository
        orderService.updateOrder(order);

        return order;
    }

    /**
     * Calculate the margin required for an order.
     */
    private double calculateMarginRequired(Order order, MarketData marketData) {
        double effectivePrice = order.getPrice() > 0
                ? order.getPrice()
                : (marketData != null ? marketData.getLtp() : 0.0);
        return effectivePrice * order.getQuantity();
    }

    // =====================================================================
    // CANCEL ORDER
    // =====================================================================

    /**
     * Cancel an existing order.
     */
    public void cancelOrder(String orderId, String symbol) {
        Order order = orderService.getOrder(orderId);
        if (order == null) {
            throw new IllegalArgumentException("Order not found: " + orderId);
        }

        // Remove from matching engine's order book
        boolean removed = matchingService.cancelOrder(orderId, symbol);

        // Cancel the order object
        order.cancel();
        orderService.updateOrder(order);

        // Release blocked margin for unfilled portion
        if (order.getSide() == OrderSide.BUY) {
            double marginToRelease = order.getPrice() * order.getRemainingQuantity();
            if (marginToRelease > 0) {
                accountService.releaseMargin(order.getUserId(), marginToRelease);
            }
        }

        notificationService.notifyOrderCancelled(order.getUserId(), order);
    }

    // =====================================================================
    // QUERY METHODS
    // =====================================================================

    /**
     * Get the order book for a symbol.
     */
    public OrderBook getOrderBook(String symbol) {
        return matchingService.getOrderBook(symbol);
    }

    /**
     * Get all positions for a user (portfolio view).
     */
    public List<Position> getPortfolio(String userId) {
        return portfolioService.getPositions(userId);
    }

    /**
     * Get all orders for a user.
     */
    public List<Order> getUserOrders(String userId) {
        return orderService.getOrdersByUserId(userId);
    }

    /**
     * Get market data for a symbol.
     */
    public MarketData getMarketData(String symbol) {
        return marketDataService.getMarketData(symbol);
    }

    /**
     * Get the portfolio service (for direct P&L access in demos).
     */
    public PortfolioService getPortfolioService() {
        return portfolioService;
    }

    /**
     * Get the account service (for balance queries in demos).
     */
    public AccountService getAccountService() {
        return accountService;
    }

    /**
     * Get the matching service (for direct engine access in demos).
     */
    public MatchingService getMatchingService() {
        return matchingService;
    }

    /**
     * Get the market data service (for updates in demos).
     */
    public MarketDataService getMarketDataService() {
        return marketDataService;
    }
}
