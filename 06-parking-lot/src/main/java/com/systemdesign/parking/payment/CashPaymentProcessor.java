package com.systemdesign.parking.payment;

import com.systemdesign.parking.model.Payment;
import com.systemdesign.parking.model.PaymentMethod;
import com.systemdesign.parking.model.PaymentStatus;

/**
 * Cash payment processor. Always succeeds (cash is always accepted).
 */
public class CashPaymentProcessor implements PaymentProcessor {

    @Override
    public Payment processPayment(String ticketId, double amount, PaymentMethod method) {
        System.out.printf("  [PAYMENT] Cash: Received $%.2f | Change: $0.00%n", amount);
        return new Payment(ticketId, amount, PaymentMethod.CASH, PaymentStatus.COMPLETED);
    }

    @Override
    public PaymentMethod supportedMethod() {
        return PaymentMethod.CASH;
    }
}
