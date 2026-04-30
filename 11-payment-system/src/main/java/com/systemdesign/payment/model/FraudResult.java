package com.systemdesign.payment.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * FraudResult — The outcome of running one or more fraud checks on a payment.
 *
 * Contains:
 *   - passed: did the payment pass the fraud check?
 *   - riskScore: 0.0 (no risk) to 1.0 (definite fraud)
 *   - reasons: human-readable explanations of why it was flagged
 *
 * Uses static factory methods instead of constructors for readability:
 *   FraudResult.pass(0.1)
 *   FraudResult.fail(0.95, List.of("Amount exceeds $10,000", "Velocity check failed"))
 */
public class FraudResult {

    private final boolean passed;
    private final double riskScore;
    private final List<String> reasons;

    private FraudResult(boolean passed, double riskScore, List<String> reasons) {
        this.passed = passed;
        this.riskScore = riskScore;
        this.reasons = Collections.unmodifiableList(new ArrayList<>(reasons));
    }

    // ── Static Factories ──

    /** Payment passed fraud check with the given risk score. */
    public static FraudResult pass(double riskScore) {
        return new FraudResult(true, riskScore, List.of());
    }

    /** Payment failed fraud check — blocked. */
    public static FraudResult fail(double riskScore, List<String> reasons) {
        return new FraudResult(false, riskScore, reasons);
    }

    // ── Getters ──
    public boolean isPassed() { return passed; }
    public double getRiskScore() { return riskScore; }
    public List<String> getReasons() { return reasons; }

    @Override
    public String toString() {
        return "FraudResult{" +
                "passed=" + passed +
                ", riskScore=" + String.format("%.2f", riskScore) +
                ", reasons=" + reasons +
                '}';
    }
}
