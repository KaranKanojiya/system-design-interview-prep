package com.systemdesign.trading.strategy.risk;

import com.systemdesign.trading.model.*;
import com.systemdesign.trading.repository.StockRepository;

/**
 * CircuitBreakerStrategy enforces exchange-imposed price bands and trading halts.
 *
 * WHAT ARE CIRCUIT BREAKERS (Indian context):
 * - NSE/BSE set daily price bands for each stock (e.g., +/- 20% from previous close).
 * - RELIANCE closes at 2500 → next day circuit = [2000, 3000] (20% band).
 * - Any order with price outside [2000, 3000] is automatically REJECTED.
 * - If the index (NIFTY/SENSEX) moves 10%/15%/20%, market-wide halt is triggered.
 *
 * WHY circuit breakers exist:
 * - Prevent panic-driven crashes (e.g., Flash Crash of 2010).
 * - Give market participants time to digest information during extreme events.
 * - Protect retail investors from erroneous "fat finger" orders.
 *
 * TWO CHECKS:
 * 1. Is trading halted for this stock? (market-wide or stock-specific halt)
 * 2. Is the order price within circuit limits? (only for limit/stop orders — market orders
 *    execute at whatever price is available, so the book itself won't have out-of-band prices)
 *
 * CALL CHAIN:
 * RiskService.runChecks() → CircuitBreakerStrategy.check() →
 * StockRepository.getStock(symbol) → reads circuit limits → compares with order price →
 * reject if outside bands or if trading halted
 */
public class CircuitBreakerStrategy implements RiskCheckStrategy {

    private final StockRepository stockRepository;

    public CircuitBreakerStrategy(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    @Override
    public RiskResult check(Order order, Account account, MarketData marketData) {
        Stock stock = stockRepository.findBySymbol(order.getSymbol());
        if (stock == null) {
            return RiskResult.reject("Unknown symbol: " + order.getSymbol());
        }

        // Check 1: Is trading halted?
        if (stock.isTradingHalted()) {
            return RiskResult.reject(String.format(
                    "Trading halted for %s. No new orders accepted.", order.getSymbol()));
        }

        // Check 2: Is order price within circuit limits?
        // Only applicable for orders with a price (LIMIT, STOP_LIMIT)
        // Market orders don't specify a price — they execute at whatever the book offers.
        if (order.getPrice() > 0) {
            double price = order.getPrice();
            double upper = stock.getUpperCircuitLimit();
            double lower = stock.getLowerCircuitLimit();

            if (price > upper) {
                return RiskResult.reject(String.format(
                        "Circuit breaker: order price %.2f exceeds upper limit %.2f for %s",
                        price, upper, order.getSymbol()));
            }
            if (price < lower) {
                return RiskResult.reject(String.format(
                        "Circuit breaker: order price %.2f below lower limit %.2f for %s",
                        price, lower, order.getSymbol()));
            }
        }

        return RiskResult.pass();
    }
}
