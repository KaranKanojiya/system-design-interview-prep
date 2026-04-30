package com.systemdesign.ecommerce.config;

import com.systemdesign.ecommerce.controller.ECommerceController;
import com.systemdesign.ecommerce.display.OrderStatsDisplay;
import com.systemdesign.ecommerce.model.Inventory;
import com.systemdesign.ecommerce.model.Product;
import com.systemdesign.ecommerce.repository.CartRepository;
import com.systemdesign.ecommerce.repository.InMemoryCartRepository;
import com.systemdesign.ecommerce.repository.InMemoryInventoryRepository;
import com.systemdesign.ecommerce.repository.InMemoryOrderRepository;
import com.systemdesign.ecommerce.repository.InMemoryPaymentRepository;
import com.systemdesign.ecommerce.repository.InMemoryProductRepository;
import com.systemdesign.ecommerce.repository.InventoryRepository;
import com.systemdesign.ecommerce.repository.OrderRepository;
import com.systemdesign.ecommerce.repository.PaymentRepository;
import com.systemdesign.ecommerce.repository.ProductRepository;
import com.systemdesign.ecommerce.saga.OrderSagaOrchestrator;
import com.systemdesign.ecommerce.service.CartService;
import com.systemdesign.ecommerce.service.InventoryService;
import com.systemdesign.ecommerce.service.NotificationService;
import com.systemdesign.ecommerce.service.OrderService;
import com.systemdesign.ecommerce.service.PaymentService;
import com.systemdesign.ecommerce.service.ProductService;
import com.systemdesign.ecommerce.service.ShippingService;
import com.systemdesign.ecommerce.strategy.payment.CODPaymentStrategy;
import com.systemdesign.ecommerce.strategy.payment.CreditCardPaymentStrategy;
import com.systemdesign.ecommerce.strategy.payment.PaymentStrategy;
import com.systemdesign.ecommerce.strategy.payment.WalletPaymentStrategy;
import com.systemdesign.ecommerce.strategy.pricing.DiscountPricingStrategy;
import com.systemdesign.ecommerce.strategy.pricing.PricingStrategy;
import com.systemdesign.ecommerce.strategy.pricing.StandardPricingStrategy;
import com.systemdesign.ecommerce.strategy.shipping.ExpressShippingStrategy;
import com.systemdesign.ecommerce.strategy.shipping.ShippingStrategy;
import com.systemdesign.ecommerce.strategy.shipping.StandardShippingStrategy;

/**
 * AppConfig — FACTORY. The ONLY place where "new ConcreteClass()" appears.
 *
 * Interview notes:
 * - This is the Composition Root: all objects are wired here, and only
 *   interfaces flow through the rest of the system.
 * - In production, a DI container (Spring, Guice) would handle this.
 *   Here we do manual wiring to show the dependency graph explicitly.
 *
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │                      DEPENDENCY GRAPH                                │
 * │                                                                      │
 * │  ECommerceController                                                 │
 * │    ├── ProductService ← ProductRepository (InMemory)                │
 * │    ├── CartService ← CartRepository (InMemory)                      │
 * │    └── OrderService                                                  │
 * │          ├── CartService (shared)                                    │
 * │          ├── OrderRepository (InMemory)                             │
 * │          ├── ShippingService                                         │
 * │          └── OrderSagaOrchestrator                                  │
 * │                ├── InventoryService ← InventoryRepository (InMemory)│
 * │                ├── PaymentService ← PaymentRepository (InMemory)    │
 * │                ├── ShippingService (shared)                          │
 * │                └── NotificationService                               │
 * │                                                                      │
 * │  OrderStatsDisplay                                                   │
 * │    ├── OrderService (shared)                                         │
 * │    ├── InventoryService (shared)                                     │
 * │    └── PaymentService (shared)                                       │
 * └──────────────────────────────────────────────────────────────────────┘
 */
public class AppConfig {

    // ── Repositories (data layer) ────────────────────────────────────────
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final InventoryRepository inventoryRepository;
    private final CartRepository cartRepository;
    private final PaymentRepository paymentRepository;

    // ── Services (business logic layer) ──────────────────────────────────
    private final ProductService productService;
    private final CartService cartService;
    private final InventoryService inventoryService;
    private final PaymentService paymentService;
    private final ShippingService shippingService;
    private final NotificationService notificationService;
    private final OrderSagaOrchestrator sagaOrchestrator;
    private final OrderService orderService;

    // ── Presentation layer ───────────────────────────────────────────────
    private final ECommerceController controller;
    private final OrderStatsDisplay statsDisplay;

    // ── Strategies ───────────────────────────────────────────────────────
    private final PricingStrategy standardPricing;
    private final PricingStrategy discountPricing;
    private final PaymentStrategy creditCardPayment;
    private final PaymentStrategy walletPayment;
    private final PaymentStrategy codPayment;
    private final ShippingStrategy standardShipping;
    private final ShippingStrategy expressShipping;

    public AppConfig() {
        // ── 1. Instantiate repositories ──────────────────────────────────
        this.productRepository = new InMemoryProductRepository();
        this.orderRepository = new InMemoryOrderRepository();
        this.inventoryRepository = new InMemoryInventoryRepository();
        this.cartRepository = new InMemoryCartRepository();
        this.paymentRepository = new InMemoryPaymentRepository();

        // ── 2. Instantiate services (inject repositories) ────────────────
        this.productService = new ProductService(productRepository);
        this.cartService = new CartService(cartRepository);
        this.inventoryService = new InventoryService(inventoryRepository);
        this.paymentService = new PaymentService(paymentRepository);
        this.shippingService = new ShippingService();
        this.notificationService = new NotificationService();

        // ── 3. Instantiate saga orchestrator (inject services) ───────────
        this.sagaOrchestrator = new OrderSagaOrchestrator(
                inventoryService, paymentService, shippingService, notificationService);

        // ── 4. Instantiate OrderService (facade, inject everything) ──────
        this.orderService = new OrderService(
                cartService, sagaOrchestrator, orderRepository, shippingService);

        // ── 5. Instantiate controller (inject services) ──────────────────
        this.controller = new ECommerceController(
                productService, cartService, orderService);

        // ── 6. Instantiate stats display ─────────────────────────────────
        this.statsDisplay = new OrderStatsDisplay(
                orderService, inventoryService, paymentService);

        // ── 7. Instantiate strategies ────────────────────────────────────
        this.standardPricing = new StandardPricingStrategy();
        this.discountPricing = new DiscountPricingStrategy(standardPricing);
        this.creditCardPayment = new CreditCardPaymentStrategy();
        this.walletPayment = new WalletPaymentStrategy();
        this.codPayment = new CODPaymentStrategy();
        this.standardShipping = new StandardShippingStrategy();
        this.expressShipping = new ExpressShippingStrategy();
    }

    // ── Seed data ────────────────────────────────────────────────────────

    /**
     * Seeds the product catalog with sample products across categories.
     */
    public void seedProducts() {
        productService.addProduct(new Product("P001", "MacBook Pro 16\"",
                "Apple M3 Max, 36GB RAM, 1TB SSD", 2499.99, "Electronics",
                "https://cdn.example.com/macbook-pro.jpg"));

        productService.addProduct(new Product("P002", "Sony WH-1000XM5",
                "Wireless noise-cancelling headphones", 349.99, "Electronics",
                "https://cdn.example.com/sony-xm5.jpg"));

        productService.addProduct(new Product("P003", "Kindle Paperwhite",
                "6.8\" display, 16GB, waterproof", 139.99, "Electronics",
                "https://cdn.example.com/kindle.jpg"));

        productService.addProduct(new Product("P004", "The Pragmatic Programmer",
                "20th Anniversary Edition", 49.99, "Books",
                "https://cdn.example.com/pragmatic.jpg"));

        productService.addProduct(new Product("P005", "Designing Data-Intensive Applications",
                "By Martin Kleppmann", 44.99, "Books",
                "https://cdn.example.com/ddia.jpg"));

        productService.addProduct(new Product("P006", "Standing Desk",
                "Electric height adjustable, 60x30 inch", 399.99, "Furniture",
                "https://cdn.example.com/desk.jpg"));

        productService.addProduct(new Product("P007", "Ergonomic Chair",
                "Mesh back, lumbar support, adjustable armrests", 299.99, "Furniture",
                "https://cdn.example.com/chair.jpg"));

        productService.addProduct(new Product("P008", "USB-C Hub",
                "7-in-1: HDMI, USB-A, SD, ethernet", 29.99, "Accessories",
                "https://cdn.example.com/usbc-hub.jpg"));

        productService.addProduct(new Product("P009", "Mechanical Keyboard",
                "Cherry MX Brown, TKL, backlit", 129.99, "Accessories",
                "https://cdn.example.com/keyboard.jpg"));

        productService.addProduct(new Product("P010", "Monitor 27\" 4K",
                "IPS, USB-C, 60Hz", 449.99, "Electronics",
                "https://cdn.example.com/monitor.jpg"));
    }

    /**
     * Seeds inventory for all products.
     */
    public void seedInventory() {
        inventoryRepository.save(new Inventory("P001", "MacBook Pro 16\"", 50));
        inventoryRepository.save(new Inventory("P002", "Sony WH-1000XM5", 100));
        inventoryRepository.save(new Inventory("P003", "Kindle Paperwhite", 200));
        inventoryRepository.save(new Inventory("P004", "The Pragmatic Programmer", 500));
        inventoryRepository.save(new Inventory("P005", "Designing Data-Intensive Applications", 500));
        inventoryRepository.save(new Inventory("P006", "Standing Desk", 30));
        inventoryRepository.save(new Inventory("P007", "Ergonomic Chair", 75));
        inventoryRepository.save(new Inventory("P008", "USB-C Hub", 300));
        inventoryRepository.save(new Inventory("P009", "Mechanical Keyboard", 150));
        inventoryRepository.save(new Inventory("P010", "Monitor 27\" 4K", 60));
    }

    /**
     * Seeds limited inventory for flash-sale / concurrent checkout demos.
     */
    public void seedLimitedInventory(String productId, String productName, int stock) {
        inventoryRepository.save(new Inventory(productId, productName, stock));
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public ECommerceController getController()       { return controller; }
    public OrderStatsDisplay getStatsDisplay()       { return statsDisplay; }
    public ProductService getProductService()        { return productService; }
    public CartService getCartService()              { return cartService; }
    public OrderService getOrderService()            { return orderService; }
    public InventoryService getInventoryService()    { return inventoryService; }
    public PaymentService getPaymentService()        { return paymentService; }
    public ShippingService getShippingService()      { return shippingService; }
    public NotificationService getNotificationService() { return notificationService; }
    public OrderSagaOrchestrator getSagaOrchestrator()  { return sagaOrchestrator; }

    // ── Strategy getters ─────────────────────────────────────────────────

    public PricingStrategy getStandardPricing()     { return standardPricing; }
    public PricingStrategy getDiscountPricing()     { return discountPricing; }
    public PaymentStrategy getCreditCardPayment()   { return creditCardPayment; }
    public PaymentStrategy getWalletPayment()       { return walletPayment; }
    public PaymentStrategy getCodPayment()          { return codPayment; }
    public ShippingStrategy getStandardShipping()   { return standardShipping; }
    public ShippingStrategy getExpressShipping()    { return expressShipping; }
}
