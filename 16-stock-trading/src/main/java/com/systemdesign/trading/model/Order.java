package com.systemdesign.trading.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Order represents a user's intent to buy or sell a stock.
 *
 * WHY Builder pattern:
 * - Orders have many fields, some optional (triggerPrice only for stop-loss).
 * - Builder prevents telescoping constructors and makes construction readable.
 * - Enforces required fields at compile time via builder chain.
 *
 * KEY DESIGN DECISIONS:
 * - filledQuantity tracks partial fills. remainingQuantity is COMPUTED (not stored)
 *   to avoid stale state: remaining = quantity - filledQuantity.
 * - trades list tracks all executions for this order (one order can have multiple trades).
 * - State guards: fill() only works on OPEN/PARTIALLY_FILLED orders. cancel() only on
 *   OPEN/PARTIALLY_FILLED. This prevents double-filling or cancelling already-filled orders.
 *
 * CALL CHAIN:
 * TradingController creates Order via Builder → RiskService validates → MatchingEngine fills →
 * Order.fill() updates state → PortfolioService reads order.getTrades()
 */
public class Order {

    private final String orderId;
    private final String userId;
    private final String symbol;
    private final OrderSide side;
    private final OrderType type;
    private final double price;          // 0 for market orders (price is "best available")
    private final int quantity;
    private int filledQuantity;
    private OrderStatus status;
    private final double triggerPrice;   // For STOP_LOSS / STOP_LIMIT orders
    private final Instant createdAt;
    private Instant updatedAt;
    private final List<Trade> trades;    // All trades that filled (part of) this order

    private Order(Builder builder) {
        this.orderId = builder.orderId;
        this.userId = builder.userId;
        this.symbol = builder.symbol;
        this.side = builder.side;
        this.type = builder.type;
        this.price = builder.price;
        this.quantity = builder.quantity;
        this.filledQuantity = 0;
        this.status = OrderStatus.PENDING_RISK;
        this.triggerPrice = builder.triggerPrice;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.trades = new ArrayList<>();
    }

    // =====================================================================
    // CORE METHODS — State transitions with guards
    // =====================================================================

    /**
     * Fill this order with the given quantity at the given price.
     *
     * WHY state guards:
     * - Prevents filling an already-FILLED or CANCELLED order.
     * - In a real system, this would also be idempotent (trade IDs prevent double-counting).
     *
     * @param qty   Number of shares filled in this execution
     * @param trade The trade that caused this fill
     * @throws IllegalStateException if order is not in a fillable state
     */
    public void fill(int qty, Trade trade) {
        // Guard: only OPEN or PARTIALLY_FILLED orders can be filled
        if (status != OrderStatus.OPEN && status != OrderStatus.PARTIALLY_FILLED) {
            throw new IllegalStateException(
                    "Cannot fill order in state " + status + ". Order: " + orderId);
        }
        if (qty <= 0 || qty > getRemainingQuantity()) {
            throw new IllegalArgumentException(
                    "Invalid fill qty " + qty + " for remaining " + getRemainingQuantity());
        }

        this.filledQuantity += qty;
        this.trades.add(trade);
        this.updatedAt = Instant.now();

        // Transition state based on whether fully filled
        if (filledQuantity >= quantity) {
            this.status = OrderStatus.FILLED;
        } else {
            this.status = OrderStatus.PARTIALLY_FILLED;
        }
    }

    /**
     * Cancel this order (or its remaining unfilled portion).
     *
     * WHY cancellation is allowed for PARTIALLY_FILLED:
     * - User might want to cancel remaining 70 shares after 30 of 100 were filled.
     * - The 30 filled shares are NOT reversed (that's a different operation).
     */
    public void cancel() {
        if (status != OrderStatus.OPEN && status != OrderStatus.PARTIALLY_FILLED) {
            throw new IllegalStateException(
                    "Cannot cancel order in state " + status + ". Order: " + orderId);
        }
        this.status = OrderStatus.CANCELLED;
        this.updatedAt = Instant.now();
    }

    /**
     * Reject this order (risk check failed).
     */
    public void reject() {
        if (status != OrderStatus.PENDING_RISK) {
            throw new IllegalStateException(
                    "Cannot reject order in state " + status + ". Order: " + orderId);
        }
        this.status = OrderStatus.REJECTED;
        this.updatedAt = Instant.now();
    }

    /**
     * Mark order as OPEN after passing risk checks.
     */
    public void markOpen() {
        if (status != OrderStatus.PENDING_RISK) {
            throw new IllegalStateException(
                    "Cannot open order in state " + status + ". Order: " + orderId);
        }
        this.status = OrderStatus.OPEN;
        this.updatedAt = Instant.now();
    }

    // =====================================================================
    // COMPUTED PROPERTIES
    // =====================================================================

    /**
     * Remaining quantity is ALWAYS computed, never stored.
     * WHY: Prevents stale state. If filledQuantity is updated, remaining is automatically correct.
     */
    public int getRemainingQuantity() {
        return quantity - filledQuantity;
    }

    public boolean isFilled() {
        return status == OrderStatus.FILLED;
    }

    /**
     * An order is "active" if it can still receive fills or be cancelled.
     * OPEN and PARTIALLY_FILLED orders sit in the order book waiting for matches.
     */
    public boolean isActive() {
        return status == OrderStatus.OPEN || status == OrderStatus.PARTIALLY_FILLED;
    }

    // =====================================================================
    // GETTERS
    // =====================================================================

    public String getOrderId() { return orderId; }
    public String getUserId() { return userId; }
    public String getSymbol() { return symbol; }
    public OrderSide getSide() { return side; }
    public OrderType getType() { return type; }
    public double getPrice() { return price; }
    public int getQuantity() { return quantity; }
    public int getFilledQuantity() { return filledQuantity; }
    public OrderStatus getStatus() { return status; }
    public double getTriggerPrice() { return triggerPrice; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<Trade> getTrades() { return trades; }

    @Override
    public String toString() {
        return String.format("Order{id='%s', user='%s', %s %s %s %d @ %.2f, filled=%d/%d, status=%s}",
                orderId, userId, side, type, symbol, quantity, price, filledQuantity, quantity, status);
    }

    // =====================================================================
    // BUILDER
    // =====================================================================

    /**
     * Builder pattern for Order construction.
     *
     * Usage:
     *   Order order = new Order.Builder("user1", "RELIANCE", OrderSide.BUY, OrderType.LIMIT)
     *       .price(2500.00)
     *       .quantity(100)
     *       .build();
     */
    public static class Builder {
        private final String orderId;
        private final String userId;
        private final String symbol;
        private final OrderSide side;
        private final OrderType type;
        private double price = 0.0;
        private int quantity = 1;
        private double triggerPrice = 0.0;

        public Builder(String userId, String symbol, OrderSide side, OrderType type) {
            this.orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
            this.userId = userId;
            this.symbol = symbol;
            this.side = side;
            this.type = type;
        }

        public Builder price(double price) {
            this.price = price;
            return this;
        }

        public Builder quantity(int quantity) {
            this.quantity = quantity;
            return this;
        }

        public Builder triggerPrice(double triggerPrice) {
            this.triggerPrice = triggerPrice;
            return this;
        }

        public Order build() {
            // Validate: market orders should have price = 0
            if (type == OrderType.MARKET && price != 0.0) {
                // Allow it but note: market orders ignore the price field
                price = 0.0;
            }
            // Validate: limit orders must have a price
            if (type == OrderType.LIMIT && price <= 0.0) {
                throw new IllegalArgumentException("Limit order must have a positive price");
            }
            // Validate: stop-loss orders must have a trigger price
            if ((type == OrderType.STOP_LOSS || type == OrderType.STOP_LIMIT) && triggerPrice <= 0.0) {
                throw new IllegalArgumentException("Stop-loss order must have a trigger price");
            }
            if (quantity <= 0) {
                throw new IllegalArgumentException("Quantity must be positive");
            }
            return new Order(this);
        }
    }
}
