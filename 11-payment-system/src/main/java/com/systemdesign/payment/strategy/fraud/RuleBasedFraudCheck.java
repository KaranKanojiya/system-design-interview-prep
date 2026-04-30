package com.systemdesign.payment.strategy.fraud;

import com.systemdesign.payment.model.FraudResult;
import com.systemdesign.payment.model.Payment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RuleBasedFraudCheck — Deterministic fraud detection using business rules.
 *
 * RULES IMPLEMENTED:
 *   1. Amount threshold: payment > $10,000 → flag (high-value fraud risk)
 *   2. Velocity check: > 5 transactions from same customer in 1 minute → flag
 *   3. Blacklisted merchant: known bad merchants → flag
 *
 * WHY rule-based?
 *   - Fast: O(1) checks, no model inference
 *   - Explainable: "blocked because amount > $10,000" is easy to understand
 *   - Tunable: business team can adjust thresholds without retraining a model
 *   - Catches obvious fraud that ML might miss (ML optimizes for patterns,
 *     rules enforce absolute limits)
 *
 * LIMITATIONS:
 *   - Can't catch subtle patterns (e.g. unusual purchase time + location)
 *   - Rules are brittle: fraudsters learn the thresholds and stay just below
 *   - That's why we also run MLFraudCheck in the chain
 *
 * RISK SCORE CALCULATION:
 *   Each triggered rule adds to the risk score.  The final score is
 *   capped at 1.0.  If any rule triggers, the payment fails.
 */
public class RuleBasedFraudCheck implements FraudCheckStrategy {

    // Amount threshold — payments above this are flagged
    private static final double HIGH_AMOUNT_THRESHOLD = 10_000.0;

    // Velocity limit — max transactions per customer per minute
    private static final int MAX_TRANSACTIONS_PER_MINUTE = 5;

    // Blacklisted merchants — known bad actors
    private final Set<String> blacklistedMerchants;

    // Velocity tracking: customerId → list of transaction timestamps (millis)
    // WHY ConcurrentHashMap? Multiple payment threads may update this concurrently.
    private final Map<String, List<Long>> customerVelocity = new ConcurrentHashMap<>();

    public RuleBasedFraudCheck(Set<String> blacklistedMerchants) {
        this.blacklistedMerchants = blacklistedMerchants;
    }

    @Override
    public FraudResult checkFraud(Payment payment) {
        List<String> reasons = new ArrayList<>();
        double riskScore = 0.0;

        // ── Rule 1: High amount check ──
        if (payment.getAmount() > HIGH_AMOUNT_THRESHOLD) {
            reasons.add("Amount " + payment.getCurrency().format(payment.getAmount())
                        + " exceeds threshold of "
                        + payment.getCurrency().format(HIGH_AMOUNT_THRESHOLD));
            riskScore += 0.4;
        }

        // ── Rule 2: Velocity check — too many txns from same customer in 1 minute ──
        if (payment.getCustomerId() != null) {
            long now = System.currentTimeMillis();
            long oneMinuteAgo = now - 60_000;

            // Record this transaction and clean old entries
            customerVelocity.computeIfAbsent(payment.getCustomerId(), k -> new ArrayList<>());
            List<Long> timestamps = customerVelocity.get(payment.getCustomerId());
            synchronized (timestamps) {
                // Remove timestamps older than 1 minute
                timestamps.removeIf(ts -> ts < oneMinuteAgo);
                timestamps.add(now);

                if (timestamps.size() > MAX_TRANSACTIONS_PER_MINUTE) {
                    reasons.add("Velocity check failed: " + timestamps.size()
                                + " transactions in last minute (limit: "
                                + MAX_TRANSACTIONS_PER_MINUTE + ")");
                    riskScore += 0.5;
                }
            }
        }

        // ── Rule 3: Blacklisted merchant ──
        if (blacklistedMerchants.contains(payment.getMerchantId())) {
            reasons.add("Merchant " + payment.getMerchantId() + " is blacklisted");
            riskScore += 0.8;
        }

        // Cap risk score at 1.0
        riskScore = Math.min(riskScore, 1.0);

        if (reasons.isEmpty()) {
            return FraudResult.pass(riskScore);
        } else {
            return FraudResult.fail(riskScore, reasons);
        }
    }

    @Override
    public String getName() {
        return "RuleBasedFraudCheck";
    }
}
