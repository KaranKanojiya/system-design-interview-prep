package com.systemdesign.payment.display;

import com.systemdesign.payment.model.*;
import com.systemdesign.payment.repository.PaymentRepository;
import com.systemdesign.payment.service.LedgerService;
import com.systemdesign.payment.service.WebhookService;

import java.util.List;

/**
 * PaymentStatsDisplay — Displays aggregate statistics about the payment system.
 *
 * Shows:
 *   - Total payments and success rate
 *   - Total payment volume (sum of amounts)
 *   - Average payment amount
 *   - Refund rate
 *   - Webhook delivery rate
 *   - Ledger balance verification (sum of all entries should be 0)
 */
public class PaymentStatsDisplay {

    private final PaymentRepository paymentRepository;
    private final LedgerService ledgerService;
    private final WebhookService webhookService;

    public PaymentStatsDisplay(PaymentRepository paymentRepository,
                               LedgerService ledgerService,
                               WebhookService webhookService) {
        this.paymentRepository = paymentRepository;
        this.ledgerService = ledgerService;
        this.webhookService = webhookService;
    }

    /**
     * Display comprehensive payment system statistics.
     */
    public void displayStats() {
        String separator = "=".repeat(70);
        System.out.println("\n" + separator);
        System.out.println("  PAYMENT SYSTEM STATISTICS");
        System.out.println(separator);

        List<Payment> allPayments = paymentRepository.findAll();
        List<WebhookEvent> allWebhooks = webhookService.getAllEvents();

        // ── Payment Statistics ──
        long totalPayments = allPayments.size();
        long successfulPayments = allPayments.stream()
            .filter(p -> p.getStatus() == PaymentStatus.CAPTURED
                      || p.getStatus() == PaymentStatus.SETTLED
                      || p.getStatus() == PaymentStatus.REFUNDED)
            .count();
        long failedPayments = allPayments.stream()
            .filter(p -> p.getStatus() == PaymentStatus.FAILED)
            .count();
        long refundedPayments = allPayments.stream()
            .filter(p -> p.getStatus() == PaymentStatus.REFUNDED)
            .count();

        double successRate = totalPayments > 0
            ? (successfulPayments * 100.0 / totalPayments) : 0;

        double totalVolume = allPayments.stream()
            .filter(p -> p.getStatus() == PaymentStatus.CAPTURED
                      || p.getStatus() == PaymentStatus.SETTLED
                      || p.getStatus() == PaymentStatus.REFUNDED)
            .mapToDouble(Payment::getAmount)
            .sum();

        double avgPayment = successfulPayments > 0
            ? totalVolume / successfulPayments : 0;

        double refundRate = totalPayments > 0
            ? (refundedPayments * 100.0 / totalPayments) : 0;

        System.out.println("\n  Payments:");
        System.out.printf("    Total Payments:      %d%n", totalPayments);
        System.out.printf("    Successful:          %d%n", successfulPayments);
        System.out.printf("    Failed:              %d%n", failedPayments);
        System.out.printf("    Refunded:            %d%n", refundedPayments);
        System.out.printf("    Success Rate:        %.1f%%%n", successRate);
        System.out.printf("    Total Volume:        $%.2f%n", totalVolume);
        System.out.printf("    Average Payment:     $%.2f%n", avgPayment);
        System.out.printf("    Refund Rate:         %.1f%%%n", refundRate);

        // ── Webhook Statistics ──
        long totalWebhooks = allWebhooks.size();
        long deliveredWebhooks = allWebhooks.stream()
            .filter(w -> w.getStatus() == WebhookStatus.DELIVERED)
            .count();
        long failedWebhooks = allWebhooks.stream()
            .filter(w -> w.getStatus() == WebhookStatus.FAILED
                      || w.getStatus() == WebhookStatus.EXHAUSTED)
            .count();

        double webhookDeliveryRate = totalWebhooks > 0
            ? (deliveredWebhooks * 100.0 / totalWebhooks) : 0;

        System.out.println("\n  Webhooks:");
        System.out.printf("    Total Events:        %d%n", totalWebhooks);
        System.out.printf("    Delivered:           %d%n", deliveredWebhooks);
        System.out.printf("    Failed/Exhausted:    %d%n", failedWebhooks);
        System.out.printf("    Delivery Rate:       %.1f%%%n", webhookDeliveryRate);

        // ── Ledger Balance Check ──
        System.out.println("\n  Ledger Integrity:");
        double ledgerSum = ledgerService.verifyBalance();
        System.out.printf("    Sum of All Entries:  %.4f%n", ledgerSum);
        System.out.printf("    Balanced:            %s%n",
            Math.abs(ledgerSum) < 0.01 ? "YES (within tolerance)" : "NO — IMBALANCE DETECTED!");

        // ── Total Ledger Entries ──
        int totalEntries = ledgerService.getAllEntries().size();
        System.out.printf("    Total Entries:       %d%n", totalEntries);

        System.out.println("\n" + separator);
    }
}
