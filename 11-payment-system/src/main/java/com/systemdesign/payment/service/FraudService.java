package com.systemdesign.payment.service;

import com.systemdesign.payment.model.FraudResult;
import com.systemdesign.payment.model.Payment;
import com.systemdesign.payment.strategy.fraud.FraudCheckStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * FraudService — Orchestrates multiple fraud detection strategies.
 *
 * DESIGN: Chain of Responsibility / Composite Strategy
 *   Holds a list of FraudCheckStrategy implementations.
 *   Runs ALL strategies on every payment.
 *   If ANY strategy flags the payment, it's blocked.
 *
 * WHY run all strategies even if the first fails?
 *   1. We want to collect ALL reasons for flagging (better for fraud analysts)
 *   2. The combined risk score from all strategies is more informative
 *   3. In monitoring, we want to see which strategies are triggering most often
 *
 * CALL CHAIN:
 *   PaymentService.processPayment()
 *     → FraudService.checkPayment(payment)
 *       → for each FraudCheckStrategy:
 *           strategy.checkFraud(payment) → FraudResult
 *       → combine all results
 *       → return combined FraudResult (fail if ANY failed)
 */
public class FraudService {

    private final List<FraudCheckStrategy> strategies;

    public FraudService(List<FraudCheckStrategy> strategies) {
        this.strategies = strategies;
    }

    /**
     * Run all fraud check strategies on the payment.
     *
     * @param payment the payment to check
     * @return combined FraudResult — fails if ANY strategy fails
     */
    public FraudResult checkPayment(Payment payment) {
        boolean allPassed = true;
        double maxRiskScore = 0.0;
        List<String> allReasons = new ArrayList<>();

        System.out.println("    [FraudService] Running " + strategies.size() + " fraud check strategies...");

        for (FraudCheckStrategy strategy : strategies) {
            FraudResult result = strategy.checkFraud(payment);

            System.out.println("      " + strategy.getName() + " → "
                               + (result.isPassed() ? "PASS" : "FAIL")
                               + " (score: " + String.format("%.2f", result.getRiskScore()) + ")");

            if (!result.isPassed()) {
                allPassed = false;
                allReasons.addAll(result.getReasons());
            }
            maxRiskScore = Math.max(maxRiskScore, result.getRiskScore());
        }

        if (allPassed) {
            return FraudResult.pass(maxRiskScore);
        } else {
            return FraudResult.fail(maxRiskScore, allReasons);
        }
    }
}
