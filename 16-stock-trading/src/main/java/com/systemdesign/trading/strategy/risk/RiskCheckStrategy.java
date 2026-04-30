package com.systemdesign.trading.strategy.risk;

import com.systemdesign.trading.model.Account;
import com.systemdesign.trading.model.MarketData;
import com.systemdesign.trading.model.Order;
import com.systemdesign.trading.model.RiskResult;

/**
 * RiskCheckStrategy is the interface for pre-trade risk validation.
 *
 * WHY Strategy pattern for risk checks:
 * - Different risk rules can be composed dynamically. A RETAIL account might have:
 *   [MarginCheck, PositionLimit, CircuitBreaker]
 * - An INSTITUTIONAL account might skip PositionLimit.
 * - New rules (e.g., fat-finger check, market-wide halt) can be added without modifying existing ones.
 *
 * WHY risk checks run BEFORE matching:
 * - An order that fails margin check should never enter the order book.
 * - Matching is irreversible (trades are binding). Risk is the last gate.
 * - In production, risk checks may be synchronous (blocking) or async (with timeout).
 *
 * CALL CHAIN:
 * TradingService.placeOrder() → RiskService.runChecks(order, account, marketData) →
 * iterates List<RiskCheckStrategy> → each returns RiskResult →
 * first REJECT stops the chain → order rejected or proceeds to matching
 */
public interface RiskCheckStrategy {

    /**
     * Perform a risk check on the given order.
     *
     * @param order      The order to validate
     * @param account    The user's account (for margin checks)
     * @param marketData Current market data for the symbol (for circuit breaker checks)
     * @return RiskResult — pass() or reject(reason)
     */
    RiskResult check(Order order, Account account, MarketData marketData);
}
