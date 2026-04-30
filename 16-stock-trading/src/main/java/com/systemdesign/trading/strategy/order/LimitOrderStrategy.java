package com.systemdesign.trading.strategy.order;

import com.systemdesign.trading.engine.OrderBook;
import com.systemdesign.trading.engine.PriceLevel;
import com.systemdesign.trading.model.*;

import java.util.ArrayList;
import java.util.List;

/**
 * LimitOrderStrategy matches an order only at or better than the specified limit price.
 *
 * MATCHING RULES:
 * - BUY LIMIT at $105: match against asks with price <= $105.
 *   The buyer says "I'll pay UP TO $105". If best ask is $103, great — trade at $103.
 *   If best ask is $106, no match — order rests in the book at $105.
 * - SELL LIMIT at $95: match against bids with price >= $95.
 *   The seller says "I'll accept DOWN TO $95". If best bid is $97, trade at $97.
 *
 * KEY DIFFERENCE FROM MARKET:
 * - Market orders always fill (or partially fill if book is thin).
 * - Limit orders may not fill at all if the price isn't right → they "rest" in the book.
 * - Resting limit orders PROVIDE LIQUIDITY (they ARE the order book).
 * - Market orders CONSUME LIQUIDITY (they eat resting orders).
 *
 * WHY the remaining quantity goes into the book:
 * - If a limit buy at $105 for 200 shares matches 100 against asks, the remaining
 *   100 shares become a new bid at $105 in the order book. They'll match when a
 *   new sell order arrives at $105 or below.
 *
 * CALL CHAIN:
 * MatchingEngine.submitOrder() → LimitOrderStrategy.execute() →
 * match against opposite side within price limit → if remainder → book.addOrder()
 */
public class LimitOrderStrategy implements OrderExecutionStrategy {

    @Override
    public List<Trade> execute(Order order, OrderBook book) {
        List<Trade> trades = new ArrayList<>();

        if (order.getSide() == OrderSide.BUY) {
            // BUY LIMIT: match against asks where ask price <= order's limit price
            matchAgainstAsks(order, book, trades);

            // If not fully filled, add remainder to the BID side of the book
            if (order.getRemainingQuantity() > 0 && order.isActive()) {
                book.addOrder(order);
            }
        } else {
            // SELL LIMIT: match against bids where bid price >= order's limit price
            matchAgainstBids(order, book, trades);

            // If not fully filled, add remainder to the ASK side of the book
            if (order.getRemainingQuantity() > 0 && order.isActive()) {
                book.addOrder(order);
            }
        }

        return trades;
    }

    /**
     * Match a BUY LIMIT order against asks.
     *
     * WALK-THROUGH (Buy Limit $105 for 200 shares):
     *
     * Ask side:
     *   $103.00 | 80 shares   ← below limit, match!
     *   $104.50 | 100 shares  ← below limit, match!
     *   $106.00 | 50 shares   ← ABOVE limit, STOP
     *
     * Step 1: Match 80 @ $103 → Trade. Remaining = 120.
     * Step 2: Match 100 @ $104.50 → Trade. Remaining = 20.
     * Step 3: $106 > $105 limit → STOP. Remaining 20 shares added to book as bid at $105.
     */
    private void matchAgainstAsks(Order buyOrder, OrderBook book, List<Trade> trades) {
        double priceLimit = buyOrder.getPrice(); // The buyer's maximum acceptable price

        while (buyOrder.getRemainingQuantity() > 0 && book.hasAsks()) {
            PriceLevel bestAsk = book.getBestAsk();
            if (bestAsk == null || bestAsk.isEmpty()) break;

            // PRICE CHECK: only match if ask price is at or below buyer's limit
            if (bestAsk.getPrice() > priceLimit) {
                break; // No more matchable asks — remaining rests in book
            }

            Order sellOrder = bestAsk.peekFirst();
            if (sellOrder == null) break;

            int fillQty = Math.min(buyOrder.getRemainingQuantity(), sellOrder.getRemainingQuantity());
            double fillPrice = sellOrder.getPrice(); // Execute at the passive (resting) order's price

            Trade trade = new Trade(
                    buyOrder.getOrderId(), sellOrder.getOrderId(),
                    buyOrder.getUserId(), sellOrder.getUserId(),
                    buyOrder.getSymbol(), fillPrice, fillQty
            );
            trades.add(trade);

            buyOrder.fill(fillQty, trade);
            sellOrder.fill(fillQty, trade);
            bestAsk.updateQuantityAfterFill(fillQty);

            if (sellOrder.isFilled()) {
                bestAsk.removeFirst();
            }
            if (bestAsk.isEmpty()) {
                book.removeBestAsk();
            }
        }
    }

    /**
     * Match a SELL LIMIT order against bids.
     * Mirror of matchAgainstAsks: sell only at or above the limit price.
     */
    private void matchAgainstBids(Order sellOrder, OrderBook book, List<Trade> trades) {
        double priceLimit = sellOrder.getPrice(); // The seller's minimum acceptable price

        while (sellOrder.getRemainingQuantity() > 0 && book.hasBids()) {
            PriceLevel bestBid = book.getBestBid();
            if (bestBid == null || bestBid.isEmpty()) break;

            // PRICE CHECK: only match if bid price is at or above seller's limit
            if (bestBid.getPrice() < priceLimit) {
                break; // No more matchable bids — remaining rests in book
            }

            Order buyOrder = bestBid.peekFirst();
            if (buyOrder == null) break;

            int fillQty = Math.min(sellOrder.getRemainingQuantity(), buyOrder.getRemainingQuantity());
            double fillPrice = buyOrder.getPrice(); // Execute at the passive (resting) order's price

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
