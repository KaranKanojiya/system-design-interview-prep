package com.systemdesign.ecommerce.model;

import com.systemdesign.ecommerce.exception.InvalidOrderStateException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Order — Aggregate root with a guarded state machine.
 *
 * Interview notes:
 * - Builder pattern for construction (many optional fields).
 * - State machine transitions are guarded: each method checks that the
 *   current status is in the set of allowed "from" states. If not, it
 *   throws InvalidOrderStateException. This prevents illegal jumps like
 *   shipping an unpaid order.
 * - sagaSteps is an audit log populated by the SagaOrchestrator so the
 *   order itself carries a full trace of what happened during checkout.
 *
 * State diagram:
 *   CREATED → INVENTORY_RESERVED → PAYMENT_PENDING → PAYMENT_CONFIRMED → SHIPPED → DELIVERED
 *   Any pre-DELIVERED state → CANCELLED → REFUNDED
 */
public class Order {

    private final String orderId;
    private final String userId;
    private final List<OrderItem> items;
    private OrderStatus status;
    private final double totalAmount;
    private final String shippingAddress;
    private final String paymentMethod;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Saga audit trail — populated by OrderSagaOrchestrator
    private final List<SagaStepRecord> sagaSteps;

    // Populated when shipped
    private String trackingId;

    private Order(Builder builder) {
        this.orderId = builder.orderId;
        this.userId = builder.userId;
        this.items = builder.items;
        this.status = builder.status;
        this.totalAmount = builder.totalAmount;
        this.shippingAddress = builder.shippingAddress;
        this.paymentMethod = builder.paymentMethod;
        this.createdAt = builder.createdAt;
        this.updatedAt = builder.updatedAt;
        this.sagaSteps = builder.sagaSteps;
    }

    // ── Guarded state machine transitions ────────────────────────────────

    /**
     * CREATED → INVENTORY_RESERVED
     * Called after the saga's inventory step succeeds.
     */
    public void confirmInventory() {
        guardTransition("confirmInventory", status,
                Set.of(OrderStatus.CREATED));
        transitionTo(OrderStatus.INVENTORY_RESERVED);
    }

    /**
     * INVENTORY_RESERVED → PAYMENT_PENDING → PAYMENT_CONFIRMED
     * Two-phase: first we mark payment pending, then on success we confirm.
     * For simplicity the saga calls confirmPayment() which jumps through both.
     */
    public void confirmPayment() {
        guardTransition("confirmPayment", status,
                Set.of(OrderStatus.INVENTORY_RESERVED, OrderStatus.PAYMENT_PENDING));
        transitionTo(OrderStatus.PAYMENT_CONFIRMED);
    }

    /**
     * PAYMENT_CONFIRMED → SHIPPED
     * Requires a tracking ID from the carrier.
     */
    public void ship(String trackingId) {
        guardTransition("ship", status,
                Set.of(OrderStatus.PAYMENT_CONFIRMED));
        this.trackingId = trackingId;
        transitionTo(OrderStatus.SHIPPED);
    }

    /**
     * SHIPPED → DELIVERED
     */
    public void deliver() {
        guardTransition("deliver", status,
                Set.of(OrderStatus.SHIPPED));
        transitionTo(OrderStatus.DELIVERED);
    }

    /**
     * Any pre-DELIVERED state → CANCELLED
     */
    public void cancel() {
        Set<OrderStatus> cancellable = Set.of(
                OrderStatus.CREATED,
                OrderStatus.INVENTORY_RESERVED,
                OrderStatus.PAYMENT_PENDING,
                OrderStatus.PAYMENT_CONFIRMED,
                OrderStatus.SHIPPED
        );
        guardTransition("cancel", status, cancellable);
        transitionTo(OrderStatus.CANCELLED);
    }

    /**
     * CANCELLED → REFUNDED (only if payment was already taken)
     */
    public void refund() {
        guardTransition("refund", status,
                Set.of(OrderStatus.CANCELLED));
        transitionTo(OrderStatus.REFUNDED);
    }

    // ── Guard helper ─────────────────────────────────────────────────────

    private void guardTransition(String action, OrderStatus current,
                                 Set<OrderStatus> allowed) {
        if (!allowed.contains(current)) {
            throw new InvalidOrderStateException(
                    String.format("Cannot %s: order %s is in %s (allowed: %s)",
                            action, orderId, current, allowed));
        }
    }

    private void transitionTo(OrderStatus newStatus) {
        this.status = newStatus;
        this.updatedAt = LocalDateTime.now();
    }

    // ── Saga step tracking ───────────────────────────────────────────────

    public void addSagaStep(SagaStepRecord step) {
        sagaSteps.add(step);
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public String getOrderId()                  { return orderId; }
    public String getUserId()                   { return userId; }
    public List<OrderItem> getItems()           { return items; }
    public OrderStatus getStatus()              { return status; }
    public double getTotalAmount()              { return totalAmount; }
    public String getShippingAddress()          { return shippingAddress; }
    public String getPaymentMethod()            { return paymentMethod; }
    public LocalDateTime getCreatedAt()         { return createdAt; }
    public LocalDateTime getUpdatedAt()         { return updatedAt; }
    public List<SagaStepRecord> getSagaSteps()  { return sagaSteps; }
    public String getTrackingId()               { return trackingId; }

    @Override
    public String toString() {
        return String.format("Order{id='%s', user='%s', status=%s, total=$%.2f, items=%d}",
                orderId, userId, status, totalAmount, items.size());
    }

    // ── Builder ──────────────────────────────────────────────────────────

    public static class Builder {
        private String orderId;
        private String userId;
        private List<OrderItem> items = new ArrayList<>();
        private OrderStatus status = OrderStatus.CREATED;
        private double totalAmount;
        private String shippingAddress;
        private String paymentMethod;
        private LocalDateTime createdAt = LocalDateTime.now();
        private LocalDateTime updatedAt = LocalDateTime.now();
        private List<SagaStepRecord> sagaSteps = new ArrayList<>();

        public Builder orderId(String id)              { this.orderId = id; return this; }
        public Builder userId(String id)               { this.userId = id; return this; }
        public Builder items(List<OrderItem> items)    { this.items = items; return this; }
        public Builder status(OrderStatus s)           { this.status = s; return this; }
        public Builder totalAmount(double amt)         { this.totalAmount = amt; return this; }
        public Builder shippingAddress(String addr)    { this.shippingAddress = addr; return this; }
        public Builder paymentMethod(String m)         { this.paymentMethod = m; return this; }
        public Builder createdAt(LocalDateTime t)      { this.createdAt = t; return this; }
        public Builder updatedAt(LocalDateTime t)      { this.updatedAt = t; return this; }
        public Builder sagaSteps(List<SagaStepRecord> s) { this.sagaSteps = s; return this; }

        public Order build() {
            return new Order(this);
        }
    }
}
