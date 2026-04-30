package com.systemdesign.trading.display;

import com.systemdesign.trading.engine.OrderBook;
import com.systemdesign.trading.model.*;
import com.systemdesign.trading.repository.OrderRepository;
import com.systemdesign.trading.repository.TradeRepository;
import com.systemdesign.trading.service.MarketDataService;
import com.systemdesign.trading.service.MatchingService;
import com.systemdesign.trading.service.PortfolioService;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * TradingStatsDisplay provides aggregated statistics for the trading system.
 *
 * Displays: total orders, trades, fill rate, average trade price, volume by symbol,
 * P&L summary, and order book depth visualization.
 */
public class TradingStatsDisplay {

    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;
    private final MarketDataService marketDataService;
    private final MatchingService matchingService;
    private final PortfolioService portfolioService;

    public TradingStatsDisplay(OrderRepository orderRepository,
                               TradeRepository tradeRepository,
                               MarketDataService marketDataService,
                               MatchingService matchingService,
                               PortfolioService portfolioService) {
        this.orderRepository = orderRepository;
        this.tradeRepository = tradeRepository;
        this.marketDataService = marketDataService;
        this.matchingService = matchingService;
        this.portfolioService = portfolioService;
    }

    /**
     * Print comprehensive trading statistics.
     */
    public void printStats() {
        System.out.println("\n  Trading Statistics");
        System.out.println("  " + "-".repeat(60));

        // Order stats
        List<Order> allOrders = orderRepository.findAll();
        List<Trade> allTrades = tradeRepository.findAll();

        long totalOrders = allOrders.size();
        long filledOrders = allOrders.stream().filter(o -> o.getStatus() == OrderStatus.FILLED).count();
        long partiallyFilled = allOrders.stream().filter(o -> o.getStatus() == OrderStatus.PARTIALLY_FILLED).count();
        long rejectedOrders = allOrders.stream().filter(o -> o.getStatus() == OrderStatus.REJECTED).count();
        long cancelledOrders = allOrders.stream().filter(o -> o.getStatus() == OrderStatus.CANCELLED).count();
        long openOrders = allOrders.stream().filter(o -> o.getStatus() == OrderStatus.OPEN).count();

        double fillRate = totalOrders > 0 ? (double) filledOrders / totalOrders * 100 : 0;

        System.out.printf("  Total Orders:      %d%n", totalOrders);
        System.out.printf("  Filled:            %d%n", filledOrders);
        System.out.printf("  Partially Filled:  %d%n", partiallyFilled);
        System.out.printf("  Open:              %d%n", openOrders);
        System.out.printf("  Rejected:          %d%n", rejectedOrders);
        System.out.printf("  Cancelled:         %d%n", cancelledOrders);
        System.out.printf("  Fill Rate:         %.1f%%%n", fillRate);

        // Trade stats
        System.out.println("\n  Trade Statistics");
        System.out.println("  " + "-".repeat(60));
        System.out.printf("  Total Trades:      %d%n", allTrades.size());

        if (!allTrades.isEmpty()) {
            double totalValue = allTrades.stream().mapToDouble(Trade::getValue).sum();
            double avgPrice = allTrades.stream().mapToDouble(Trade::getPrice).average().orElse(0);
            int totalVolume = allTrades.stream().mapToInt(Trade::getQuantity).sum();

            System.out.printf("  Total Volume:      %,d shares%n", totalVolume);
            System.out.printf("  Total Value:       %.2f%n", totalValue);
            System.out.printf("  Avg Trade Price:   %.2f%n", avgPrice);

            // Volume by symbol
            Map<String, Integer> volumeBySymbol = allTrades.stream()
                    .collect(Collectors.groupingBy(Trade::getSymbol,
                            Collectors.summingInt(Trade::getQuantity)));

            System.out.println("\n  Volume by Symbol:");
            volumeBySymbol.forEach((symbol, volume) ->
                    System.out.printf("    %-10s: %,d shares%n", symbol, volume));
        }

        // Market data summary
        System.out.println("\n  Market Data Summary");
        System.out.println("  " + "-".repeat(60));
        List<MarketData> allMarketData = marketDataService.getAllMarketData();
        System.out.printf("  %-10s | %10s | %10s | %10s | %10s | %8s%n",
                "Symbol", "LTP", "Open", "High", "Low", "Change%");
        for (MarketData md : allMarketData) {
            System.out.printf("  %-10s | %10.2f | %10.2f | %10.2f | %10.2f | %+7.2f%%%n",
                    md.getSymbol(), md.getLtp(), md.getOpen(), md.getHigh(), md.getLow(), md.getChangePercent());
        }
    }

    /**
     * Print order book depth visualization for a symbol.
     */
    public void printOrderBookDepth(String symbol, int levels) {
        OrderBook book = matchingService.getOrderBook(symbol);
        if (book == null) {
            System.out.printf("  No order book for %s%n", symbol);
            return;
        }

        List<OrderBookEntry> bids = book.getBidDepth(levels);
        List<OrderBookEntry> asks = book.getAskDepth(levels);

        System.out.printf("\n  Order Book Depth: %s%n", symbol);
        System.out.println("  " + "=".repeat(55));

        // Display asks in reverse (highest to lowest) so the visualization
        // reads naturally: asks on top, bids on bottom, spread in middle
        System.out.printf("  %-8s | %10s | %10s | %-15s%n", "Side", "Price", "Qty", "Visual");
        System.out.println("  " + "-".repeat(55));

        // Find max quantity for scaling the visual bar
        int maxQty = 1;
        for (OrderBookEntry e : bids) maxQty = Math.max(maxQty, e.getTotalQuantity());
        for (OrderBookEntry e : asks) maxQty = Math.max(maxQty, e.getTotalQuantity());

        // Print asks in reverse order (highest price first, for visual layout)
        for (int i = asks.size() - 1; i >= 0; i--) {
            OrderBookEntry e = asks.get(i);
            int barLen = (int) ((double) e.getTotalQuantity() / maxQty * 20);
            String bar = "#".repeat(Math.max(1, barLen));
            System.out.printf("  %-8s | %10.2f | %,10d | %s%n", "ASK", e.getPrice(), e.getTotalQuantity(), bar);
        }

        // Spread
        double spread = book.getSpread();
        System.out.printf("  %-8s | %10s | %10s | Spread: %.2f%n", "---", "---", "---", spread);

        // Print bids (highest price first — already in correct order)
        for (OrderBookEntry e : bids) {
            int barLen = (int) ((double) e.getTotalQuantity() / maxQty * 20);
            String bar = "#".repeat(Math.max(1, barLen));
            System.out.printf("  %-8s | %10.2f | %,10d | %s%n", "BID", e.getPrice(), e.getTotalQuantity(), bar);
        }

        System.out.println("  " + "=".repeat(55));
        System.out.printf("  Bid Volume: %,d | Ask Volume: %,d%n",
                book.getTotalBidVolume(), book.getTotalAskVolume());
    }
}
