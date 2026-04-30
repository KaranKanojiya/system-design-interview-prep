package com.systemdesign.ecommerce;

import com.systemdesign.ecommerce.config.AppConfig;
import com.systemdesign.ecommerce.controller.ECommerceController;
import com.systemdesign.ecommerce.exception.InvalidOrderStateException;
import com.systemdesign.ecommerce.model.Cart;
import com.systemdesign.ecommerce.model.Inventory;
import com.systemdesign.ecommerce.model.Order;
import com.systemdesign.ecommerce.model.OrderItem;
import com.systemdesign.ecommerce.model.OrderStatus;
import com.systemdesign.ecommerce.model.Product;
import com.systemdesign.ecommerce.strategy.payment.PaymentStrategy;
import com.systemdesign.ecommerce.strategy.pricing.PricingStrategy;
import com.systemdesign.ecommerce.strategy.shipping.ShippingStrategy;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ECommerceApp — Main demo showcasing the E-Commerce System Design.
 *
 * Demonstrates:
 * - Saga pattern (orchestration-based) with proper compensation
 * - Strategy pattern (pricing, payment, shipping)
 * - Builder pattern (Order, Payment, Cart, SagaResult)
 * - Repository pattern (data access abstraction)
 * - Facade pattern (OrderService orchestrating the checkout flow)
 * - State machine (Order status transitions with guards)
 * - Thread safety (synchronized inventory, concurrent checkout)
 * - Decorator pattern (DiscountPricingStrategy wrapping StandardPricingStrategy)
 *
 * Each demo is self-contained with its own setup and teardown.
 */
public class ECommerceApp {

    private static final String SEPARATOR = "=".repeat(70);

    public static void main(String[] args) {
        System.out.println(SEPARATOR);
        System.out.println("   E-COMMERCE SYSTEM (Amazon) — System Design Interview Demo");
        System.out.println(SEPARATOR);
        System.out.println();

        demo1_ProductCatalog();
        demo2_ShoppingCart();
        demo3_SuccessfulCheckout();
        demo4_SagaRollback_PaymentFailure();
        demo5_SagaRollback_InsufficientStock();
        demo6_DiscountPricing();
        demo7_PaymentStrategyComparison();
        demo8_ShippingStrategyComparison();
        demo9_ConcurrentCheckout();
        demo10_OrderLifecycle();

        printDesignSummary();
    }

    // ══════════════════════════════════════════════════════════════════════
    // DEMO 1: Product Catalog
    // ══════════════════════════════════════════════════════════════════════

    private static void demo1_ProductCatalog() {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 1: Product Catalog (Browse, Search, Filter)");
        System.out.println(SEPARATOR);

        AppConfig config = new AppConfig();
        config.seedProducts();

        ECommerceController controller = config.getController();

        // Browse all products
        System.out.println("\n  All Products:");
        List<Product> allProducts = controller.handleGetProducts();
        for (Product p : allProducts) {
            System.out.println("    " + p);
        }

        // Search by name
        System.out.println("\n  Search for 'keyboard':");
        List<Product> keyboardResults = controller.handleSearchProducts("keyboard");
        for (Product p : keyboardResults) {
            System.out.println("    " + p);
        }

        // Filter by category
        System.out.println("\n  Category: 'Books':");
        List<Product> books = controller.handleGetProductsByCategory("Books");
        for (Product p : books) {
            System.out.println("    " + p);
        }

        System.out.println("\n  Category: 'Electronics':");
        List<Product> electronics = controller.handleGetProductsByCategory("Electronics");
        for (Product p : electronics) {
            System.out.println("    " + p);
        }

        System.out.println();
    }

    // ══════════════════════════════════════════════════════════════════════
    // DEMO 2: Shopping Cart
    // ══════════════════════════════════════════════════════════════════════

    private static void demo2_ShoppingCart() {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 2: Shopping Cart (Add, Remove, Update, View Total)");
        System.out.println(SEPARATOR);

        AppConfig config = new AppConfig();
        config.seedProducts();

        ECommerceController controller = config.getController();
        String userId = "user-alice";

        List<Product> products = controller.handleGetProducts();
        Product macbook = findProduct(products, "MacBook");
        Product headphones = findProduct(products, "Sony");
        Product usbHub = findProduct(products, "USB-C");

        // Add items
        System.out.println("\n  Adding items to cart...");
        controller.handleAddToCart(userId, macbook, 1);
        System.out.println("    Added: 1x " + macbook.getName());

        controller.handleAddToCart(userId, headphones, 2);
        System.out.println("    Added: 2x " + headphones.getName());

        controller.handleAddToCart(userId, usbHub, 3);
        System.out.println("    Added: 3x " + usbHub.getName());

        Cart cart = controller.handleGetCart(userId);
        System.out.println("\n  Cart: " + cart);
        System.out.println("    Items: " + cart.getItemCount() + ", Total: $" +
                String.format("%.2f", cart.getTotal()));

        // Update quantity
        System.out.println("\n  Updating headphones quantity to 1...");
        config.getCartService().updateQuantity(userId, headphones.getId(), 1);
        cart = controller.handleGetCart(userId);
        System.out.println("    Updated total: $" + String.format("%.2f", cart.getTotal()));

        // Remove item
        System.out.println("\n  Removing USB-C Hub...");
        controller.handleRemoveFromCart(userId, usbHub.getId());
        cart = controller.handleGetCart(userId);
        System.out.println("    After removal: " + cart.getItemCount() + " items, $" +
                String.format("%.2f", cart.getTotal()));

        // Add same product again (should merge)
        System.out.println("\n  Adding 1 more headphones (should merge with existing)...");
        controller.handleAddToCart(userId, headphones, 1);
        cart = controller.handleGetCart(userId);
        System.out.println("    After merge: " + cart);
        cart.getItems().forEach(item ->
                System.out.println("      " + item));

        System.out.println();
    }

    // ══════════════════════════════════════════════════════════════════════
    // DEMO 3: Successful Checkout
    // ══════════════════════════════════════════════════════════════════════

    private static void demo3_SuccessfulCheckout() {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 3: Successful Checkout (Full Saga Flow)");
        System.out.println(SEPARATOR);

        AppConfig config = new AppConfig();
        config.seedProducts();
        config.seedInventory();

        ECommerceController controller = config.getController();
        String userId = "user-bob";

        List<Product> products = controller.handleGetProducts();
        Product book1 = findProduct(products, "Pragmatic");
        Product book2 = findProduct(products, "Designing Data");

        // Build cart
        System.out.println("\n  Building cart for Bob...");
        controller.handleAddToCart(userId, book1, 1);
        controller.handleAddToCart(userId, book2, 1);

        Cart cart = controller.handleGetCart(userId);
        System.out.println("  Cart: " + cart);

        // Checkout with wallet (99% success rate) and standard shipping
        System.out.println("\n  Checking out with Wallet + Standard Shipping...");
        System.out.println("  ---");

        // Use wallet for near-guaranteed success in this demo
        Order order = controller.handleCheckout(userId,
                config.getWalletPayment(),
                "123 Main St, San Francisco, CA 94105",
                config.getStandardShipping());

        if (order != null) {
            System.out.println("\n  Final order: " + order);
            System.out.println("  Saga steps:");
            order.getSagaSteps().forEach(step ->
                    System.out.println("    " + step));
        }

        // Verify cart is cleared
        Cart postCart = controller.handleGetCart(userId);
        System.out.println("\n  Cart after checkout: " + postCart.getItemCount() + " items");

        System.out.println();
    }

    // ══════════════════════════════════════════════════════════════════════
    // DEMO 4: Saga Rollback — Payment Failure
    // ══════════════════════════════════════════════════════════════════════

    private static void demo4_SagaRollback_PaymentFailure() {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 4: Saga Rollback — Payment Failure");
        System.out.println("  (Inventory reserved, payment fails, saga compensates)");
        System.out.println(SEPARATOR);

        AppConfig config = new AppConfig();
        config.seedProducts();
        config.seedInventory();

        // Use a special "always fails" payment strategy to guarantee failure
        PaymentStrategy alwaysFailPayment = (amount, orderId) -> {
            return new com.systemdesign.ecommerce.model.Payment.Builder()
                    .paymentId("PAY-FAIL-" + java.util.UUID.randomUUID().toString().substring(0, 8))
                    .orderId(orderId)
                    .amount(amount)
                    .method(com.systemdesign.ecommerce.model.PaymentMethod.CREDIT_CARD)
                    .status(com.systemdesign.ecommerce.model.PaymentStatus.FAILED)
                    .processedAt(java.time.LocalDateTime.now())
                    .build();
        };

        ECommerceController controller = config.getController();
        String userId = "user-charlie";

        List<Product> products = controller.handleGetProducts();
        Product desk = findProduct(products, "Standing Desk");

        // Check inventory before
        Inventory deskInv = config.getInventoryService().getStock("P006");
        System.out.println("\n  Inventory BEFORE checkout: " + deskInv);

        // Build cart
        controller.handleAddToCart(userId, desk, 2);
        System.out.println("  Cart: 2x " + desk.getName());

        // Checkout with guaranteed-fail payment
        System.out.println("\n  Checking out (payment WILL fail)...");
        System.out.println("  ---");

        Order order = controller.handleCheckout(userId,
                alwaysFailPayment,
                "456 Oak Ave, Seattle, WA 98101",
                config.getStandardShipping());

        // Check inventory after — should be FULLY RESTORED
        deskInv = config.getInventoryService().getStock("P006");
        System.out.println("\n  Inventory AFTER failed checkout: " + deskInv);
        System.out.println("  (Stock should be fully restored — compensation worked!)");

        // Cart should NOT be cleared (checkout failed)
        Cart cart = controller.handleGetCart(userId);
        System.out.println("  Cart still has items: " + cart.getItemCount());

        System.out.println();
    }

    // ══════════════════════════════════════════════════════════════════════
    // DEMO 5: Saga Rollback — Insufficient Stock
    // ══════════════════════════════════════════════════════════════════════

    private static void demo5_SagaRollback_InsufficientStock() {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 5: Saga Rollback — Insufficient Stock");
        System.out.println("  (First saga step fails, no compensation needed)");
        System.out.println(SEPARATOR);

        AppConfig config = new AppConfig();
        config.seedProducts();
        // Seed VERY limited inventory
        config.seedLimitedInventory("P001", "MacBook Pro 16\"", 1);

        ECommerceController controller = config.getController();
        String userId = "user-diana";

        List<Product> products = controller.handleGetProducts();
        Product macbook = findProduct(products, "MacBook");

        // Try to buy 5 MacBooks when only 1 is in stock
        controller.handleAddToCart(userId, macbook, 5);
        System.out.println("\n  Cart: 5x " + macbook.getName() + " (only 1 in stock!)");

        System.out.println("\n  Checking out (inventory reservation WILL fail)...");
        System.out.println("  ---");

        Order order = controller.handleCheckout(userId,
                config.getWalletPayment(),
                "789 Pine Rd, Austin, TX 78701",
                config.getStandardShipping());

        // Inventory should be unchanged (step 1 failed, nothing to compensate)
        Inventory inv = config.getInventoryService().getStock("P001");
        System.out.println("\n  Inventory after failed checkout: " + inv);
        System.out.println("  (No compensation needed — first step failed before any reservation)");

        System.out.println();
    }

    // ══════════════════════════════════════════════════════════════════════
    // DEMO 6: Discount Pricing
    // ══════════════════════════════════════════════════════════════════════

    private static void demo6_DiscountPricing() {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 6: Discount Pricing (Decorator Pattern)");
        System.out.println(SEPARATOR);

        AppConfig config = new AppConfig();

        PricingStrategy standard = config.getStandardPricing();
        PricingStrategy discount = config.getDiscountPricing();

        // Scenario A: Small order (no discounts)
        List<OrderItem> smallOrder = List.of(
                new OrderItem("P008", "USB-C Hub", 1, 29.99)
        );
        System.out.println("\n  Scenario A: Small order ($29.99)");
        System.out.println("    Standard price: $" + String.format("%.2f", standard.calculatePrice(smallOrder)));
        System.out.println("    Discount price: $" + String.format("%.2f", discount.calculatePrice(smallOrder)));

        // Scenario B: Order > $100 (10% off)
        List<OrderItem> mediumOrder = List.of(
                new OrderItem("P003", "Kindle Paperwhite", 1, 139.99)
        );
        System.out.println("\n  Scenario B: Order > $100 ($139.99)");
        System.out.println("    Standard price: $" + String.format("%.2f", standard.calculatePrice(mediumOrder)));
        System.out.print("    Discount price: ");
        double discountedMedium = discount.calculatePrice(mediumOrder);
        System.out.println("    Result: $" + String.format("%.2f", discountedMedium));

        // Scenario C: Large order with 5+ items (both discounts stack)
        List<OrderItem> largeOrder = List.of(
                new OrderItem("P004", "The Pragmatic Programmer", 3, 49.99),
                new OrderItem("P005", "DDIA", 3, 44.99)
        );
        System.out.println("\n  Scenario C: 6 items, $284.94 (both discounts apply)");
        System.out.println("    Standard price: $" + String.format("%.2f", standard.calculatePrice(largeOrder)));
        System.out.print("    Discount price: ");
        double discountedLarge = discount.calculatePrice(largeOrder);
        System.out.println("    Result: $" + String.format("%.2f", discountedLarge));

        System.out.println();
    }

    // ══════════════════════════════════════════════════════════════════════
    // DEMO 7: Payment Strategy Comparison
    // ══════════════════════════════════════════════════════════════════════

    private static void demo7_PaymentStrategyComparison() {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 7: Payment Strategy Comparison");
        System.out.println("  (Same order, different payment methods)");
        System.out.println(SEPARATOR);

        AppConfig config = new AppConfig();

        String orderId = "ORD-DEMO-007";
        double amount = 94.98;

        System.out.println("\n  Order: " + orderId + ", Amount: $" + String.format("%.2f", amount));

        // Credit Card (95% success)
        System.out.println("\n  1. Credit Card (95% success rate):");
        var ccPayment = config.getCreditCardPayment().processPayment(amount, orderId + "-CC");
        System.out.println("     " + ccPayment);

        // Wallet (99% success)
        System.out.println("\n  2. Wallet (99% success rate):");
        var walletPayment = config.getWalletPayment().processPayment(amount, orderId + "-WAL");
        System.out.println("     " + walletPayment);

        // COD (always succeeds, stays PENDING)
        System.out.println("\n  3. Cash On Delivery (always succeeds, PENDING until delivery):");
        var codPayment = config.getCodPayment().processPayment(amount, orderId + "-COD");
        System.out.println("     " + codPayment);

        System.out.println();
    }

    // ══════════════════════════════════════════════════════════════════════
    // DEMO 8: Shipping Strategy Comparison
    // ══════════════════════════════════════════════════════════════════════

    private static void demo8_ShippingStrategyComparison() {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 8: Shipping Strategy Comparison");
        System.out.println(SEPARATOR);

        AppConfig config = new AppConfig();

        ShippingStrategy standard = config.getStandardShipping();
        ShippingStrategy express = config.getExpressShipping();

        // Scenario A: Order < $50 (no free shipping)
        System.out.println("\n  Scenario A: Order $29.99 (3 items)");
        System.out.printf("    Standard: $%.2f, %d days%n",
                standard.calculateShippingCost(3, 29.99), standard.getEstimatedDays());
        System.out.printf("    Express:  $%.2f, %d days%n",
                express.calculateShippingCost(3, 29.99), express.getEstimatedDays());

        // Scenario B: Order > $50 (free standard shipping)
        System.out.println("\n  Scenario B: Order $139.99 (1 item)");
        System.out.printf("    Standard: $%.2f (FREE over $50), %d days%n",
                standard.calculateShippingCost(1, 139.99), standard.getEstimatedDays());
        System.out.printf("    Express:  $%.2f, %d days%n",
                express.calculateShippingCost(1, 139.99), express.getEstimatedDays());

        // Scenario C: Large order
        System.out.println("\n  Scenario C: Order $2499.99 (10 items)");
        System.out.printf("    Standard: $%.2f (FREE over $50), %d days%n",
                standard.calculateShippingCost(10, 2499.99), standard.getEstimatedDays());
        System.out.printf("    Express:  $%.2f, %d days%n",
                express.calculateShippingCost(10, 2499.99), express.getEstimatedDays());

        System.out.println();
    }

    // ══════════════════════════════════════════════════════════════════════
    // DEMO 9: Concurrent Checkout (Overselling Prevention)
    // ══════════════════════════════════════════════════════════════════════

    private static void demo9_ConcurrentCheckout() {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 9: Concurrent Checkout (Overselling Prevention)");
        System.out.println("  (Multiple users racing for limited stock)");
        System.out.println(SEPARATOR);

        // Fresh config with limited stock
        AppConfig config = new AppConfig();

        // Only 3 headphones in stock, but 5 users try to buy 1 each
        Product headphones = new Product("P002", "Sony WH-1000XM5",
                "Wireless noise-cancelling headphones", 349.99, "Electronics",
                "https://cdn.example.com/sony-xm5.jpg");
        config.getProductService().addProduct(headphones);
        config.seedLimitedInventory("P002", "Sony WH-1000XM5", 3);

        System.out.println("\n  Setup: 3 headphones in stock, 5 users racing to buy 1 each");
        System.out.println("  (Synchronized inventory prevents overselling)\n");

        int numUsers = 5;
        CountDownLatch startLatch = new CountDownLatch(1);  // All threads start at once
        CountDownLatch doneLatch = new CountDownLatch(numUsers);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // Spin up concurrent checkout threads
        ExecutorService executor = Executors.newFixedThreadPool(numUsers);
        for (int i = 0; i < numUsers; i++) {
            final String userId = "race-user-" + (i + 1);
            executor.submit(() -> {
                try {
                    // Add to cart
                    config.getCartService().addToCart(userId, headphones, 1);

                    // Wait for all threads to be ready, then GO!
                    startLatch.await();

                    // Attempt checkout
                    Order order = config.getOrderService().placeOrder(
                            userId,
                            config.getWalletPayment(),
                            "Race Address " + userId,
                            config.getStandardShipping());

                    if (order.getStatus() == OrderStatus.SHIPPED ||
                        order.getStatus() == OrderStatus.PAYMENT_CONFIRMED) {
                        successCount.incrementAndGet();
                        System.out.printf("    [%s] SUCCESS — Order %s placed!%n",
                                userId, order.getOrderId());
                    } else {
                        failCount.incrementAndGet();
                        System.out.printf("    [%s] FAILED  — Order %s, status: %s%n",
                                userId, order.getOrderId(), order.getStatus());
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    System.out.printf("    [%s] FAILED  — %s%n", userId, e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release all threads simultaneously
        startLatch.countDown();

        try {
            doneLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor.shutdown();

        // Results
        Inventory inv = config.getInventoryService().getStock("P002");
        System.out.println("\n  Results:");
        System.out.println("    Successful checkouts: " + successCount.get() + " (expected <= 3)");
        System.out.println("    Failed checkouts: " + failCount.get());
        System.out.println("    Inventory after race: " + inv);
        System.out.println("    Overselling prevented: " +
                (successCount.get() <= 3 ? "YES" : "NO — BUG!"));

        System.out.println();
    }

    // ══════════════════════════════════════════════════════════════════════
    // DEMO 10: Order Lifecycle (State Machine)
    // ══════════════════════════════════════════════════════════════════════

    private static void demo10_OrderLifecycle() {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 10: Order Lifecycle (State Machine Transitions)");
        System.out.println(SEPARATOR);

        // Create an order manually to walk through states
        Order order = new Order.Builder()
                .orderId("ORD-LIFECYCLE-001")
                .userId("user-eve")
                .items(List.of(
                        new OrderItem("P009", "Mechanical Keyboard", 1, 129.99)))
                .totalAmount(129.99)
                .shippingAddress("999 State St, Boston, MA 02101")
                .paymentMethod("CREDIT_CARD")
                .build();

        System.out.println("\n  Starting state: " + order.getStatus());

        // CREATED → INVENTORY_RESERVED
        order.confirmInventory();
        System.out.println("  After confirmInventory(): " + order.getStatus());

        // INVENTORY_RESERVED → PAYMENT_CONFIRMED
        order.confirmPayment();
        System.out.println("  After confirmPayment():   " + order.getStatus());

        // PAYMENT_CONFIRMED → SHIPPED
        order.ship("TRACK-LIFECYCLE-001");
        System.out.println("  After ship():             " + order.getStatus());
        System.out.println("    Tracking ID: " + order.getTrackingId());

        // SHIPPED → DELIVERED
        order.deliver();
        System.out.println("  After deliver():          " + order.getStatus());

        // Try illegal transition: DELIVERED → CANCELLED (should throw)
        System.out.println("\n  Attempting illegal transition: DELIVERED → CANCELLED...");
        try {
            order.cancel();
            System.out.println("    BUG: should have thrown InvalidOrderStateException!");
        } catch (InvalidOrderStateException e) {
            System.out.println("    Correctly rejected: " + e.getMessage());
        }

        // Demonstrate CANCEL → REFUND path
        System.out.println("\n  Demonstrating cancel/refund path:");
        Order order2 = new Order.Builder()
                .orderId("ORD-LIFECYCLE-002")
                .userId("user-frank")
                .items(List.of(
                        new OrderItem("P010", "Monitor 27\" 4K", 1, 449.99)))
                .totalAmount(449.99)
                .shippingAddress("111 Refund Ln, Chicago, IL 60601")
                .paymentMethod("CREDIT_CARD")
                .build();

        order2.confirmInventory();
        order2.confirmPayment();
        System.out.println("  Status: " + order2.getStatus() + " (PAYMENT_CONFIRMED)");

        order2.cancel();
        System.out.println("  After cancel():  " + order2.getStatus());

        order2.refund();
        System.out.println("  After refund():  " + order2.getStatus());

        // Try refunding again (should fail — already REFUNDED)
        System.out.println("\n  Attempting double refund...");
        try {
            order2.refund();
            System.out.println("    BUG: should have thrown!");
        } catch (InvalidOrderStateException e) {
            System.out.println("    Correctly rejected: " + e.getMessage());
        }

        System.out.println();
    }

    // ══════════════════════════════════════════════════════════════════════
    // Design Summary
    // ══════════════════════════════════════════════════════════════════════

    private static void printDesignSummary() {
        System.out.println(SEPARATOR);
        System.out.println("  DESIGN SUMMARY");
        System.out.println(SEPARATOR);
        System.out.println();
        System.out.println("  Patterns Used:");
        System.out.println("    1. Saga Pattern (OrderSagaOrchestrator)");
        System.out.println("       - Orchestration-based distributed transaction");
        System.out.println("       - Steps: Inventory → Payment → Shipping");
        System.out.println("       - Compensation: reverse order on failure");
        System.out.println("       - Why not 2PC: latency, availability, coupling");
        System.out.println();
        System.out.println("    2. Strategy Pattern");
        System.out.println("       - PricingStrategy: Standard, Discount (decorator)");
        System.out.println("       - PaymentStrategy: CreditCard, Wallet, COD");
        System.out.println("       - ShippingStrategy: Standard, Express");
        System.out.println();
        System.out.println("    3. Builder Pattern");
        System.out.println("       - Order, Payment, Cart, SagaResult");
        System.out.println("       - Clean construction of objects with many fields");
        System.out.println();
        System.out.println("    4. Repository Pattern");
        System.out.println("       - Interface + InMemory implementation");
        System.out.println("       - Swappable for DynamoDB/PostgreSQL in production");
        System.out.println();
        System.out.println("    5. Facade Pattern (OrderService)");
        System.out.println("       - Single placeOrder() hides cart/saga/clearing complexity");
        System.out.println();
        System.out.println("    6. State Machine (Order status)");
        System.out.println("       - Guarded transitions prevent illegal state changes");
        System.out.println();
        System.out.println("    7. Decorator Pattern (DiscountPricingStrategy)");
        System.out.println("       - Wraps StandardPricingStrategy, adds discount rules");
        System.out.println();
        System.out.println("  Key Design Decisions:");
        System.out.println("    - Synchronized inventory: prevents overselling under concurrency");
        System.out.println("    - Price snapshot in cart: protects from mid-session price changes");
        System.out.println("    - Idempotent payments: prevents double-charging on retries");
        System.out.println("    - AppConfig as composition root: no 'new' in business logic");
        System.out.println("    - Saga over 2PC: eventual consistency, no distributed locks");
        System.out.println();
        System.out.println("  Scale Considerations (interview talking points):");
        System.out.println("    - Cart: Redis (fast, TTL for abandoned carts)");
        System.out.println("    - Products: Elasticsearch for search, DynamoDB for reads");
        System.out.println("    - Orders: DynamoDB with GSI on userId");
        System.out.println("    - Inventory: Redis DECR for atomic reservation");
        System.out.println("    - Payments: event-driven with dead letter queue for retries");
        System.out.println("    - Notifications: SNS → SQS fan-out (email, push, SMS)");
        System.out.println("    - Shipping: async event, carrier webhook for status updates");
        System.out.println();
        System.out.println(SEPARATOR);
        System.out.println("  END OF E-COMMERCE SYSTEM DEMO");
        System.out.println(SEPARATOR);
    }

    // ── Utility ──────────────────────────────────────────────────────────

    /**
     * Finds a product by partial name match (case-insensitive).
     * Used by demos to avoid hardcoding product references.
     */
    private static Product findProduct(List<Product> products, String nameFragment) {
        return products.stream()
                .filter(p -> p.getName().toLowerCase().contains(nameFragment.toLowerCase()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Product not found: " + nameFragment));
    }
}
