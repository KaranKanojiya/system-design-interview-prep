package com.systemdesign.payment.strategy.fraud;

import com.systemdesign.payment.model.FraudResult;
import com.systemdesign.payment.model.Payment;

/**
 * FraudCheckStrategy — Strategy interface for fraud detection.
 *
 * STRATEGY PATTERN:
 *   Different fraud detection approaches can be plugged in:
 *   - Rule-based: deterministic rules (amount thresholds, velocity checks)
 *   - ML-based: trained models that score risk based on features
 *   - Third-party: calls to external fraud services (Sift, Riskified)
 *
 * CHAIN OF RESPONSIBILITY:
 *   FraudService holds a List<FraudCheckStrategy> and runs ALL of them.
 *   If ANY strategy returns a "fail" result, the payment is blocked.
 *   This is defense-in-depth: the rule-based check catches obvious fraud,
 *   and the ML model catches subtle patterns the rules miss.
 *
 * CALL CHAIN:
 *   PaymentService.processPayment()
 *     → FraudService.checkPayment(payment)
 *       → RuleBasedFraudCheck.checkFraud(payment)  → FraudResult
 *       → MLFraudCheck.checkFraud(payment)          → FraudResult
 *       → combine results → final FraudResult
 *     → if failed, throw FraudDetectedException
 */
public interface FraudCheckStrategy {

    /**
     * Run fraud checks on the given payment.
     *
     * @param payment the payment to check
     * @return FraudResult indicating pass/fail, risk score, and reasons
     */
    FraudResult checkFraud(Payment payment);

    /**
     * @return human-readable name of this fraud check strategy
     */
    String getName();
}
