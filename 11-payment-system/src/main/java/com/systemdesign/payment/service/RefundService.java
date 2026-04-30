package com.systemdesign.payment.service;

import com.systemdesign.payment.exception.PaymentException;
import com.systemdesign.payment.model.*;
import com.systemdesign.payment.repository.PaymentRepository;
import com.systemdesign.payment.strategy.processor.PaymentProcessor;

import java.util.Map;

/**
 * RefundService — Handles full and partial refund processing.
 *
 * REFUND FLOW:
 *   1. Validate: payment must be CAPTURED or SETTLED, refund amount <= original
 *   2. Create Refund object (PENDING)
 *   3. Select processor (same one that processed the original payment)
 *   4. Process refund through processor → Transaction
 *   5. If processor approves:
 *      a. Update Refund status → COMPLETED
 *      b. Create reverse ledger entries (debit merchant, credit customer)
 *      c. Update Payment status → REFUNDED
 *      d. Dispatch "refund.completed" webhook
 *   6. If processor declines:
 *      a. Update Refund status → FAILED
 *      b. Dispatch "refund.failed" webhook
 *
 * PARTIAL REFUNDS:
 *   A $100 payment can be partially refunded for $30.  The merchant keeps $70.
 *   The ledger entries reflect the partial amount.
 *   Note: in this simplified version, we mark the payment as REFUNDED even for
 *   partial refunds.  A production system would track remaining refundable amount.
 *
 * CALL CHAIN:
 *   PaymentController.handleRefund()
 *     → RefundService.processRefund(paymentId, amount, reason)
 *       → validate payment state and amount
 *       → processor.processRefund(refund, payment) → Transaction
 *       → LedgerService.recordRefund(refund, payment) → reverse entries
 *       → WebhookService.dispatchWebhook("refund.completed", payment)
 */
public class RefundService {

    private final PaymentRepository paymentRepository;
    private final Map<PaymentMethod, PaymentProcessor> processors;
    private final LedgerService ledgerService;
    private final WebhookService webhookService;

    public RefundService(PaymentRepository paymentRepository,
                         Map<PaymentMethod, PaymentProcessor> processors,
                         LedgerService ledgerService,
                         WebhookService webhookService) {
        this.paymentRepository = paymentRepository;
        this.processors = processors;
        this.ledgerService = ledgerService;
        this.webhookService = webhookService;
    }

    /**
     * Process a refund for a payment.
     *
     * @param paymentId the payment to refund
     * @param amount    the refund amount (must be <= original payment amount)
     * @param reason    human-readable reason for the refund
     * @return the completed Refund object
     */
    public Refund processRefund(String paymentId, double amount, String reason) {
        System.out.println("\n  [RefundService] Processing refund for payment " + paymentId);
        System.out.println("    Amount: " + amount + ", Reason: " + reason);

        // ── Step 1: Validate ──
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new PaymentException("Payment not found: " + paymentId));

        // Payment must be CAPTURED or SETTLED to be refunded
        if (payment.getStatus() != PaymentStatus.CAPTURED
            && payment.getStatus() != PaymentStatus.SETTLED) {
            throw new PaymentException(
                "Cannot refund payment in state " + payment.getStatus()
                + ". Payment must be CAPTURED or SETTLED.");
        }

        // Refund amount must not exceed original payment
        if (amount > payment.getAmount()) {
            throw new PaymentException(
                "Refund amount " + amount + " exceeds payment amount " + payment.getAmount());
        }

        if (amount <= 0) {
            throw new PaymentException("Refund amount must be positive, got: " + amount);
        }

        // ── Step 2: Create Refund ──
        Refund refund = new Refund.Builder()
            .paymentId(paymentId)
            .amount(amount)
            .reason(reason)
            .build();

        System.out.println("    Created refund: " + refund.getRefundId());

        // ── Step 3: Select processor ──
        PaymentProcessor processor = processors.get(payment.getMethod());
        if (processor == null) {
            throw new PaymentException("No processor for method: " + payment.getMethod());
        }

        // ── Step 4: Process through processor ──
        refund.startProcessing();
        Transaction txn = processor.processRefund(refund, payment);

        System.out.println("    Processor response: " + txn.getStatus() + " — " + txn.getResponseMessage());

        // ── Step 5/6: Handle result ──
        if ("APPROVED".equals(txn.getStatus())) {
            refund.complete();

            // Reverse ledger entries
            ledgerService.recordRefund(refund, payment);

            // Update payment status
            payment.refund();
            paymentRepository.save(payment);

            // Dispatch webhook
            webhookService.dispatchWebhook("refund.completed", payment);

            System.out.println("    Refund " + refund.getRefundId() + " COMPLETED");
        } else {
            refund.fail();
            webhookService.dispatchWebhook("refund.failed", payment);
            System.out.println("    Refund " + refund.getRefundId() + " FAILED: " + txn.getResponseMessage());
        }

        return refund;
    }
}
