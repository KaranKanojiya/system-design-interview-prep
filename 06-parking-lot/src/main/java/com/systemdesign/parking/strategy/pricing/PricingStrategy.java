package com.systemdesign.parking.strategy.pricing;

import com.systemdesign.parking.model.ticket.ParkingTicket;

/**
 * Strategy interface for parking fee calculation algorithms.
 *
 * Demonstrates:
 * - Strategy pattern: different pricing models are interchangeable
 * - Single Responsibility: each strategy handles one pricing model
 * - Open/Closed: add new pricing (surge, membership, etc.) without modifying existing code
 */
public interface PricingStrategy {

    /**
     * Calculate the parking fee for the given ticket.
     *
     * @param ticket the parking ticket with entry/exit times and vehicle type
     * @return the calculated fee amount
     */
    double calculateFee(ParkingTicket ticket);

    /**
     * Human-readable name of this pricing strategy.
     */
    String name();
}
