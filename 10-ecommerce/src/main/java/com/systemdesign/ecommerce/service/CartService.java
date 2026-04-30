package com.systemdesign.ecommerce.service;

import com.systemdesign.ecommerce.model.Cart;
import com.systemdesign.ecommerce.model.CartItem;
import com.systemdesign.ecommerce.model.Order;
import com.systemdesign.ecommerce.model.OrderItem;
import com.systemdesign.ecommerce.model.Product;
import com.systemdesign.ecommerce.repository.CartRepository;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CartService — Manages user shopping carts.
 *
 * Interview notes:
 * - One cart per user (lazy-created on first addToCart).
 * - checkout() converts the cart into an Order and returns it.
 *   The caller (OrderService) then runs the saga on that order.
 * - Cart is NOT cleared here — only after the saga succeeds, so a failed
 *   checkout doesn't lose the cart contents.
 *
 * Call chain: Controller → CartService → CartRepository
 *             OrderService → CartService.checkout() to create Order from cart
 */
public class CartService {

    private final CartRepository cartRepository;

    public CartService(CartRepository cartRepository) {
        this.cartRepository = cartRepository;
    }

    // ── Cart operations ──────────────────────────────────────────────────

    /**
     * Adds a product to the user's cart. Creates the cart if it doesn't exist.
     */
    public Cart addToCart(String userId, Product product, int quantity) {
        Cart cart = getOrCreateCart(userId);
        cart.addItem(product, quantity);
        cartRepository.save(cart);
        return cart;
    }

    /**
     * Removes a product from the user's cart.
     */
    public Cart removeFromCart(String userId, String productId) {
        Cart cart = getOrCreateCart(userId);
        cart.removeItem(productId);
        cartRepository.save(cart);
        return cart;
    }

    /**
     * Updates the quantity for a product in the user's cart.
     */
    public Cart updateQuantity(String userId, String productId, int newQty) {
        Cart cart = getOrCreateCart(userId);
        cart.updateQuantity(productId, newQty);
        cartRepository.save(cart);
        return cart;
    }

    /**
     * Retrieves the user's cart (or creates an empty one).
     */
    public Cart getCart(String userId) {
        return getOrCreateCart(userId);
    }

    /**
     * Converts the cart into an Order.
     *
     * This does NOT clear the cart — the caller should clear it only after
     * the saga completes successfully. If payment fails, the cart is intact.
     */
    public Order checkout(String userId, String paymentMethod, String shippingAddress) {
        Cart cart = getOrCreateCart(userId);

        if (cart.getItems().isEmpty()) {
            throw new IllegalStateException("Cannot checkout an empty cart for user: " + userId);
        }

        // Convert CartItems → OrderItems (snapshot all data)
        List<OrderItem> orderItems = cart.getItems().stream()
                .map(ci -> new OrderItem(
                        ci.getProduct().getId(),
                        ci.getProduct().getName(),
                        ci.getQuantity(),
                        ci.getPriceSnapshot()  // Use the snapshotted price, not current
                ))
                .collect(Collectors.toList());

        double total = cart.getTotal();

        return new Order.Builder()
                .orderId("ORD-" + UUID.randomUUID().toString().substring(0, 8))
                .userId(userId)
                .items(orderItems)
                .totalAmount(total)
                .shippingAddress(shippingAddress)
                .paymentMethod(paymentMethod)
                .build();
    }

    /**
     * Clears the user's cart (called after successful checkout).
     */
    public void clearCart(String userId) {
        Cart cart = getOrCreateCart(userId);
        cart.clear();
        cartRepository.save(cart);
    }

    // ── Internal helper ──────────────────────────────────────────────────

    private Cart getOrCreateCart(String userId) {
        return cartRepository.findByUserId(userId)
                .orElseGet(() -> {
                    Cart newCart = new Cart.Builder()
                            .cartId("CART-" + UUID.randomUUID().toString().substring(0, 8))
                            .userId(userId)
                            .build();
                    cartRepository.save(newCart);
                    return newCart;
                });
    }
}
