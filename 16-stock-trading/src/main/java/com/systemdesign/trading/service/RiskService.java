package com.systemdesign.trading.service;

import com.systemdesign.trading.model.Account;
import com.systemdesign.trading.model.MarketData;
import com.systemdesign.trading.model.Order;
import com.systemdesign.trading.model.RiskResult;
import com.systemdesign.trading.strategy.risk.RiskCheckStrategy;

import java.util.List;

/**
 * RiskService chains multiple risk checks and fails fast on the first rejection.
 *
 * WHY chain of checks (not one big check):
 * - Each risk check is independent and testable.
 * - New checks can be added/removed without modifying existing ones.
 * - Order of checks matters: run cheap checks first, expensive checks last.
 *   1. Circuit breaker: just a price comparison (O(1), cheapest)
 *   2. Margin check: reads account balance (may require DB query in production)
 *   3. Position limit: reads current position (may require aggregation)
 *
 * WHY fail-fast:
 * - If margin check fails, there's no point running position limit check.
 * - Saves computation and provides immediate feedback to the user.
 * - In production, some checks might involve external calls (clearing house),
 *   so failing fast avoids unnecessary network round-trips.
 *
 * CALL CHAIN:
 * TradingService.placeOrder() → RiskService.runChecks(order, account, marketData) →
 * iterates List<RiskCheckStrategy> → first reject returns RiskResult.reject(reason) →
 * TradingService decides: reject order or proceed to matching
 */
public class RiskService {

    private final List<RiskCheckStrategy> riskChecks;

    public RiskService(List<RiskCheckStrategy> riskChecks) {
        this.riskChecks = riskChecks;
    }

    /**
     * Run all risk checks in sequence. Fail fast on first rejection.
     *
     * @param order      The order to validate
     * @param account    The user's trading account
     * @param marketData Current market data for the symbol
     * @return RiskResult — pass() if all checks pass, reject(reason) on first failure
     */
    public RiskResult runChecks(Order order, Account account, MarketData marketData) {
        for (RiskCheckStrategy check : riskChecks) {
            RiskResult result = check.check(order, account, marketData);
            if (!result.isPassed()) {
                // Fail fast: return the rejection immediately
                return result;
            }
        }
        // All checks passed
        return RiskResult.pass();
    }

    /**
     * Get the list of registered risk checks (for introspection/debugging).
     */
    public List<RiskCheckStrategy> getRiskChecks() {
        return riskChecks;
    }
}
