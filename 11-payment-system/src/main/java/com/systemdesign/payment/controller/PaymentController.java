package com.systemdesign.payment.controller;

import com.systemdesign.payment.exception.DuplicatePaymentException;
import com.systemdesign.payment.exception.FraudDetectedException;
import com.systemdesign.payment.exception.PaymentException;
import com.systemdesign.payment.model.*;
import com.systemdesign.payment.service.*;

import java.util.List;
import java.util.Optional;

/**
 * PaymentController — Simulated REST controller.
 *
 * In a real system this would be a Spring @RestController or a
 * plain HTTP server handler.  Here we simulate the REST layer
 * with plain method calls that print HTTP-like request/response.
 *
 * ENDPOINTS (simulated):
 *   POST   /payments              → handleProcessPayment
 *   GET    /payments/{id}         → handleGetPayment
 *   POST   /payments/{id}/refund  → handleRefund
 *   GET    /accounts/{id}/balance → handleGetBalance
 *   POST   /reconciliation        → handleReconciliation
 *   POST   /webhooks/retry        → handleRetryWebhooks
 *
 * CALL CHAIN:
 *   PaymentController → PaymentService / RefundService / LedgerService
 *   (Controller delegates all business logic to services)
 */
public class PaymentController {

    private final PaymentService paymentService;
    private final RefundService refundService;
    private final LedgerService ledgerService;
    private final ReconciliationService reconciliationService;
    private final WebhookService webhookService;

    public PaymentController(PaymentService paymentService,
                             RefundService refundService,
                             LedgerService ledgerService,
                             ReconciliationService reconciliationService,
                             WebhookService webhookService) {
        this.paymentService = paymentService;
        this.refundService = refundService;
        this.ledgerService = ledgerService;
        this.reconciliationService = reconciliationService;
        this.webhookService = webhookService;
    }

    /**
     * POST /payments — Process a new payment.
     * Simulates: HTTP 200 on success, 409 on duplicate, 403 on fraud, 500 on error.
     */
    public Payment handleProcessPayment(String merchantId, double amount, Currency currency,
                                        PaymentMethod method, String idempotencyKey,
                                        String customerId) {
        System.out.println("  >>> POST /payments");
        try {
            Payment payment = paymentService.processPayment(
                merchantId, amount, currency, method, idempotencyKey, customerId);
            System.out.println("  <<< HTTP 200 OK — " + payment.getPaymentId());
            return payment;
        } catch (DuplicatePaymentException e) {
            System.out.println("  <<< HTTP 409 Conflict — " + e.getMessage());
            // Return the cached payment
            return paymentService.getPayment(e.getExistingPaymentId()).orElse(null);
        } catch (FraudDetectedException e) {
            System.out.println("  <<< HTTP 403 Forbidden — " + e.getMessage());
            return null;
        } catch (PaymentException e) {
            System.out.println("  <<< HTTP 500 Error — " + e.getMessage());
            return null;
        }
    }

    /**
     * GET /payments/{id} — Look up a payment.
     */
    public Payment handleGetPayment(String paymentId) {
        System.out.println("  >>> GET /payments/" + paymentId);
        Optional<Payment> payment = paymentService.getPayment(paymentId);
        if (payment.isPresent()) {
            System.out.println("  <<< HTTP 200 OK — " + payment.get());
            return payment.get();
        } else {
            System.out.println("  <<< HTTP 404 Not Found");
            return null;
        }
    }

    /**
     * POST /payments/{id}/refund — Refund a payment.
     */
    public Refund handleRefund(String paymentId, double amount, String reason) {
        System.out.println("  >>> POST /payments/" + paymentId + "/refund");
        try {
            Refund refund = refundService.processRefund(paymentId, amount, reason);
            System.out.println("  <<< HTTP 200 OK — " + refund);
            return refund;
        } catch (PaymentException e) {
            System.out.println("  <<< HTTP 400 Bad Request — " + e.getMessage());
            return null;
        }
    }

    /**
     * GET /accounts/{id}/balance — Get account balance from ledger.
     */
    public double handleGetBalance(String accountId) {
        System.out.println("  >>> GET /accounts/" + accountId + "/balance");
        double balance = ledgerService.getBalance(accountId);
        System.out.println("  <<< HTTP 200 OK — Balance: " + balance);
        return balance;
    }

    /**
     * POST /reconciliation — Run reconciliation.
     */
    public ReconciliationService.ReconciliationReport handleReconciliation(
            List<Transaction> internal,
            List<ReconciliationService.ExternalStatement> external) {
        System.out.println("  >>> POST /reconciliation");
        ReconciliationService.ReconciliationReport report = reconciliationService.reconcile(internal, external);
        System.out.println("  <<< HTTP 200 OK — " + report);
        return report;
    }

    /**
     * POST /webhooks/retry — Retry failed webhooks.
     */
    public int handleRetryWebhooks() {
        System.out.println("  >>> POST /webhooks/retry");
        int delivered = webhookService.deliverPendingWebhooks();
        System.out.println("  <<< HTTP 200 OK — Delivered: " + delivered);
        return delivered;
    }
}
