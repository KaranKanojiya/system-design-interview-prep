package com.systemdesign.payment.strategy.processor;

import com.systemdesign.payment.model.Payment;
import com.systemdesign.payment.model.Refund;
import com.systemdesign.payment.model.Transaction;

import java.util.Random;
import java.util.UUID;

/**
 * WalletProcessor — Simulates a digital wallet (like PayPal, Google Pay balance, Paytm).
 *
 * WALLET CHARACTERISTICS:
 *   - 99% success rate (highest — money is already in the system)
 *   - Fastest processing (~30ms) — no external bank calls needed
 *   - Pre-funded: customer has already loaded money into the wallet
 *   - Settlement is instant (internal ledger transfer)
 *
 * WHY highest success rate?
 *   Unlike cards (where the issuing bank can decline) or UPI (where the
 *   customer's bank might be down), wallet payments are internal transfers
 *   within our own system.  The only failure mode is insufficient wallet
 *   balance, which we can check before calling the processor.
 */
public class WalletProcessor implements PaymentProcessor {

    private final Random random = new Random();

    @Override
    public Transaction processPayment(Payment payment) {
        // Wallet is the fastest — internal ledger transfer, no external calls
        simulateLatency(30);

        String processorTxnId = "TXN-WAL-" + UUID.randomUUID().toString().substring(0, 8);

        // 99% success rate — failure only on insufficient wallet balance
        int roll = random.nextInt(100);

        if (roll < 99) {
            return new Transaction(
                payment.getPaymentId(),
                getName(),
                processorTxnId,
                payment.getAmount(),
                "APPROVED",
                "00",
                "Wallet payment successful — instant settlement"
            );
        } else {
            // 1% failure — insufficient wallet balance
            return new Transaction(
                payment.getPaymentId(),
                getName(),
                processorTxnId,
                payment.getAmount(),
                "DECLINED",
                "W01",
                "Insufficient wallet balance"
            );
        }
    }

    @Override
    public Transaction processRefund(Refund refund, Payment payment) {
        simulateLatency(20);

        String processorTxnId = "TXN-WAL-REF-" + UUID.randomUUID().toString().substring(0, 8);

        // Wallet refunds are trivially easy — credit the wallet balance
        return new Transaction(
            payment.getPaymentId(),
            getName(),
            processorTxnId,
            refund.getAmount(),
            "APPROVED",
            "00",
            "Wallet refund processed — instant credit to wallet"
        );
    }

    @Override
    public String getName() {
        return "WalletProcessor";
    }

    private void simulateLatency(int baseMs) {
        try {
            int jitter = random.nextInt(Math.max(1, baseMs / 5)) - (baseMs / 10);
            Thread.sleep(Math.max(5, baseMs + jitter));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
