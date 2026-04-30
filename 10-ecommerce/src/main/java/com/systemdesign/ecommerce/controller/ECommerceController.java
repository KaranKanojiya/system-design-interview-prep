package com.systemdesign.ecommerce.controller;

import com.systemdesign.ecommerce.exception.ECommerceException;
import com.systemdesign.ecommerce.model.Cart;
import com.systemdesign.ecommerce.model.Order;
import com.systemdesign.ecommerce.model.Product;
import com.systemdesign.ecommerce.service.CartService;
import com.systemdesign.ecommerce.service.OrderService;
import com.systemdesign.ecommerce.service.ProductService;
import com.systemdesign.ecommerce.strategy.payment.PaymentStrategy;
import com.systemdesign.ecommerce.strategy.shipping.ShippingStrategy;

import java.util.List;

/**
 * ECommerceController — Simulated REST controller.
 *
 * Interview notes:
 * - In a real system, each "handle" method maps to an HTTP endpoint:
 *     handleAddToCart      → POST /api/cart/items
 *     handleCheckout       → POST /api/orders
 *     handleCancelOrder    → POST /api/orders/{id}/cancel
 *     handleGetOrder       → GET  /api/orders/{id}
 *     handleGetProducts    → GET  /api/products
 * - The controller catches domain exceptions and returns error messages
 *   (in production: HTTP status codes + JSON error bodies).
 * - No business logic here — pure delegation to services.
 *
 * Call chain: Main → Controller → Service → Repository
 */
public class ECommerceController {

    private final ProductService productService;
    private final CartService cartService;
    private final OrderService orderService;

    public ECommerceController(ProductService productService,
                               CartService cartService,
                               OrderService orderService) {
        this.productService = productService;
        this.cartService = cartService;
        this.orderService = orderService;
    }

    // ── Product endpoints ────────────────────────────────────────────────

    /**
     * GET /api/products — List all products.
     */
    public List<Product> handleGetProducts() {
        return productService.getAllProducts();
    }

    /**
     * GET /api/products/search?name=... — Search by name.
     */
    public List<Product> handleSearchProducts(String nameFragment) {
        return productService.searchByName(nameFragment);
    }

    /**
     * GET /api/products/category/{category} — Filter by category.
     */
    public List<Product> handleGetProductsByCategory(String category) {
        return productService.searchByCategory(category);
    }

    // ── Cart endpoints ───────────────────────────────────────────────────

    /**
     * POST /api/cart/items — Add product to cart.
     */
    public Cart handleAddToCart(String userId, Product product, int quantity) {
        return cartService.addToCart(userId, product, quantity);
    }

    /**
     * DELETE /api/cart/items/{productId} — Remove from cart.
     */
    public Cart handleRemoveFromCart(String userId, String productId) {
        return cartService.removeFromCart(userId, productId);
    }

    /**
     * GET /api/cart — Get user's cart.
     */
    public Cart handleGetCart(String userId) {
        return cartService.getCart(userId);
    }

    // ── Order endpoints ──────────────────────────────────────────────────

    /**
     * POST /api/orders — Checkout (create order from cart, execute saga).
     */
    public Order handleCheckout(String userId, PaymentStrategy paymentStrategy,
                                String shippingAddress, ShippingStrategy shippingStrategy) {
        try {
            return orderService.placeOrder(userId, paymentStrategy,
                    shippingAddress, shippingStrategy);
        } catch (ECommerceException e) {
            System.out.println("  [Controller] Checkout failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * POST /api/orders/{id}/cancel — Cancel an order.
     */
    public Order handleCancelOrder(String orderId) {
        try {
            return orderService.cancelOrder(orderId);
        } catch (ECommerceException e) {
            System.out.println("  [Controller] Cancel failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * GET /api/orders/{id} — Get order by ID.
     */
    public Order handleGetOrder(String orderId) {
        try {
            return orderService.getOrder(orderId);
        } catch (ECommerceException e) {
            System.out.println("  [Controller] Order not found: " + e.getMessage());
            return null;
        }
    }

    /**
     * GET /api/orders?userId=... — Get orders for user.
     */
    public List<Order> handleGetOrdersByUser(String userId) {
        return orderService.getOrdersByUser(userId);
    }
}
