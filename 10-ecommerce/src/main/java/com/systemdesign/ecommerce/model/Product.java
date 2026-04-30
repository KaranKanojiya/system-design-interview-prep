package com.systemdesign.ecommerce.model;

/**
 * Product — Core domain entity representing an item in the catalog.
 *
 * Interview notes:
 * - Immutable after creation (no setters) — thread-safe by design.
 * - price is a double here for simplicity; in production use BigDecimal
 *   to avoid floating-point rounding in monetary calculations.
 * - imageUrl is a placeholder for a CDN link; in a real system this would
 *   point to an object-storage URL (S3, GCS, etc.).
 */
public class Product {

    private final String id;
    private final String name;
    private final String description;
    private final double price;
    private final String category;
    private final String imageUrl;

    public Product(String id, String name, String description,
                   double price, String category, String imageUrl) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.category = category;
        this.imageUrl = imageUrl;
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public String getId()          { return id; }
    public String getName()        { return name; }
    public String getDescription() { return description; }
    public double getPrice()       { return price; }
    public String getCategory()    { return category; }
    public String getImageUrl()    { return imageUrl; }

    @Override
    public String toString() {
        return String.format("Product{id='%s', name='%s', price=$%.2f, category='%s'}",
                id, name, price, category);
    }
}
