package com.systemdesign.trading.service;

import com.systemdesign.trading.model.Order;
import com.systemdesign.trading.model.Trade;

/**
 * NotificationService sends trade/order notifications to users.
 *
 * WHY console print (not real notifications):
 * - In production: WebSocket push for real-time updates, SMS for critical alerts,
 *   email for end-of-day summaries, push notifications for mobile.
 * - For this demo, System.out.println simulates all notification channels.
 *
 * CALL CHAIN:
 * TradingService.placeOrder() → trade fills → NotificationService.notifyOrderFilled() →
 * prints to console. Also called for partial fills, rejections, and margin calls.
 */
public class NotificationService {

    public void notifyOrderFilled(String userId, Order order) {
        System.out.printf("  [NOTIFY] %s: Order %s FILLED — %s %s %d shares%n",
                userId, order.getOrderId(), order.getSide(), order.getSymbol(), order.getQuantity());
    }

    public void notifyOrderPartialFill(String userId, Order order, Trade trade) {
        System.out.printf("  [NOTIFY] %s: Order %s PARTIAL FILL — %d/%d shares @ %.2f%n",
                userId, order.getOrderId(), order.getFilledQuantity(),
                order.getQuantity(), trade.getPrice());
    }

    public void notifyOrderRejected(String userId, Order order, String reason) {
        System.out.printf("  [NOTIFY] %s: Order %s REJECTED — %s%n",
                userId, order.getOrderId(), reason);
    }

    public void notifyOrderCancelled(String userId, Order order) {
        System.out.printf("  [NOTIFY] %s: Order %s CANCELLED%n", userId, order.getOrderId());
    }

    public void notifyMarginCall(String userId) {
        System.out.printf("  [NOTIFY] %s: MARGIN CALL — Please add funds or close positions%n", userId);
    }

    public void notifyTradeSettled(Trade trade) {
        System.out.printf("  [NOTIFY] Trade %s settled: %s bought %d %s @ %.2f from %s%n",
                trade.getTradeId(), trade.getBuyerUserId(), trade.getQuantity(),
                trade.getSymbol(), trade.getPrice(), trade.getSellerUserId());
    }
}
