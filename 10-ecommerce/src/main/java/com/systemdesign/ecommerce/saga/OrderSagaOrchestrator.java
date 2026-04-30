package com.systemdesign.ecommerce.saga;

import com.systemdesign.ecommerce.exception.ECommerceException;
import com.systemdesign.ecommerce.exception.InsufficientStockException;
import com.systemdesign.ecommerce.exception.PaymentFailedException;
import com.systemdesign.ecommerce.model.Order;
import com.systemdesign.ecommerce.model.OrderItem;
import com.systemdesign.ecommerce.model.Payment;
import com.systemdesign.ecommerce.model.PaymentStatus;
import com.systemdesign.ecommerce.model.SagaStepRecord;
import com.systemdesign.ecommerce.model.Shipment;
import com.systemdesign.ecommerce.service.InventoryService;
import com.systemdesign.ecommerce.service.NotificationService;
import com.systemdesign.ecommerce.service.PaymentService;
import com.systemdesign.ecommerce.service.ShippingService;
import com.systemdesign.ecommerce.strategy.payment.PaymentStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * OrderSagaOrchestrator — THE CORE CLASS.
 *
 * Implements the Saga pattern for order checkout. This is an
 * ORCHESTRATION-BASED saga (as opposed to choreography-based):
 * a single orchestrator drives the steps sequentially and handles
 * compensation on failure.
 *
 * ──────────────────────────────────────────────────────────────────
 * SAGA STEPS & COMPENSATIONS:
 *
 *   Step 1: Reserve Inventory (for every item in the order)
 *           Compensation: Release all reserved inventory
 *
 *   Step 2: Process Payment (charge the customer)
 *           Compensation: Refund the payment
 *
 *   Step 3: Create Shipment (hand off to logistics)
 *           Compensation: (would cancel shipment — not shown for brevity)
 *
 *   On full success: Notify the customer.
 *
 * ──────────────────────────────────────────────────────────────────
 * WHY ORCHESTRATION OVER CHOREOGRAPHY?
 *
 * - Choreography: each service emits an event and the next service reacts.
 *   Pro: decoupled.  Con: hard to reason about the full flow; "saga spaghetti".
 *
 * - Orchestration: a central coordinator calls each service in order.
 *   Pro: flow is explicit, easy to add steps, compensation is clear.
 *   Con: the orchestrator is a single point of coordination (not failure —
 *   it can be replayed from a durable log).
 *
 * For an e-commerce checkout with a well-defined sequence, orchestration
 * is the standard choice (used by Amazon, Uber, etc.).
 * ──────────────────────────────────────────────────────────────────
 */
public class OrderSagaOrchestrator implements SagaOrchestrator {

    private final InventoryService inventoryService;
    private final PaymentService paymentService;
    private final ShippingService shippingService;
    private final NotificationService notificationService;

    // The payment strategy is passed per-order (customer chooses at checkout)
    private PaymentStrategy paymentStrategy;

    public OrderSagaOrchestrator(InventoryService inventoryService,
                                  PaymentService paymentService,
                                  ShippingService shippingService,
                                  NotificationService notificationService) {
        this.inventoryService = inventoryService;
        this.paymentService = paymentService;
        this.shippingService = shippingService;
        this.notificationService = notificationService;
    }

    /**
     * Sets the payment strategy for the next saga execution.
     * Called by OrderService before execute().
     */
    public void setPaymentStrategy(PaymentStrategy paymentStrategy) {
        this.paymentStrategy = paymentStrategy;
    }

    // ──────────────────────────────────────────────────────────────────
    // SAGA EXECUTION — the heart of the checkout flow
    // ──────────────────────────────────────────────────────────────────

    @Override
    public SagaResult execute(Order order) {
        SagaResult.Builder resultBuilder = new SagaResult.Builder();

        // Track which items were reserved so we can compensate on failure
        List<OrderItem> reservedItems = new ArrayList<>();
        Payment payment = null;

        // ════════════════════════════════════════════════════════════════
        // STEP 1: RESERVE INVENTORY
        // ════════════════════════════════════════════════════════════════
        // Why first? We must guarantee stock before charging the customer.
        // Charging first and then discovering "out of stock" is a terrible UX
        // and requires an immediate refund ($$$ in payment processor fees).

        SagaStepRecord inventoryStep = new SagaStepRecord("RESERVE_INVENTORY");
        order.addSagaStep(inventoryStep);

        try {
            System.out.println("  [SAGA] Step 1: Reserving inventory...");

            for (OrderItem item : order.getItems()) {
                // This call is synchronized inside Inventory.reserve() to
                // prevent overselling under concurrent checkouts.
                inventoryService.reserve(item.getProductId(), item.getQuantity());
                reservedItems.add(item);
                System.out.printf("    -> Reserved %d x '%s'%n",
                        item.getQuantity(), item.getProductName());
            }

            inventoryStep.markCompleted();
            order.confirmInventory();  // CREATED → INVENTORY_RESERVED
            resultBuilder.addCompletedStep("RESERVE_INVENTORY");
            System.out.println("  [SAGA] Step 1 COMPLETED: Inventory reserved.");

        } catch (InsufficientStockException e) {
            // Step 1 failed. No compensation needed for OTHER items because
            // we stop at the first failure. But we DO need to release any
            // items that were already reserved in this loop iteration.
            inventoryStep.markFailed();

            System.out.println("  [SAGA] Step 1 FAILED: " + e.getMessage());

            // Compensate: release any items reserved before the failure
            compensateInventory(reservedItems, order);
            resultBuilder.addCompensatedStep("RESERVE_INVENTORY (partial)");

            return resultBuilder
                    .success(false)
                    .failedStep("RESERVE_INVENTORY")
                    .message("Inventory reservation failed: " + e.getMessage())
                    .build();
        }

        // ════════════════════════════════════════════════════════════════
        // STEP 2: PROCESS PAYMENT
        // ════════════════════════════════════════════════════════════════
        // Why after inventory? See comment above — charge only after stock
        // is guaranteed.

        SagaStepRecord paymentStep = new SagaStepRecord("PROCESS_PAYMENT");
        order.addSagaStep(paymentStep);

        try {
            System.out.println("  [SAGA] Step 2: Processing payment...");

            payment = paymentService.processPayment(order, paymentStrategy);

            if (payment.getStatus() == PaymentStatus.FAILED) {
                // The strategy returned a FAILED payment (e.g., 5% random failure
                // for credit cards). Treat this as a step failure.
                throw new PaymentFailedException(
                        "Payment declined for order " + order.getOrderId());
            }

            paymentStep.markCompleted();
            order.confirmPayment();  // INVENTORY_RESERVED → PAYMENT_CONFIRMED
            resultBuilder.addCompletedStep("PROCESS_PAYMENT");
            System.out.println("  [SAGA] Step 2 COMPLETED: Payment processed. Txn=" +
                    payment.getTransactionId());

        } catch (PaymentFailedException e) {
            paymentStep.markFailed();

            System.out.println("  [SAGA] Step 2 FAILED: " + e.getMessage());

            // ── COMPENSATE STEP 1 ──
            // Payment failed, so we must release the inventory we reserved
            // in step 1. The customer was never charged, so no refund needed.
            compensateInventory(reservedItems, order);
            resultBuilder.addCompensatedStep("RESERVE_INVENTORY");

            notificationService.notifyPaymentFailed(order);

            return resultBuilder
                    .success(false)
                    .failedStep("PROCESS_PAYMENT")
                    .message("Payment failed: " + e.getMessage())
                    .build();
        }

        // ════════════════════════════════════════════════════════════════
        // STEP 3: CREATE SHIPMENT
        // ════════════════════════════════════════════════════════════════

        SagaStepRecord shippingStep = new SagaStepRecord("CREATE_SHIPMENT");
        order.addSagaStep(shippingStep);

        try {
            System.out.println("  [SAGA] Step 3: Creating shipment...");

            Shipment shipment = shippingService.createShipment(order);

            shippingStep.markCompleted();
            order.ship(shipment.getTrackingId());  // PAYMENT_CONFIRMED → SHIPPED
            resultBuilder.addCompletedStep("CREATE_SHIPMENT");
            System.out.println("  [SAGA] Step 3 COMPLETED: Shipment created. Tracking=" +
                    shipment.getTrackingId());

            // ── ALL STEPS SUCCEEDED ──
            // Confirm the inventory reservations (decrement totalStock permanently)
            for (OrderItem item : order.getItems()) {
                inventoryService.confirmReservation(item.getProductId(), item.getQuantity());
            }

            notificationService.notifyOrderConfirmed(order);
            notificationService.notifyOrderShipped(order, shipment);

            return resultBuilder
                    .success(true)
                    .message("Order " + order.getOrderId() + " completed successfully!")
                    .build();

        } catch (ECommerceException e) {
            shippingStep.markFailed();

            System.out.println("  [SAGA] Step 3 FAILED: " + e.getMessage());

            // ── COMPENSATE STEP 2 (refund) ──
            if (payment != null) {
                System.out.println("  [SAGA] Compensating: Refunding payment...");
                paymentService.refundPayment(payment.getPaymentId());
                resultBuilder.addCompensatedStep("PROCESS_PAYMENT");
            }

            // ── COMPENSATE STEP 1 (release inventory) ──
            compensateInventory(reservedItems, order);
            resultBuilder.addCompensatedStep("RESERVE_INVENTORY");

            return resultBuilder
                    .success(false)
                    .failedStep("CREATE_SHIPMENT")
                    .message("Shipment creation failed: " + e.getMessage())
                    .build();
        }
    }

    // ── Compensation helper ──────────────────────────────────────────

    /**
     * Releases inventory for all previously reserved items.
     * This is the compensation action for the inventory step.
     *
     * Why a separate method? The same compensation logic is needed when
     * step 2 OR step 3 fails, so we extract it to avoid duplication.
     */
    private void compensateInventory(List<OrderItem> reservedItems, Order order) {
        System.out.println("  [SAGA] Compensating: Releasing reserved inventory...");

        // Find the inventory saga step and mark it as compensating
        order.getSagaSteps().stream()
                .filter(s -> s.getStepName().equals("RESERVE_INVENTORY"))
                .findFirst()
                .ifPresent(s -> {
                    s.markCompensating();

                    for (OrderItem item : reservedItems) {
                        inventoryService.releaseReservation(
                                item.getProductId(), item.getQuantity());
                        System.out.printf("    -> Released %d x '%s'%n",
                                item.getQuantity(), item.getProductName());
                    }

                    s.markCompensated();
                });
    }
}
