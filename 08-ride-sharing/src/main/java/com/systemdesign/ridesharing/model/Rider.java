package com.systemdesign.ridesharing.model;

/**
 * Rider — A person requesting a ride.
 *
 * In production Uber, a rider has:
 *   - Profile info (name, phone, email)
 *   - Payment methods (multiple, with a default)
 *   - Rating (average of driver ratings after each trip)
 *   - Ride history
 *   - Saved locations (home, work)
 *
 * For this demo, we keep it simple: id, name, rating, payment method, location.
 */
public class Rider {

    private final String id;
    private final String name;
    private double rating;
    private PaymentMethod paymentMethod;
    private Location currentLocation;

    public Rider(String id, String name, double rating, PaymentMethod paymentMethod, Location currentLocation) {
        this.id = id;
        this.name = name;
        this.rating = rating;
        this.paymentMethod = paymentMethod;
        this.currentLocation = currentLocation;
    }

    // --- Getters ---

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public double getRating() {
        return rating;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public Location getCurrentLocation() {
        return currentLocation;
    }

    // --- Setters ---

    public void setRating(double rating) {
        this.rating = rating;
    }

    public void setPaymentMethod(PaymentMethod paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public void setCurrentLocation(Location currentLocation) {
        this.currentLocation = currentLocation;
    }

    @Override
    public String toString() {
        return String.format("Rider{id='%s', name='%s', rating=%.1f, payment=%s, location=%s}",
                id, name, rating, paymentMethod, currentLocation);
    }
}
