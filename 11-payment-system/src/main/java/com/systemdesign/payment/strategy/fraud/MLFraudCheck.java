package com.systemdesign.payment.strategy.fraud;

import com.systemdesign.payment.model.FraudResult;
import com.systemdesign.payment.model.Payment;
import com.systemdesign.payment.model.PaymentMethod;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * MLFraudCheck — Simulated machine learning fraud detection.
 *
 * IN A REAL SYSTEM:
 *   - Feature engineering: extract features from the payment and customer history
 *     (amount, method, time of day, customer age, device fingerprint, IP geolocation,
 *      historical chargeback rate, card BIN risk score, etc.)
 *   - Model: gradient boosted trees (XGBoost/LightGBM) or neural network
 *     trained on labeled fraud/not-fraud data
 *   - Inference: model returns a probability [0.0, 1.0] that this payment is fraud
 *   - Threshold: if P(fraud) > 0.7, block; if 0.4-0.7, require 3DS/OTP
 *
 * HERE (SIMULATED):
 *   We compute a deterministic risk score based on observable features:
 *   - Amount: higher amounts → higher risk (scaled logarithmically)
 *   - Payment method: some methods are riskier (credit cards > wallets)
 *   - Time of day: late night (midnight-5am) → slightly higher risk
 *
 *   This gives us a score that behaves somewhat like a real ML model
 *   without needing an actual trained model.
 *
 * THRESHOLD: 0.7
 *   Score >= 0.7 → BLOCK the payment
 *   Score <  0.7 → PASS (but the score is logged for monitoring)
 */
public class MLFraudCheck implements FraudCheckStrategy {

    private static final double BLOCK_THRESHOLD = 0.7;

    @Override
    public FraudResult checkFraud(Payment payment) {
        List<String> reasons = new ArrayList<>();

        // ── Feature 1: Amount risk (logarithmic scaling) ──
        // $1 → 0.0, $100 → 0.1, $1000 → 0.15, $10000 → 0.2, $50000 → 0.25
        double amountRisk = Math.min(0.3, Math.log10(Math.max(1, payment.getAmount())) / 20.0);

        // ── Feature 2: Payment method risk ──
        // Credit cards have highest fraud rate (card-not-present fraud)
        // Wallets have lowest (pre-authenticated, device-bound)
        double methodRisk = switch (payment.getMethod()) {
            case CREDIT_CARD    -> 0.15;
            case DEBIT_CARD     -> 0.10;
            case BANK_TRANSFER  -> 0.08;
            case UPI            -> 0.05;
            case WALLET         -> 0.02;
        };

        // ── Feature 3: Time-of-day risk ──
        // Fraud is more common between midnight and 5 AM
        // (in a real system we'd use the customer's local time)
        int hour = LocalDateTime.now().getHour();
        double timeRisk = (hour >= 0 && hour < 5) ? 0.15 : 0.0;

        // ── Feature 4: Amount anomaly ──
        // Amounts that are round numbers (e.g. exactly $5000) are slightly more suspicious
        // Real ML models detect this pattern in training data
        double roundNumberRisk = (payment.getAmount() % 1000 == 0 && payment.getAmount() >= 5000)
                                 ? 0.1 : 0.0;

        // ── Combine features (in real ML, this would be the model's prediction) ──
        // Simple weighted sum — a real model would use learned weights
        double riskScore = amountRisk + methodRisk + timeRisk + roundNumberRisk;

        // Add some deterministic "noise" based on amount to simulate model variance
        // Using payment amount's hash to make it deterministic but varied
        double noise = (Math.abs(Double.hashCode(payment.getAmount())) % 100) / 1000.0;
        riskScore += noise;

        // Cap at 1.0
        riskScore = Math.min(1.0, riskScore);

        // ── Decision ──
        if (riskScore >= BLOCK_THRESHOLD) {
            reasons.add("ML risk score " + String.format("%.2f", riskScore)
                        + " exceeds threshold " + BLOCK_THRESHOLD);
            if (amountRisk > 0.2) reasons.add("High amount contributes risk: " + String.format("%.2f", amountRisk));
            if (methodRisk > 0.1) reasons.add("Payment method risk: " + String.format("%.2f", methodRisk));
            if (timeRisk > 0)     reasons.add("Late-night transaction risk: " + String.format("%.2f", timeRisk));
            if (roundNumberRisk > 0) reasons.add("Suspicious round amount: " + payment.getAmount());
            return FraudResult.fail(riskScore, reasons);
        }

        return FraudResult.pass(riskScore);
    }

    @Override
    public String getName() {
        return "MLFraudCheck";
    }
}
