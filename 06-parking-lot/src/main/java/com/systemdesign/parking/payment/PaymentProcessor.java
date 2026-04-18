package com.systemdesign.parking.payment;

import com.systemdesign.parking.model.Payment;
import com.systemdesign.parking.model.PaymentMethod;

/**
 * Interface for payment processing strategies.
 *
 * Demonstrates:
 * - Strategy pattern: each payment method has its own processor
 * - Interface Segregation: processors only implement what they need
 * - Dependency Inversion: ParkingService depends on this abstraction
 */
public interface PaymentProcessor {

    /**
     * Process a payment for the given ticket.
     *
     * @param ticketId the ticket being paid
     * @param amount   the amount to charge
     * @param method   the payment method used
     * @return a Payment object with the transaction result
     */
    Payment processPayment(String ticketId, double amount, PaymentMethod method);

    /**
     * The payment method this processor handles.
     */
    PaymentMethod supportedMethod();
}
