package com.systemdesign.trading;

import com.systemdesign.trading.config.AppConfig;
import com.systemdesign.trading.controller.TradingController;
import com.systemdesign.trading.display.TradingStatsDisplay;
import com.systemdesign.trading.exception.OrderRejectedException;
import com.systemdesign.trading.model.*;
import com.systemdesign.trading.service.*;

/**
 * StockTradingApp demonstrates the complete Stock Trading Platform.
 *
 * 12 demos covering: order book mechanics, matching algorithms, partial fills,
 * price-time priority, stop-loss, risk checks, circuit breakers, P&L tracking,
 * multi-symbol trading, and T+1 settlement.
 *
 * ARCHITECTURE SUMMARY:
 * - MatchingEngine with per-symbol OrderBooks (TreeMap bids desc, asks asc)
 * - PriceLevel as FIFO LinkedList for time priority
 * - Strategy pattern for order execution (Market/Limit), risk checks (Margin/Position/Circuit),
 *   and P&L calculation (FIFO/AvgCost)
 * - TradingService as facade orchestrating the full order lifecycle
 * - Synchronized Account operations for thread safety
 */
public class StockTradingApp {

    private static final String SEPARATOR = "=".repeat(70);

    public static void main(String[] args) {
        System.out.println(SEPARATOR);
        System.out.println("  STOCK TRADING PLATFORM - System Design Demo");
        System.out.println("  (Zerodha / Upstox / Robinhood style)");
        System.out.println(SEPARATOR);

        // Initialize the entire system via AppConfig (composition root)
        System.out.println("\n  Initializing system...");
        AppConfig config = new AppConfig();

        TradingService tradingService = config.getTradingService();
        TradingController controller = config.getTradingController();
        TradingStatsDisplay statsDisplay = config.getStatsDisplay();
        SettlementService settlementService = config.getSettlementService();

        System.out.println("  System ready!\n");

        // Run all demos
        demo1_LimitOrdersAndOrderBook(tradingService, controller);
        demo2_OrderMatchingBuyMeetsSell(tradingService, controller);
        demo3_MarketOrderExecution(tradingService, controller);
        demo4_PartialFill(tradingService, controller);
        demo5_PriceTimePriority(tradingService, controller);
        demo6_StopLossOrder(tradingService);
        demo7_MarginRejection(tradingService);
        demo8_CircuitBreaker(tradingService, config);
        demo9_PortfolioAndPnL(tradingService, controller);
        demo10_OrderBookDepthVisualization(tradingService, statsDisplay);
        demo11_MultiSymbolTrading(tradingService, controller);
        demo12_SettlementSimulation(settlementService, config);

        // Final stats
        System.out.println("\n" + SEPARATOR);
        System.out.println("  FINAL SYSTEM STATISTICS");
        System.out.println(SEPARATOR);
        statsDisplay.printStats();

        // Design summary
        printDesignSummary();
    }

    // =====================================================================
    // DEMO 1: Place Limit Orders & Order Book
    // =====================================================================
    private static void demo1_LimitOrdersAndOrderBook(TradingService service, TradingController controller) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 1: Place Limit Orders & Order Book");
        System.out.println(SEPARATOR);
        System.out.println("  Placing buy and sell limit orders for RELIANCE.");
        System.out.println("  These orders will sit in the order book waiting for matches.\n");

        // Place several buy limit orders at different prices (small qty to fit margin)
        service.placeOrder("trader1", "RELIANCE", OrderSide.BUY, OrderType.LIMIT, 2495.00, 50);
        service.placeOrder("trader1", "RELIANCE", OrderSide.BUY, OrderType.LIMIT, 2490.00, 50);
        service.placeOrder("trader2", "RELIANCE", OrderSide.BUY, OrderType.LIMIT, 2485.00, 50);

        // Place several sell limit orders at different prices
        service.placeOrder("institution1", "RELIANCE", OrderSide.SELL, OrderType.LIMIT, 2505.00, 100);
        service.placeOrder("institution1", "RELIANCE", OrderSide.SELL, OrderType.LIMIT, 2510.00, 100);
        service.placeOrder("institution1", "RELIANCE", OrderSide.SELL, OrderType.LIMIT, 2515.00, 100);

        System.out.println("\n  Order book after placing 6 limit orders:");
        controller.handleGetOrderBook("RELIANCE", 5);

        System.out.println("\n  KEY INSIGHT: Bids (buy) are sorted highest-first (TreeMap reverseOrder).");
        System.out.println("  Asks (sell) are sorted lowest-first (TreeMap natural order).");
        System.out.println("  The gap between best bid (2495) and best ask (2505) is the SPREAD.\n");
    }

    // =====================================================================
    // DEMO 2: Order Matching - Buy Meets Sell
    // =====================================================================
    private static void demo2_OrderMatchingBuyMeetsSell(TradingService service, TradingController controller) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 2: Order Matching - Buy Meets Sell");
        System.out.println(SEPARATOR);
        System.out.println("  A buy limit at 2505 will cross the existing sell at 2505.");
        System.out.println("  When buy price >= ask price, a trade executes!\n");

        // This buy at 2505 will match against the existing sell at 2505
        Order matched = service.placeOrder("trader1", "RELIANCE", OrderSide.BUY, OrderType.LIMIT, 2505.00, 50);

        System.out.printf("\n  Result: Order %s, status=%s, filled=%d/%d%n",
                matched.getOrderId(), matched.getStatus(),
                matched.getFilledQuantity(), matched.getQuantity());

        if (!matched.getTrades().isEmpty()) {
            Trade trade = matched.getTrades().get(0);
            System.out.printf("  Trade executed: %s%n", trade);
        }

        System.out.println("\n  Order book after match (50 of 100 sell at 2505 consumed):");
        controller.handleGetOrderBook("RELIANCE", 5);

        System.out.println("\n  KEY INSIGHT: Trade executes at the PASSIVE order's price (2505,");
        System.out.println("  the resting sell order's price), not the aggressive order's price.\n");
    }

    // =====================================================================
    // DEMO 3: Market Order Execution
    // =====================================================================
    private static void demo3_MarketOrderExecution(TradingService service, TradingController controller) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 3: Market Order Execution");
        System.out.println(SEPARATOR);
        System.out.println("  A MARKET BUY sweeps the best available asks, regardless of price.");
        System.out.println("  It's fast but you don't control the price (risk of slippage).\n");

        // Market buy for 80 shares — will eat into the ask side
        Order marketOrder = service.placeOrder("trader1", "RELIANCE", OrderSide.BUY, OrderType.MARKET, 0, 80);

        System.out.printf("\n  Market order result: status=%s, filled=%d/%d%n",
                marketOrder.getStatus(), marketOrder.getFilledQuantity(), marketOrder.getQuantity());

        for (Trade trade : marketOrder.getTrades()) {
            System.out.printf("  Trade: %d shares @ %.2f%n", trade.getQuantity(), trade.getPrice());
        }

        System.out.println("\n  Order book after market order:");
        controller.handleGetOrderBook("RELIANCE", 5);

        System.out.println("\n  KEY INSIGHT: Market orders consume liquidity. The ask side got thinner.\n");
    }

    // =====================================================================
    // DEMO 4: Partial Fill
    // =====================================================================
    private static void demo4_PartialFill(TradingService service, TradingController controller) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 4: Partial Fill");
        System.out.println(SEPARATOR);
        System.out.println("  A large buy order matched against multiple smaller sells.");
        System.out.println("  This demonstrates the order book walking through multiple price levels.\n");

        // First, add some fresh sell orders
        service.placeOrder("trader2", "RELIANCE", OrderSide.SELL, OrderType.LIMIT, 2512.00, 50);
        service.placeOrder("institution1", "RELIANCE", OrderSide.SELL, OrderType.LIMIT, 2513.00, 60);

        System.out.println("  Added 2 new sell orders. Now placing large buy limit at 2515...\n");

        // Large buy that will sweep multiple ask levels
        Order largeBuy = service.placeOrder("institution1", "RELIANCE", OrderSide.BUY, OrderType.LIMIT, 2515.00, 400);

        System.out.printf("\n  Large buy result: status=%s, filled=%d/%d, trades=%d%n",
                largeBuy.getStatus(), largeBuy.getFilledQuantity(), largeBuy.getQuantity(),
                largeBuy.getTrades().size());

        for (Trade trade : largeBuy.getTrades()) {
            System.out.printf("  Partial fill: %d shares @ %.2f (from %s)%n",
                    trade.getQuantity(), trade.getPrice(), trade.getSellerUserId());
        }

        if (largeBuy.getRemainingQuantity() > 0) {
            System.out.printf("  Remaining %d shares resting in book at %.2f%n",
                    largeBuy.getRemainingQuantity(), largeBuy.getPrice());
        }

        System.out.println("\n  Order book after partial fill:");
        controller.handleGetOrderBook("RELIANCE", 5);

        System.out.println("\n  KEY INSIGHT: One order can generate MULTIPLE trades at different prices.\n");
    }

    // =====================================================================
    // DEMO 5: Price-Time Priority
    // =====================================================================
    private static void demo5_PriceTimePriority(TradingService service, TradingController controller) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 5: Price-Time Priority");
        System.out.println(SEPARATOR);
        System.out.println("  Two sells at the same price — the EARLIER one fills first (FIFO).");
        System.out.println("  This is price-time priority: price first, then time.\n");

        // Place two sell orders at the same price — order of placement matters
        Order sell1 = service.placeOrder("trader2", "TCS", OrderSide.SELL, OrderType.LIMIT, 3810.00, 50);
        System.out.printf("  Sell #1 placed: %s at %.2f%n", sell1.getOrderId(), sell1.getPrice());

        Order sell2 = service.placeOrder("institution1", "TCS", OrderSide.SELL, OrderType.LIMIT, 3810.00, 50);
        System.out.printf("  Sell #2 placed: %s at %.2f%n", sell2.getOrderId(), sell2.getPrice());

        System.out.println("  Now placing buy for 50 shares at 3810...\n");

        // Buy 50 — should match against sell1 (placed first, FIFO)
        Order buy = service.placeOrder("trader1", "TCS", OrderSide.BUY, OrderType.LIMIT, 3810.00, 50);

        System.out.printf("  Buy matched with: %s%n",
                buy.getTrades().isEmpty() ? "no one" : buy.getTrades().get(0).getSellerUserId());
        System.out.printf("  Sell #1 (%s) status: %s%n", sell1.getOrderId(), sell1.getStatus());
        System.out.printf("  Sell #2 (%s) status: %s%n", sell2.getOrderId(), sell2.getStatus());

        System.out.println("\n  KEY INSIGHT: At the same price, the FIRST order placed gets filled first.");
        System.out.println("  This is why PriceLevel uses a LinkedList (FIFO queue).\n");
    }

    // =====================================================================
    // DEMO 6: Stop-Loss Order Trigger
    // =====================================================================
    private static void demo6_StopLossOrder(TradingService service) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 6: Stop-Loss Order Trigger");
        System.out.println(SEPARATOR);
        System.out.println("  A stop-loss order becomes a market order when the trigger price is hit.");
        System.out.println("  Used for protecting against losses.\n");

        // Simulate: trader holds INFY, sets stop-loss at 1580
        // First, set up some bids in the INFY book
        service.placeOrder("institution1", "INFY", OrderSide.BUY, OrderType.LIMIT, 1580.00, 100);
        service.placeOrder("institution1", "INFY", OrderSide.BUY, OrderType.LIMIT, 1575.00, 200);
        System.out.println("  Set up buy orders at 1580 and 1575 in INFY book.");

        // Place stop-loss sell (trigger at 1585, will execute as market when triggered)
        // For demo: we simulate the trigger by directly placing as STOP_LOSS
        // In a full implementation, a price feed would trigger this when LTP <= triggerPrice
        try {
            Order stopLoss = service.placeStopLossOrder(
                    "trader1", "INFY", OrderSide.SELL, OrderType.STOP_LOSS,
                    0, 1585.00, 100);
            System.out.printf("  Stop-loss triggered and executed: %s, filled=%d @ trades=%d%n",
                    stopLoss.getStatus(), stopLoss.getFilledQuantity(), stopLoss.getTrades().size());

            for (Trade trade : stopLoss.getTrades()) {
                System.out.printf("  Executed at: %.2f (slipped from trigger 1585 to %.2f)%n",
                        trade.getPrice(), trade.getPrice());
            }
        } catch (Exception e) {
            System.out.println("  Stop-loss demo: " + e.getMessage());
        }

        System.out.println("\n  KEY INSIGHT: Stop-loss becomes a market order — price is NOT guaranteed.");
        System.out.println("  The execution price may be worse than the trigger (slippage).\n");
    }

    // =====================================================================
    // DEMO 7: Risk Check - Margin Rejection
    // =====================================================================
    private static void demo7_MarginRejection(TradingService service) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 7: Risk Check - Margin Rejection");
        System.out.println(SEPARATOR);
        System.out.println("  Attempting to buy more than the account can afford.");
        System.out.println("  Risk checks run BEFORE the order enters the matching engine.\n");

        // trader2 has 300K balance. Try to buy RELIANCE worth ~500K
        Account account = service.getAccountService().getAccount("trader2");
        System.out.printf("  trader2 account: %s%n", account);
        System.out.printf("  Attempting to buy 200 RELIANCE @ 2500 (total = 500,000)...\n\n");

        try {
            service.placeOrder("trader2", "RELIANCE", OrderSide.BUY, OrderType.LIMIT, 2500.00, 200);
            System.out.println("  Order placed (unexpected — should have been rejected)");
        } catch (OrderRejectedException e) {
            System.out.printf("  ORDER REJECTED: %s%n", e.getMessage());
        }

        System.out.println("\n  KEY INSIGHT: Risk checks prevent orders that could cause financial harm.");
        System.out.println("  Margin is checked BEFORE the order enters the book.\n");
    }

    // =====================================================================
    // DEMO 8: Circuit Breaker
    // =====================================================================
    private static void demo8_CircuitBreaker(TradingService service, AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 8: Circuit Breaker");
        System.out.println(SEPARATOR);
        System.out.println("  Orders outside circuit limits are rejected.");
        System.out.println("  RELIANCE circuit: [2000, 3000]. Trying to buy at 3100...\n");

        try {
            service.placeOrder("institution1", "RELIANCE", OrderSide.BUY, OrderType.LIMIT, 3100.00, 10);
            System.out.println("  Order placed (unexpected)");
        } catch (OrderRejectedException e) {
            System.out.printf("  CIRCUIT BREAKER TRIGGERED: %s%n", e.getMessage());
        }

        // Also test trading halt
        System.out.println("\n  Now testing trading halt...");
        Stock reliance = config.getStockRepository().findBySymbol("RELIANCE");
        reliance.setTradingHalted(true);
        System.out.printf("  RELIANCE trading halted: %s%n", reliance.isTradingHalted());

        try {
            service.placeOrder("trader1", "RELIANCE", OrderSide.BUY, OrderType.LIMIT, 2500.00, 10);
            System.out.println("  Order placed (unexpected)");
        } catch (OrderRejectedException e) {
            System.out.printf("  HALT REJECTION: %s%n", e.getMessage());
        }

        // Resume trading for subsequent demos
        reliance.setTradingHalted(false);
        System.out.println("  Trading resumed for RELIANCE.\n");

        System.out.println("  KEY INSIGHT: Circuit breakers protect against extreme price movements");
        System.out.println("  and give markets time to process information during volatile events.\n");
    }

    // =====================================================================
    // DEMO 9: Portfolio & P&L Tracking
    // =====================================================================
    private static void demo9_PortfolioAndPnL(TradingService service, TradingController controller) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 9: Portfolio & P&L Tracking");
        System.out.println(SEPARATOR);
        System.out.println("  After trades, positions update. Unrealized P&L changes with market price.\n");

        // Set up a clean trade for P&L demo
        service.placeOrder("trader2", "HDFC", OrderSide.SELL, OrderType.LIMIT, 1700.00, 100);
        Order hdBuy = service.placeOrder("trader1", "HDFC", OrderSide.BUY, OrderType.LIMIT, 1700.00, 100);
        System.out.printf("  trader1 bought 100 HDFC @ 1700 (total cost: 170,000)%n");

        // Simulate price increase
        System.out.println("\n  Simulating HDFC price increase to 1750...");
        service.getPortfolioService().updateMarketPrice("trader1", "HDFC", 1750.00);

        System.out.println("\n  Portfolio for trader1:");
        controller.handleGetPortfolio("trader1");

        System.out.println("\n  KEY INSIGHT: Unrealized P&L = (currentPrice - avgBuyPrice) * quantity.");
        System.out.println("  HDFC: (1750 - 1700) * 100 = +5,000 unrealized profit.\n");
    }

    // =====================================================================
    // DEMO 10: Order Book Depth Visualization
    // =====================================================================
    private static void demo10_OrderBookDepthVisualization(TradingService service, TradingStatsDisplay statsDisplay) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 10: Order Book Depth Visualization");
        System.out.println(SEPARATOR);
        System.out.println("  Visual representation of order book depth with bid/ask levels.\n");

        // Add more orders to INFY for a richer order book
        service.placeOrder("trader1", "INFY", OrderSide.BUY, OrderType.LIMIT, 1595.00, 300);
        service.placeOrder("trader2", "INFY", OrderSide.BUY, OrderType.LIMIT, 1590.00, 500);
        service.placeOrder("institution1", "INFY", OrderSide.BUY, OrderType.LIMIT, 1585.00, 200);

        service.placeOrder("trader1", "INFY", OrderSide.SELL, OrderType.LIMIT, 1605.00, 250);
        service.placeOrder("trader2", "INFY", OrderSide.SELL, OrderType.LIMIT, 1610.00, 400);
        service.placeOrder("institution1", "INFY", OrderSide.SELL, OrderType.LIMIT, 1615.00, 150);

        statsDisplay.printOrderBookDepth("INFY", 5);

        System.out.println("\n  KEY INSIGHT: Order book depth shows liquidity at each price level.");
        System.out.println("  Wider bars = more shares available. Helps traders assess market impact.\n");
    }

    // =====================================================================
    // DEMO 11: Multi-Symbol Trading
    // =====================================================================
    private static void demo11_MultiSymbolTrading(TradingService service, TradingController controller) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 11: Multi-Symbol Trading");
        System.out.println(SEPARATOR);
        System.out.println("  Trading RELIANCE, TCS, and ICICI simultaneously.\n");

        // RELIANCE trade
        service.placeOrder("trader2", "RELIANCE", OrderSide.SELL, OrderType.LIMIT, 2498.00, 50);
        Order relBuy = service.placeOrder("trader1", "RELIANCE", OrderSide.BUY, OrderType.LIMIT, 2498.00, 50);
        System.out.printf("  RELIANCE: %s (%d filled)%n", relBuy.getStatus(), relBuy.getFilledQuantity());

        // TCS trade
        service.placeOrder("trader1", "TCS", OrderSide.SELL, OrderType.LIMIT, 3805.00, 30);
        Order tcsBuy = service.placeOrder("trader2", "TCS", OrderSide.BUY, OrderType.LIMIT, 3805.00, 30);
        System.out.printf("  TCS: %s (%d filled)%n", tcsBuy.getStatus(), tcsBuy.getFilledQuantity());

        // ICICI trade
        service.placeOrder("institution1", "ICICI", OrderSide.SELL, OrderType.LIMIT, 1095.00, 200);
        Order iciciBuy = service.placeOrder("trader1", "ICICI", OrderSide.BUY, OrderType.LIMIT, 1095.00, 200);
        System.out.printf("  ICICI: %s (%d filled)%n", iciciBuy.getStatus(), iciciBuy.getFilledQuantity());

        // Show market data for all symbols
        System.out.println("\n  Market data after multi-symbol trades:");
        controller.handleGetMarketData("RELIANCE");
        controller.handleGetMarketData("TCS");
        controller.handleGetMarketData("ICICI");

        System.out.println("\n  KEY INSIGHT: Each symbol has its own order book. Matching engines");
        System.out.println("  for different symbols can run independently (parallelizable).\n");
    }

    // =====================================================================
    // DEMO 12: Settlement Simulation
    // =====================================================================
    private static void demo12_SettlementSimulation(SettlementService settlementService, AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 12: Settlement Simulation (T+1)");
        System.out.println(SEPARATOR);
        System.out.println("  In India, equity trades settle T+1 (trade today, settle next day).");
        System.out.println("  Settlement transfers funds from buyer to seller.\n");

        // Show account balances before settlement
        System.out.println("  Account balances BEFORE settlement:");
        Account t1 = config.getAccountService().getAccount("trader1");
        Account t2 = config.getAccountService().getAccount("trader2");
        Account i1 = config.getAccountService().getAccount("institution1");
        System.out.printf("    trader1:      %.2f%n", t1.getBalance());
        System.out.printf("    trader2:      %.2f%n", t2.getBalance());
        System.out.printf("    institution1: %.2f%n", i1.getBalance());

        System.out.println("\n  Running settlement...\n");
        int settled = settlementService.settleTradesForDay();

        System.out.println("\n  Account balances AFTER settlement:");
        System.out.printf("    trader1:      %.2f%n", t1.getBalance());
        System.out.printf("    trader2:      %.2f%n", t2.getBalance());
        System.out.printf("    institution1: %.2f%n", i1.getBalance());

        System.out.printf("\n  Total trades settled: %d%n", settled);
        System.out.println("\n  KEY INSIGHT: Settlement is when actual money and shares transfer.");
        System.out.println("  Before settlement, funds are just 'blocked' (margin).\n");
    }

    // =====================================================================
    // DESIGN SUMMARY
    // =====================================================================
    private static void printDesignSummary() {
        System.out.println("\n" + SEPARATOR);
        System.out.println("  DESIGN SUMMARY — Stock Trading Platform");
        System.out.println(SEPARATOR);
        System.out.println("""
          CORE DATA STRUCTURES:
            - OrderBook: TreeMap<Double, PriceLevel> — bids descending, asks ascending
            - PriceLevel: LinkedList<Order> — FIFO queue for price-time priority
            - Both give O(log N) insert/remove with automatic sorting

          MATCHING ALGORITHM:
            - Price-time priority: best price first, then earliest order
            - Market orders: sweep the book at any price
            - Limit orders: match only at favorable prices, rest in book if unmatched

          DESIGN PATTERNS:
            - Strategy: OrderExecutionStrategy (Market/Limit), RiskCheckStrategy
              (Margin/Position/Circuit), PnLStrategy (FIFO/AvgCost)
            - Builder: Order construction with many optional fields
            - Facade: TradingService orchestrates the entire order lifecycle
            - Repository: Interface + InMemory impl for storage abstraction

          KEY EDGE CASES HANDLED:
            - Partial fills: one order → multiple trades at different prices
            - Price-time priority: FIFO within same price level
            - Circuit breaker: reject orders outside price bands
            - Margin check: prevent orders exceeding available funds
            - Stop-loss: trigger conversion from stop to market order
            - Trading halt: reject all orders when halted
            - T+1 settlement: separate margin blocking from actual fund transfer

          THREAD SAFETY:
            - Account operations synchronized (balance, margin)
            - ConcurrentHashMap for repositories
            - Per-symbol order book processing (parallelizable across symbols)

          SCALABILITY NOTES:
            - Each symbol's order book is independent → shard by symbol
            - Matching engine per symbol → horizontal scaling
            - Event sourcing for order state (not shown but design supports it)
            - Market data via pub/sub (simulated with console print)
        """);
        System.out.println(SEPARATOR);
        System.out.println("  END OF DEMO");
        System.out.println(SEPARATOR);
    }
}
