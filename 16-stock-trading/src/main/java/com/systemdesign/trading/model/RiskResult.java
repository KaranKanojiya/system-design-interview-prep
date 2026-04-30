package com.systemdesign.trading.model;

/**
 * RiskResult encapsulates the outcome of a risk check.
 *
 * WHY static factories instead of constructors:
 * - RiskResult.pass() reads like English and clearly conveys intent.
 * - RiskResult.reject("Insufficient margin") is self-documenting.
 * - Prevents ambiguity: new RiskResult(true, null) vs new RiskResult(false, "reason")
 *   is less readable than pass() vs reject("reason").
 *
 * WHY rejectionReason is a String (not an exception):
 * - Risk rejection is a normal business outcome, not an exceptional case.
 * - We want to collect ALL rejection reasons (not just the first), so exceptions
 *   would require catching and accumulating them.
 * - In production, this might include a rejection code (e.g., "MARGIN_001") for i18n.
 *
 * CALL CHAIN:
 * RiskCheckStrategy.check() returns RiskResult → RiskService chains all checks →
 * first reject stops the chain → TradingService reads rejectionReason → NotificationService
 */
public class RiskResult {

    private final boolean passed;
    private final String rejectionReason;

    private RiskResult(boolean passed, String rejectionReason) {
        this.passed = passed;
        this.rejectionReason = rejectionReason;
    }

    /** Risk check passed — order is good to go. */
    public static RiskResult pass() {
        return new RiskResult(true, null);
    }

    /** Risk check failed — order is rejected with a reason. */
    public static RiskResult reject(String reason) {
        return new RiskResult(false, reason);
    }

    public boolean isPassed() { return passed; }
    public String getRejectionReason() { return rejectionReason; }

    @Override
    public String toString() {
        return passed ? "RiskResult{PASSED}" : "RiskResult{REJECTED: " + rejectionReason + "}";
    }
}
