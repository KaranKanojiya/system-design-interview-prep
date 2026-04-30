package com.systemdesign.trading.controller;

import com.systemdesign.trading.engine.OrderBook;
import com.systemdesign.trading.exception.TradingException;
import com.systemdesign.trading.model.*;
import com.systemdesign.trading.service.TradingService;

import java.util.List;

/**
 * TradingController simulates a REST API controller for trading operations.
 *
 * WHY simulated REST:
 * - In production, this would be a Spring @RestController with HTTP endpoints.
 * - Here we simulate it with plain method calls that mirror REST semantics:
 *   POST /orders → handlePlaceOrder()
 *   DELETE /orders/{id} → handleCancelOrder()
 *   GET /orderbook/{symbol} → handleGetOrderBook()
 *   GET /portfolio/{userId} → handleGetPortfolio()
 *   GET /market-data/{symbol} → handleGetMarketData()
 *   GET /orders?userId={id} → handleGetOrders()
 *
 * WHY error handling is here (not in service):
 * - Controller is the boundary between external input and internal logic.
 * - It catches exceptions and converts them to user-friendly responses.
 * - In production: TradingException → 400 Bad Request, unexpected → 500 Internal Server Error.
 *
 * CALL CHAIN:
 * External request → TradingController.handleXxx() → TradingService.xxx() →
 * [full orchestration pipeline] → TradingController formats response → external response
 */
public class TradingController {

    private final TradingService tradingService;

    public TradingController(TradingService tradingService) {
        this.tradingService = tradingService;
    }

    /**
     * Simulated POST /orders
     * Place a new order.
     */
    public Order handlePlaceOrder(String userId, String symbol, OrderSide side,
                                  OrderType type, double price, int quantity) {
        try {
            Order order = tradingService.placeOrder(userId, symbol, side, type, price, quantity);
            System.out.printf("  [API] Order placed successfully: %s%n", order.getOrderId());
            return order;
        } catch (TradingException e) {
            System.out.printf("  [API] Order failed: %s%n", e.getMessage());
            return null;
        }
    }

    /**
     * Simulated DELETE /orders/{orderId}
     * Cancel an existing order.
     */
    public boolean handleCancelOrder(String orderId, String symbol) {
        try {
            tradingService.cancelOrder(orderId, symbol);
            System.out.printf("  [API] Order %s cancelled successfully%n", orderId);
            return true;
        } catch (TradingException e) {
            System.out.printf("  [API] Cancel failed: %s%n", e.getMessage());
            return false;
        }
    }

    /**
     * Simulated GET /orderbook/{symbol}
     * Get order book depth for a symbol.
     */
    public void handleGetOrderBook(String symbol, int depth) {
        OrderBook book = tradingService.getOrderBook(symbol);
        if (book == null) {
            System.out.printf("  [API] No order book for %s%n", symbol);
            return;
        }

        List<OrderBookEntry> bids = book.getBidDepth(depth);
        List<OrderBookEntry> asks = book.getAskDepth(depth);

        System.out.printf("  Order Book: %s (spread: %.2f)%n", symbol, book.getSpread());
        System.out.println("  " + "-".repeat(50));
        System.out.printf("  %-20s | %-20s%n", "BIDS (Buy)", "ASKS (Sell)");
        System.out.println("  " + "-".repeat(50));

        int maxRows = Math.max(bids.size(), asks.size());
        for (int i = 0; i < maxRows; i++) {
            String bidStr = i < bids.size()
                    ? String.format("%,d @ %.2f", bids.get(i).getTotalQuantity(), bids.get(i).getPrice())
                    : "";
            String askStr = i < asks.size()
                    ? String.format("%.2f @ %,d", asks.get(i).getPrice(), asks.get(i).getTotalQuantity())
                    : "";
            System.out.printf("  %-20s | %-20s%n", bidStr, askStr);
        }
        System.out.println("  " + "-".repeat(50));
        System.out.printf("  Total Bid Vol: %,d | Total Ask Vol: %,d%n",
                book.getTotalBidVolume(), book.getTotalAskVolume());
    }

    /**
     * Simulated GET /portfolio/{userId}
     * Get portfolio (positions) for a user.
     */
    public void handleGetPortfolio(String userId) {
        List<Position> positions = tradingService.getPortfolio(userId);
        if (positions.isEmpty()) {
            System.out.printf("  [API] No positions for user %s%n", userId);
            return;
        }

        System.out.printf("  Portfolio for %s:%n", userId);
        System.out.println("  " + "-".repeat(80));
        System.out.printf("  %-10s | %8s | %10s | %10s | %12s | %12s%n",
                "Symbol", "Qty", "Avg Price", "Current", "Unrealized", "Realized");
        System.out.println("  " + "-".repeat(80));

        double totalUnrealized = 0;
        double totalRealized = 0;
        for (Position pos : positions) {
            if (pos.getQuantity() != 0) {
                System.out.printf("  %-10s | %8d | %10.2f | %10.2f | %12.2f | %12.2f%n",
                        pos.getSymbol(), pos.getQuantity(), pos.getAvgBuyPrice(),
                        pos.getCurrentPrice(), pos.getUnrealizedPnL(), pos.getRealizedPnL());
                totalUnrealized += pos.getUnrealizedPnL();
                totalRealized += pos.getRealizedPnL();
            }
        }
        System.out.println("  " + "-".repeat(80));
        System.out.printf("  Total: Unrealized=%.2f, Realized=%.2f, Net=%.2f%n",
                totalUnrealized, totalRealized, totalUnrealized + totalRealized);
    }

    /**
     * Simulated GET /market-data/{symbol}
     * Get current market data for a symbol.
     */
    public void handleGetMarketData(String symbol) {
        MarketData data = tradingService.getMarketData(symbol);
        if (data == null) {
            System.out.printf("  [API] No market data for %s%n", symbol);
            return;
        }
        System.out.printf("  Market Data: %s%n", data);
    }

    /**
     * Simulated GET /orders?userId={userId}
     * Get all orders for a user.
     */
    public void handleGetOrders(String userId) {
        List<Order> orders = tradingService.getUserOrders(userId);
        if (orders.isEmpty()) {
            System.out.printf("  [API] No orders for user %s%n", userId);
            return;
        }

        System.out.printf("  Orders for %s:%n", userId);
        for (Order order : orders) {
            System.out.printf("  %s%n", order);
        }
    }
}
