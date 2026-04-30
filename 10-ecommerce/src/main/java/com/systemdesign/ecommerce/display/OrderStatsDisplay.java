package com.systemdesign.ecommerce.display;

import com.systemdesign.ecommerce.model.Inventory;
import com.systemdesign.ecommerce.model.Order;
import com.systemdesign.ecommerce.model.OrderStatus;
import com.systemdesign.ecommerce.model.Payment;
import com.systemdesign.ecommerce.model.PaymentStatus;
import com.systemdesign.ecommerce.service.InventoryService;
import com.systemdesign.ecommerce.service.OrderService;
import com.systemdesign.ecommerce.service.PaymentService;

import java.util.List;

/**
 * OrderStatsDisplay — Prints aggregated e-commerce metrics.
 *
 * Interview notes:
 * - In production, these metrics would be computed by a data pipeline
 *   (Spark/Flink) and served from a data warehouse (Redshift, BigQuery).
 * - Here we compute on-the-fly from in-memory data for demo purposes.
 */
public class OrderStatsDisplay {

    private final OrderService orderService;
    private final InventoryService inventoryService;
    private final PaymentService paymentService;

    public OrderStatsDisplay(OrderService orderService,
                             InventoryService inventoryService,
                             PaymentService paymentService) {
        this.orderService = orderService;
        this.inventoryService = inventoryService;
        this.paymentService = paymentService;
    }

    /**
     * Prints a summary of all key metrics.
     */
    public void printStats() {
        List<Order> allOrders = orderService.getAllOrders();

        System.out.println("\n--- E-Commerce Statistics ---");

        // Total orders
        System.out.println("  Total orders: " + allOrders.size());

        // Orders by status
        for (OrderStatus status : OrderStatus.values()) {
            long count = allOrders.stream()
                    .filter(o -> o.getStatus() == status)
                    .count();
            if (count > 0) {
                System.out.printf("    %s: %d%n", status, count);
            }
        }

        // Revenue (from SHIPPED and DELIVERED orders)
        double revenue = allOrders.stream()
                .filter(o -> o.getStatus() == OrderStatus.SHIPPED
                        || o.getStatus() == OrderStatus.DELIVERED
                        || o.getStatus() == OrderStatus.PAYMENT_CONFIRMED)
                .mapToDouble(Order::getTotalAmount)
                .sum();
        System.out.printf("  Total revenue: $%.2f%n", revenue);

        // Average order value
        if (!allOrders.isEmpty()) {
            double avg = allOrders.stream()
                    .mapToDouble(Order::getTotalAmount)
                    .average()
                    .orElse(0.0);
            System.out.printf("  Avg order value: $%.2f%n", avg);
        }

        // Inventory levels
        System.out.println("\n  Inventory levels:");
        List<Inventory> allInventory = inventoryService.getAllInventory();
        for (Inventory inv : allInventory) {
            System.out.printf("    %s: total=%d, reserved=%d, available=%d%n",
                    inv.getProductName(), inv.getTotalStock(),
                    inv.getReservedStock(), inv.getAvailableStock());
        }

        // Payment success rate
        long totalPayments = 0;
        long successfulPayments = 0;
        for (Order order : allOrders) {
            var payment = paymentService.getPaymentByOrderId(order.getOrderId());
            if (payment.isPresent()) {
                totalPayments++;
                if (payment.get().getStatus() == PaymentStatus.COMPLETED ||
                    payment.get().getStatus() == PaymentStatus.PENDING) {
                    successfulPayments++;
                }
            }
        }
        if (totalPayments > 0) {
            double successRate = (double) successfulPayments / totalPayments * 100;
            System.out.printf("%n  Payment success rate: %.1f%% (%d/%d)%n",
                    successRate, successfulPayments, totalPayments);
        }

        System.out.println("----------------------------\n");
    }
}
