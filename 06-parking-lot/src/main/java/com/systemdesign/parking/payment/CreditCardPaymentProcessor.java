package com.systemdesign.parking.payment;

import com.systemdesign.parking.model.Payment;
import com.systemdesign.parking.model.PaymentMethod;
import com.systemdesign.parking.model.PaymentStatus;

import java.util.Random;

/**
 * Credit card payment processor. Has a 95% success rate to simulate
 * real-world card declines.
 */
public class CreditCardPaymentProcessor implements PaymentProcessor {

    private static final double SUCCESS_RATE = 0.95;
    private final Random random;

    public CreditCardPaymentProcessor() {
        this.random = new Random();
    }

    /**
     * Constructor with seeded Random for deterministic testing.
     */
    public CreditCardPaymentProcessor(Random random) {
        this.random = random;
    }

    @Override
    public Payment processPayment(String ticketId, double amount, PaymentMethod method) {
        boolean success = random.nextDouble() < SUCCESS_RATE;

        if (success) {
            System.out.printf("  [PAYMENT] Credit Card: Charged $%.2f%n", amount);
            return new Payment(ticketId, amount, PaymentMethod.CREDIT_CARD, PaymentStatus.COMPLETED);
        } else {
            System.out.printf("  [PAYMENT] Credit Card: DECLINED $%.2f%n", amount);
            return new Payment(ticketId, amount, PaymentMethod.CREDIT_CARD, PaymentStatus.FAILED);
        }
    }

    @Override
    public PaymentMethod supportedMethod() {
        return PaymentMethod.CREDIT_CARD;
    }
}
