package com.systemdesign.payment.service;

import com.systemdesign.payment.exception.DuplicatePaymentException;
import com.systemdesign.payment.exception.FraudDetectedException;
import com.systemdesign.payment.exception.PaymentException;
import com.systemdesign.payment.model.*;
import com.systemdesign.payment.repository.MerchantRepository;
import com.systemdesign.payment.repository.PaymentRepository;
import com.systemdesign.payment.strategy.processor.PaymentProcessor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PaymentService — FACADE: The main entry point for all payment operations.
 *
 * ═══════════════════════════════════════════════════════════
 *  PAYMENT PROCESSING FLOW (the "happy path")
 * ═══════════════════════════════════════════════════════════
 *
 *  Client Request
 *       │
 *       ▼
 *  ┌─ Step 1: Idempotency Check ─────────────────────────┐
 *  │  Has this idempotency key been used before?          │
 *  │  YES → return cached result (prevent double charge)  │
 *  │  NO  → continue                                      │
 *  └──────────────────────────────────────────────────────┘
 *       │
 *       ▼
 *  ┌─ Step 2: Fraud Check ───────────────────────────────┐
 *  │  Run all fraud strategies (rule-based + ML)          │
 *  │  FAIL → throw FraudDetectedException (block payment) │
 *  │  PASS → continue                                     │
 *  └──────────────────────────────────────────────────────┘
 *       │
 *       ▼
 *  ┌─ Step 3: Create Payment ────────────────────────────┐
 *  │  Build Payment object (status: INITIATED)            │
 *  │  Save to repository                                  │
 *  └──────────────────────────────────────────────────────┘
 *       │
 *       ▼
 *  ┌─ Step 4: Process via Processor ─────────────────────┐
 *  │  Select processor based on payment method            │
 *  │  (CreditCardProcessor, UPIProcessor, WalletProcessor)│
 *  │  Call processor.processPayment() → Transaction       │
 *  │  APPROVED → capture; DECLINED → fail                 │
 *  └──────────────────────────────────────────────────────┘
 *       │
 *       ▼
 *  ┌─ Step 5: Ledger Entries ────────────────────────────┐
 *  │  Double-entry bookkeeping:                           │
 *  │  DEBIT customer/platform, CREDIT merchant            │
 *  └──────────────────────────────────────────────────────┘
 *       │
 *       ▼
 *  ┌─ Step 6: Record Idempotency ────────────────────────┐
 *  │  Store (key → paymentId, status) for future lookups  │
 *  └──────────────────────────────────────────────────────┘
 *       │
 *       ▼
 *  ┌─ Step 7: Dispatch Webhook ──────────────────────────┐
 *  │  Notify merchant: "payment.succeeded" or             │
 *  │  "payment.failed" via POST to their webhook URL      │
 *  └──────────────────────────────────────────────────────┘
 *       │
 *       ▼
 *  Return Payment
 *
 * ═══════════════════════════════════════════════════════════
 */
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final MerchantRepository merchantRepository;
    private final IdempotencyService idempotencyService;
    private final FraudService fraudService;
    private final LedgerService ledgerService;
    private final WebhookService webhookService;
    private final Map<PaymentMethod, PaymentProcessor> processors;

    public PaymentService(PaymentRepository paymentRepository,
                          MerchantRepository merchantRepository,
                          IdempotencyService idempotencyService,
                          FraudService fraudService,
                          LedgerService ledgerService,
                          WebhookService webhookService,
                          Map<PaymentMethod, PaymentProcessor> processors) {
        this.paymentRepository = paymentRepository;
        this.merchantRepository = merchantRepository;
        this.idempotencyService = idempotencyService;
        this.fraudService = fraudService;
        this.ledgerService = ledgerService;
        this.webhookService = webhookService;
        this.processors = processors;
    }

    /**
     * Process a new payment — THE MAIN METHOD.
     *
     * @param merchantId     the merchant receiving payment
     * @param amount         payment amount
     * @param currency       payment currency
     * @param method         payment method (credit card, UPI, wallet, etc.)
     * @param idempotencyKey client-generated unique key for duplicate prevention
     * @param customerId     the customer making the payment (nullable)
     * @return the processed Payment object
     */
    public Payment processPayment(String merchantId, double amount, Currency currency,
                                  PaymentMethod method, String idempotencyKey,
                                  String customerId) {
        System.out.println("\n  [PaymentService] Processing payment:");
        System.out.println("    Merchant: " + merchantId);
        System.out.println("    Amount: " + currency.format(amount));
        System.out.println("    Method: " + method);
        System.out.println("    Idempotency Key: " + idempotencyKey);

        // ══════════════════════════════════════════════
        //  STEP 1: IDEMPOTENCY CHECK
        //  Prevents double-charging on client retries.
        //  If the key exists, return the cached result.
        // ══════════════════════════════════════════════
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<IdempotencyRecord> cached = idempotencyService.checkAndGet(idempotencyKey);
            if (cached.isPresent()) {
                IdempotencyRecord record = cached.get();
                System.out.println("    [Step 1] IDEMPOTENCY HIT — returning cached result");
                System.out.println("      Cached payment: " + record.getPaymentId());
                System.out.println("      Cached status: " + record.getResponseStatus());

                // Return the cached payment instead of processing again
                Payment cachedPayment = paymentRepository.findById(record.getPaymentId())
                    .orElseThrow(() -> new PaymentException(
                        "Cached payment not found: " + record.getPaymentId()));
                throw new DuplicatePaymentException(idempotencyKey, record.getPaymentId());
            }
            System.out.println("    [Step 1] Idempotency key is new — proceeding");
        }

        // ══════════════════════════════════════════════
        //  STEP 2: CREATE PAYMENT (before fraud check
        //  so we have a Payment object for fraud analysis)
        // ══════════════════════════════════════════════
        Payment payment = new Payment.Builder()
            .merchantId(merchantId)
            .customerId(customerId)
            .amount(amount)
            .currency(currency)
            .method(method)
            .idempotencyKey(idempotencyKey)
            .description("Payment to " + merchantId)
            .build();

        // ══════════════════════════════════════════════
        //  STEP 3: FRAUD CHECK
        //  Run all fraud detection strategies.
        //  If any flags the payment, throw FraudDetectedException.
        // ══════════════════════════════════════════════
        System.out.println("    [Step 2] Running fraud checks...");
        FraudResult fraudResult = fraudService.checkPayment(payment);

        if (!fraudResult.isPassed()) {
            payment.fail();
            paymentRepository.save(payment);
            // Record in idempotency so retries also get rejected
            if (idempotencyKey != null) {
                idempotencyService.record(idempotencyKey, payment.getPaymentId(),
                    payment.getStatus(), "Blocked by fraud detection");
            }
            System.out.println("    [Step 2] FRAUD DETECTED — payment blocked");
            throw new FraudDetectedException(fraudResult);
        }
        System.out.println("    [Step 2] Fraud check passed (risk score: "
                           + String.format("%.2f", fraudResult.getRiskScore()) + ")");

        // ══════════════════════════════════════════════
        //  STEP 4: PROCESS VIA PAYMENT PROCESSOR
        //  Select the right processor for this payment method
        //  and send the payment for processing.
        // ══════════════════════════════════════════════
        PaymentProcessor processor = processors.get(method);
        if (processor == null) {
            throw new PaymentException("No processor configured for method: " + method);
        }

        System.out.println("    [Step 3] Processing via " + processor.getName() + "...");
        payment.startProcessing();

        Transaction txn = processor.processPayment(payment);
        payment.setProcessorTransactionId(txn.getProcessorTransactionId());

        System.out.println("    [Step 3] Processor response: " + txn.getStatus()
                           + " — " + txn.getResponseMessage());

        // ══════════════════════════════════════════════
        //  STEP 5: UPDATE PAYMENT STATUS BASED ON RESULT
        // ══════════════════════════════════════════════
        if ("APPROVED".equals(txn.getStatus())) {
            // Capture the payment (for card: auth+capture; for UPI/wallet: instant)
            payment.capture();
            System.out.println("    [Step 4] Payment CAPTURED: " + payment.getPaymentId());

            // ══════════════════════════════════════════════
            //  STEP 6: LEDGER ENTRIES (double-entry bookkeeping)
            // ══════════════════════════════════════════════
            System.out.println("    [Step 5] Recording ledger entries...");
            ledgerService.recordPayment(payment);

        } else {
            // Payment declined or errored
            payment.fail();
            System.out.println("    [Step 4] Payment FAILED: " + txn.getResponseMessage());
        }

        // ══════════════════════════════════════════════
        //  STEP 7: SAVE PAYMENT
        // ══════════════════════════════════════════════
        paymentRepository.save(payment);

        // ══════════════════════════════════════════════
        //  STEP 8: RECORD IDEMPOTENCY
        // ══════════════════════════════════════════════
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyService.record(idempotencyKey, payment.getPaymentId(),
                payment.getStatus(), payment.toString());
            System.out.println("    [Step 6] Idempotency recorded for key: " + idempotencyKey);
        }

        // ══════════════════════════════════════════════
        //  STEP 9: DISPATCH WEBHOOK
        // ══════════════════════════════════════════════
        String eventType = (payment.getStatus() == PaymentStatus.CAPTURED
                            || payment.getStatus() == PaymentStatus.SETTLED)
                           ? "payment.succeeded" : "payment.failed";

        System.out.println("    [Step 7] Dispatching webhook: " + eventType);
        webhookService.dispatchWebhook(eventType, payment);

        System.out.println("    [PaymentService] Payment complete: " + payment);
        return payment;
    }

    /**
     * Look up a payment by ID.
     */
    public Optional<Payment> getPayment(String paymentId) {
        return paymentRepository.findById(paymentId);
    }

    /**
     * Get all payments for a merchant.
     */
    public List<Payment> getPaymentsByMerchant(String merchantId) {
        return paymentRepository.findByMerchantId(merchantId);
    }

    /**
     * Get all payments.
     */
    public List<Payment> getAllPayments() {
        return paymentRepository.findAll();
    }
}
