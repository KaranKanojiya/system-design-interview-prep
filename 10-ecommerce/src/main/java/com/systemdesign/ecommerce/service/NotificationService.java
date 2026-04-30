package com.systemdesign.ecommerce.service;

import com.systemdesign.ecommerce.model.Order;
import com.systemdesign.ecommerce.model.Shipment;

/**
 * NotificationService — Sends notifications to customers.
 *
 * Interview notes:
 * - In production, this would publish events to SNS/SQS, which fan out to
 *   email (SES), push notification (Firebase), and SMS (Twilio) channels.
 * - Here we print to console to demonstrate the call points in the flow.
 * - Notifications are fire-and-forget — failure to notify should NOT fail
 *   the order. In the saga, notifications happen AFTER all critical steps
 *   succeed.
 */
public class NotificationService {

    public void notifyOrderConfirmed(Order order) {
        System.out.printf("    [Notification] Order %s confirmed for user %s. Total: $%.2f%n",
                order.getOrderId(), order.getUserId(), order.getTotalAmount());
    }

    public void notifyOrderShipped(Order order, Shipment shipment) {
        System.out.printf("    [Notification] Order %s shipped! Tracking: %s, Carrier: %s%n",
                order.getOrderId(), shipment.getTrackingId(), shipment.getCarrier());
    }

    public void notifyOrderCancelled(Order order) {
        System.out.printf("    [Notification] Order %s has been cancelled for user %s.%n",
                order.getOrderId(), order.getUserId());
    }

    public void notifyPaymentFailed(Order order) {
        System.out.printf("    [Notification] Payment failed for order %s. Please update your payment method.%n",
                order.getOrderId());
    }
}
