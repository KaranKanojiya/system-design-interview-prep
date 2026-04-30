package com.systemdesign.payment.exception;

import com.systemdesign.payment.model.FraudResult;

/**
 * FraudDetectedException — Thrown when fraud checks block a payment.
 *
 * Contains the FraudResult with risk score and reasons, so the
 * caller can log details for the fraud investigation team.
 */
public class FraudDetectedException extends PaymentException {

    private final FraudResult fraudResult;

    public FraudDetectedException(FraudResult fraudResult) {
        super("Payment blocked by fraud detection: risk score="
              + String.format("%.2f", fraudResult.getRiskScore())
              + ", reasons=" + fraudResult.getReasons());
        this.fraudResult = fraudResult;
    }

    public FraudResult getFraudResult() { return fraudResult; }
}
