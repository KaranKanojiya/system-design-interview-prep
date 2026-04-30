package com.systemdesign.ecommerce.service;

import com.systemdesign.ecommerce.exception.OrderNotFoundException;
import com.systemdesign.ecommerce.model.Order;
import com.systemdesign.ecommerce.repository.OrderRepository;
import com.systemdesign.ecommerce.saga.OrderSagaOrchestrator;
import com.systemdesign.ecommerce.saga.SagaResult;
import com.systemdesign.ecommerce.strategy.payment.PaymentStrategy;
import com.systemdesign.ecommerce.strategy.shipping.ShippingStrategy;

import java.util.List;

/**
 * OrderService — FACADE for the order placement flow.
 *
 * Interview notes:
 * - This is the main entry point for checkout. It orchestrates:
 *     1) Get user's cart (via CartService)
 *     2) Create an Order from cart items
 *     3) Execute the saga (inventory → payment → shipping)
 *     4) Clear the cart on success
 *     5) Return the completed order
 *
 * - The Facade pattern hides the complexity of cart-to-order conversion,
 *   saga execution, and cart cleanup behind a single placeOrder() call.
 *   The controller doesn't need to know about sagas at all.
 *
 * Call chain: Controller → OrderService.placeOrder()
 *                            → CartService.checkout() (create Order)
 *                            → SagaOrchestrator.execute() (inventory → payment → ship)
 *                            → CartService.clearCart() (on success)
 *                            → OrderRepository.save()
 *
 * Dependency graph:
 *   OrderService
 *     ├── CartService (cart → order conversion)
 *     ├── OrderSagaOrchestrator (checkout saga)
 *     │     ├── InventoryService
 *     │     ├── PaymentService
 *     │     ├── ShippingService
 *     │     └── NotificationService
 *     └── OrderRepository (persistence)
 */
public class OrderService {

    private final CartService cartService;
    private final OrderSagaOrchestrator sagaOrchestrator;
    private final OrderRepository orderRepository;
    private final ShippingService shippingService;

    public OrderService(CartService cartService,
                        OrderSagaOrchestrator sagaOrchestrator,
                        OrderRepository orderRepository,
                        ShippingService shippingService) {
        this.cartService = cartService;
        this.sagaOrchestrator = sagaOrchestrator;
        this.orderRepository = orderRepository;
        this.shippingService = shippingService;
    }

    /**
     * Places an order for the user. This is the MAIN CHECKOUT FLOW.
     *
     * Steps:
     * 1. Convert cart → Order (snapshot items, prices)
     * 2. Configure saga with the chosen payment/shipping strategies
     * 3. Execute the saga (reserve inventory → charge payment → create shipment)
     * 4. On success: clear cart, persist order
     * 5. Return the order (check order.getStatus() and sagaResult for outcome)
     *
     * @param userId           the user checking out
     * @param paymentStrategy  how to pay (credit card, wallet, COD)
     * @param shippingAddress  where to ship
     * @param shippingStrategy how to ship (standard, express)
     * @return the Order (status reflects saga outcome)
     */
    public Order placeOrder(String userId, PaymentStrategy paymentStrategy,
                            String shippingAddress, ShippingStrategy shippingStrategy) {

        // Step 1: Create order from cart
        Order order = cartService.checkout(userId,
                paymentStrategy.getClass().getSimpleName(), shippingAddress);

        System.out.println("  Order created: " + order.getOrderId() +
                " (total: $" + String.format("%.2f", order.getTotalAmount()) + ")");

        // Step 2: Configure saga strategies
        sagaOrchestrator.setPaymentStrategy(paymentStrategy);
        shippingService.setShippingStrategy(shippingStrategy);

        // Step 3: Execute the saga
        SagaResult result = sagaOrchestrator.execute(order);

        // Step 4: Persist order regardless of outcome (for audit trail)
        orderRepository.save(order);

        // Step 5: Clear cart only on success
        if (result.isSuccess()) {
            cartService.clearCart(userId);
            System.out.println("  Cart cleared for user " + userId);
        } else {
            System.out.println("  Cart NOT cleared (saga failed): " + result.getMessage());
        }

        System.out.println("  Saga result: " + result);
        return order;
    }

    /**
     * Cancels an order. In a full implementation, this would trigger
     * compensating actions (refund, release inventory).
     */
    public Order cancelOrder(String orderId) {
        Order order = getOrder(orderId);
        order.cancel();
        orderRepository.save(order);
        return order;
    }

    /**
     * Retrieves an order by ID.
     */
    public Order getOrder(String orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    /**
     * Retrieves all orders for a user.
     */
    public List<Order> getOrdersByUser(String userId) {
        return orderRepository.findByUserId(userId);
    }

    /**
     * Returns all orders (for stats/admin).
     */
    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }
}
