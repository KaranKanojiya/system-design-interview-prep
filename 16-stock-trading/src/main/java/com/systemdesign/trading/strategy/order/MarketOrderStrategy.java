package com.systemdesign.trading.strategy.order;

import com.systemdesign.trading.engine.OrderBook;
import com.systemdesign.trading.engine.PriceLevel;
import com.systemdesign.trading.model.*;

import java.util.ArrayList;
import java.util.List;

/**
 * MarketOrderStrategy executes an order at the best available price.
 *
 * ALGORITHM:
 * - No price check: matches against whatever is available on the opposite side.
 * - Sweeps through price levels until the order is fully filled or the book is empty.
 * - If the book is empty before the order is filled → partial fill (unusual for liquid stocks).
 *
 * WHY market orders are risky:
 * - In a thin order book, the order might "walk the book" — filling at progressively
 *   worse prices. Example: buy 1000 shares, but only 100 available at 2500, next 200
 *   at 2510, next 300 at 2520... average price is much higher than expected (SLIPPAGE).
 * - This is why smart traders check order book depth before placing market orders.
 *
 * WHY market orders DON'T rest in the book:
 * - A market order without a price can't sit in the book (what price level would it go to?).
 * - If not fully filled, it just becomes partially filled. In production, some exchanges
 *   cancel the unfilled remainder; others try to fill at the next available price.
 *
 * CALL CHAIN:
 * MatchingEngine.submitOrder() → MarketOrderStrategy.execute() →
 * sweeps opposite side of book → creates Trade for each price level consumed →
 * returns List<Trade> to MatchingEngine
 */
public class MarketOrderStrategy implements OrderExecutionStrategy {

    @Override
    public List<Trade> execute(Order order, OrderBook book) {
        List<Trade> trades = new ArrayList<>();

        if (order.getSide() == OrderSide.BUY) {
            // BUY MARKET: match against asks (sellers), starting from lowest ask
            matchAgainstAsks(order, book, trades, Double.MAX_VALUE);
        } else {
            // SELL MARKET: match against bids (buyers), starting from highest bid
            matchAgainstBids(order, book, trades, 0.0);
        }

        return trades;
    }

    /**
     * Match a BUY order against the ASK side of the book.
     *
     * WALK-THROUGH (Buy Market for 150 shares):
     *
     * Ask side before:
     *   2500.00 | 100 shares (order A1)
     *   2505.00 | 200 shares (order A2)
     *
     * Step 1: Best ask = 2500. Match min(150, 100) = 100 shares @ 2500 with A1.
     *   → Trade(buyer, A1, 2500, 100). A1 fully filled, removed. Remaining = 50.
     * Step 2: Best ask = 2505. Match min(50, 200) = 50 shares @ 2505 with A2.
     *   → Trade(buyer, A2, 2505, 50). A2 partially filled (150 remaining). Done.
     *
     * Result: 2 trades, avg price = (100*2500 + 50*2505) / 150 = 2501.67
     */
    private void matchAgainstAsks(Order buyOrder, OrderBook book, List<Trade> trades, double priceLimit) {
        while (buyOrder.getRemainingQuantity() > 0 && book.hasAsks()) {
            PriceLevel bestAsk = book.getBestAsk();
            if (bestAsk == null || bestAsk.isEmpty()) break;

            // For limit orders, check price constraint
            if (bestAsk.getPrice() > priceLimit) break;

            Order sellOrder = bestAsk.peekFirst();
            if (sellOrder == null) break;

            // Determine fill quantity: minimum of buyer's remaining and seller's remaining
            int fillQty = Math.min(buyOrder.getRemainingQuantity(), sellOrder.getRemainingQuantity());
            double fillPrice = sellOrder.getPrice(); // Trade executes at passive (maker) order's price

            // Create the trade
            Trade trade = new Trade(
                    buyOrder.getOrderId(), sellOrder.getOrderId(),
                    buyOrder.getUserId(), sellOrder.getUserId(),
                    buyOrder.getSymbol(), fillPrice, fillQty
            );
            trades.add(trade);

            // Update both orders
            buyOrder.fill(fillQty, trade);
            sellOrder.fill(fillQty, trade);

            // Update the price level's tracked quantity
            bestAsk.updateQuantityAfterFill(fillQty);

            // If sell order fully filled, remove it from the book
            if (sellOrder.isFilled()) {
                bestAsk.removeFirst();
            }

            // If price level is now empty, remove it from the tree
            if (bestAsk.isEmpty()) {
                book.removeBestAsk();
            }
        }
    }

    /**
     * Match a SELL order against the BID side of the book.
     * Mirror of matchAgainstAsks but against bids (highest first).
     */
    private void matchAgainstBids(Order sellOrder, OrderBook book, List<Trade> trades, double priceLimit) {
        while (sellOrder.getRemainingQuantity() > 0 && book.hasBids()) {
            PriceLevel bestBid = book.getBestBid();
            if (bestBid == null || bestBid.isEmpty()) break;

            // For limit orders, check price constraint (sell only at or above limit)
            if (bestBid.getPrice() < priceLimit) break;

            Order buyOrder = bestBid.peekFirst();
            if (buyOrder == null) break;

            int fillQty = Math.min(sellOrder.getRemainingQuantity(), buyOrder.getRemainingQuantity());
            double fillPrice = buyOrder.getPrice(); // Trade at passive (maker) order's price

            Trade trade = new Trade(
                    buyOrder.getOrderId(), sellOrder.getOrderId(),
                    buyOrder.getUserId(), sellOrder.getUserId(),
                    sellOrder.getSymbol(), fillPrice, fillQty
            );
            trades.add(trade);

            buyOrder.fill(fillQty, trade);
            sellOrder.fill(fillQty, trade);

            bestBid.updateQuantityAfterFill(fillQty);

            if (buyOrder.isFilled()) {
                bestBid.removeFirst();
            }

            if (bestBid.isEmpty()) {
                book.removeBestBid();
            }
        }
    }
}
