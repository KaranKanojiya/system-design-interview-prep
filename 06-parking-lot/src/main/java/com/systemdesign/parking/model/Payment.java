package com.systemdesign.parking.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Value object representing a completed (or failed) payment transaction.
 */
public class Payment {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String paymentId;
    private final String ticketId;
    private final double amount;
    private final PaymentMethod method;
    private final PaymentStatus status;
    private final LocalDateTime timestamp;

    public Payment(String ticketId, double amount, PaymentMethod method, PaymentStatus status) {
        this.paymentId = "PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        this.ticketId = ticketId;
        this.amount = amount;
        this.method = method;
        this.status = status;
        this.timestamp = LocalDateTime.now();
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getTicketId() {
        return ticketId;
    }

    public double getAmount() {
        return amount;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public boolean isSuccessful() {
        return status == PaymentStatus.COMPLETED;
    }

    @Override
    public String toString() {
        return String.format("Payment{id=%s, ticket=%s, amount=$%.2f, method=%s, status=%s, time=%s}",
                paymentId, ticketId, amount, method.getDisplayName(), status, timestamp.format(TIME_FMT));
    }
}
