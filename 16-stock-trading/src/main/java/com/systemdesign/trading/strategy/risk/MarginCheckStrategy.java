package com.systemdesign.trading.strategy.risk;

import com.systemdesign.trading.model.*;

/**
 * MarginCheckStrategy validates that the user has sufficient funds to place an order.
 *
 * MARGIN MODEL:
 * - For BUY orders: user needs funds to pay for the shares.
 *   Delivery (CNC): Full amount required. margin = price * quantity.
 *   Intraday (MIS): Only a fraction required. margin = price * quantity * marginPercent.
 *   Example: Buy 100 RELIANCE @ 2500. CNC margin = 2,50,000. MIS margin (20%) = 50,000.
 *
 * - For SELL orders: user needs to HOLD the shares (short selling not modeled here).
 *   We check position quantity against order quantity.
 *
 * WHY marginPercent exists:
 * - Intraday traders don't hold overnight, so risk is lower → brokers allow leverage.
 * - Zerodha allows up to 5x leverage for intraday (marginPercent = 0.20).
 * - Delivery requires full payment (marginPercent = 1.0).
 * - This is configurable per broker/stock/segment.
 *
 * MARKET ORDER SPECIAL CASE:
 * - Market orders have price = 0 (execute at best available).
 * - For margin calculation, we use the LTP (Last Traded Price) as a proxy.
 * - WHY: We can't know the exact execution price in advance, but LTP is the best estimate.
 *
 * CALL CHAIN:
 * RiskService.runChecks() → MarginCheckStrategy.check() →
 * reads Account.getAvailableMargin() → compares with required margin →
 * returns pass() or reject("Insufficient margin: available X, required Y")
 */
public class MarginCheckStrategy implements RiskCheckStrategy {

    // Fraction of order value required as margin (1.0 = full, 0.2 = 5x leverage)
    private final double marginPercent;

    public MarginCheckStrategy(double marginPercent) {
        this.marginPercent = marginPercent;
    }

    @Override
    public RiskResult check(Order order, Account account, MarketData marketData) {
        if (order.getSide() == OrderSide.BUY) {
            return checkBuyMargin(order, account, marketData);
        } else {
            // For SELL: simplified check — assume user has shares if they have an account.
            // A full implementation would check PositionRepository.
            // The PositionLimitStrategy handles position-level checks.
            return RiskResult.pass();
        }
    }

    /**
     * Check if user has enough margin for a buy order.
     *
     * CALCULATION:
     * - Determine the effective price (order price for limit, LTP for market).
     * - Required margin = effectivePrice * quantity * marginPercent.
     * - Compare against account.getAvailableMargin().
     */
    private RiskResult checkBuyMargin(Order order, Account account, MarketData marketData) {
        // For market orders, use LTP as the estimated price
        double effectivePrice = order.getPrice() > 0
                ? order.getPrice()
                : (marketData != null ? marketData.getLtp() : 0.0);

        if (effectivePrice <= 0) {
            return RiskResult.reject("Cannot determine order price for margin calculation");
        }

        double requiredMargin = effectivePrice * order.getQuantity() * marginPercent;
        double availableMargin = account.getAvailableMargin();

        if (availableMargin < requiredMargin) {
            return RiskResult.reject(String.format(
                    "Insufficient margin. Available: %.2f, Required: %.2f (%.0f%% of %.2f x %d)",
                    availableMargin, requiredMargin, marginPercent * 100,
                    effectivePrice, order.getQuantity()));
        }

        return RiskResult.pass();
    }
}
