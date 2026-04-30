package com.systemdesign.payment.model;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Transaction — A record of one interaction with an external payment processor.
 *
 * A single Payment may generate multiple Transactions:
 *   - Authorization transaction (processor holds funds)
 *   - Capture transaction (processor moves funds)
 *   - Refund transaction (processor returns funds)
 *
 * WHY separate from Payment?
 *   The Payment is our internal domain object.  The Transaction is the
 *   processor's view.  During reconciliation we match our Transactions
 *   against the processor's settlement file to find discrepancies.
 *
 * processorTransactionId — the ID the processor returns (e.g. "TXN-CC-a1b2c3").
 * responseCode/responseMessage — raw processor response for debugging.
 */
public class Transaction {

    private final String transactionId;
    private final String paymentId;
    private final String processorName;
    private final String processorTransactionId;
    private final double amount;
    private final String status;          // "APPROVED", "DECLINED", "ERROR"
    private final String responseCode;    // e.g. "00" for approved, "51" for insufficient funds
    private final String responseMessage; // human-readable
    private final LocalDateTime createdAt;

    public Transaction(String paymentId, String processorName, String processorTransactionId,
                       double amount, String status, String responseCode,
                       String responseMessage) {
        this.transactionId = "TXN-" + UUID.randomUUID().toString().substring(0, 8);
        this.paymentId = paymentId;
        this.processorName = processorName;
        this.processorTransactionId = processorTransactionId;
        this.amount = amount;
        this.status = status;
        this.responseCode = responseCode;
        this.responseMessage = responseMessage;
        this.createdAt = LocalDateTime.now();
    }

    // ── Getters ──
    public String getTransactionId() { return transactionId; }
    public String getPaymentId() { return paymentId; }
    public String getProcessorName() { return processorName; }
    public String getProcessorTransactionId() { return processorTransactionId; }
    public double getAmount() { return amount; }
    public String getStatus() { return status; }
    public String getResponseCode() { return responseCode; }
    public String getResponseMessage() { return responseMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "Transaction{" +
                "id='" + transactionId + '\'' +
                ", paymentId='" + paymentId + '\'' +
                ", processor='" + processorName + '\'' +
                ", processorTxnId='" + processorTransactionId + '\'' +
                ", amount=" + amount +
                ", status='" + status + '\'' +
                ", code='" + responseCode + '\'' +
                ", message='" + responseMessage + '\'' +
                '}';
    }
}
