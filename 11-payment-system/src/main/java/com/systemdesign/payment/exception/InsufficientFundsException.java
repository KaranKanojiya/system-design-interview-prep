package com.systemdesign.payment.exception;

/**
 * InsufficientFundsException — Thrown when an account debit exceeds available balance.
 *
 * Can be thrown by:
 *   - Account.debit() — customer doesn't have enough money
 *   - CreditCardProcessor — simulated decline reason
 */
public class InsufficientFundsException extends PaymentException {

    public InsufficientFundsException(String message) {
        super(message);
    }
}
