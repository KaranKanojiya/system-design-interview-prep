package com.systemdesign.ridesharing.service;

import com.systemdesign.ridesharing.exception.PaymentFailedException;
import com.systemdesign.ridesharing.model.Payment;
import com.systemdesign.ridesharing.model.Ride;

import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PaymentService — Processes ride payments with simulated success/failure.
 *
 * CALL CHAIN:
 *   RideService.completeRide()
 *     -> PricingService.calculateActualFare(ride)  // get the fare
 *     -> PaymentService.processPayment(ride)        // charge the rider
 *       -> Create Payment(rideId, amount, method)
 *       -> Simulate payment processing (90% success rate)
 *       -> On success: mark Payment as COMPLETED
 *       -> On failure: mark Payment as FAILED, throw PaymentFailedException
 *
 * WHY 90% success rate simulation:
 *   In production, ~3-5% of payments fail (expired cards, insufficient funds,
 *   fraud detection). Showing this in a demo proves awareness of real-world
 *   error handling. The system should handle failures gracefully:
 *   - Retry with exponential backoff
 *   - Try alternative payment method
 *   - Add to rider's outstanding balance
 *
 * In production Uber:
 *   Payments are async. The ride completes, an event is published, and the
 *   Payment service picks it up. If it fails, it retries. If it ultimately
 *   fails, the rider is notified and blocked from new rides until they pay.
 */
public class PaymentService {

    private final Map<String, Payment> payments = new ConcurrentHashMap<>();
    private final Random random = new Random();

    /** Payment success rate — 90% of payments succeed. */
    private static final double SUCCESS_RATE = 0.90;

    /**
     * Process payment for a completed ride.
     *
     * @param ride the completed ride (must have actualFare > 0)
     * @return the Payment record
     * @throws PaymentFailedException if payment processing fails
     */
    public Payment processPayment(Ride ride) {
        if (ride.getActualFare() <= 0) {
            throw new PaymentFailedException("N/A",
                    "Ride " + ride.getRideId() + " has no fare to charge");
        }

        // Create payment record
        Payment payment = new Payment(
                ride.getRideId(),
                ride.getActualFare(),
                ride.getRider().getPaymentMethod()
        );

        System.out.printf("  [Payment] Processing $%.2f via %s for ride %s...%n",
                payment.getAmount(), payment.getMethod(), ride.getRideId());

        // Simulate payment processing
        // In production: call Stripe/Braintree API, handle 3DS, fraud check
        boolean success = random.nextDouble() < SUCCESS_RATE;

        if (success) {
            payment.markCompleted();
            System.out.printf("  [Payment] SUCCESS - %s%n", payment);
        } else {
            payment.markFailed();
            System.out.printf("  [Payment] FAILED - %s%n", payment);
            payments.put(payment.getPaymentId(), payment);
            throw new PaymentFailedException(payment.getPaymentId(),
                    "Payment processing failed (simulated - 10% failure rate)");
        }

        payments.put(payment.getPaymentId(), payment);
        return payment;
    }

    /**
     * Refund a completed payment.
     *
     * @param paymentId the payment to refund
     * @return the updated Payment record
     */
    public Payment refundPayment(String paymentId) {
        Payment payment = payments.get(paymentId);
        if (payment == null) {
            throw new PaymentFailedException(paymentId, "Payment not found");
        }

        if (payment.getStatus() != Payment.PaymentStatus.COMPLETED) {
            throw new PaymentFailedException(paymentId,
                    "Can only refund COMPLETED payments, current status: " + payment.getStatus());
        }

        payment.markRefunded();
        System.out.printf("  [Payment] REFUNDED - %s%n", payment);
        return payment;
    }

    /** Get a payment by ID. */
    public Optional<Payment> getPayment(String paymentId) {
        return Optional.ofNullable(payments.get(paymentId));
    }

    /** Get all payments for debugging. */
    public Map<String, Payment> getAllPayments() {
        return Map.copyOf(payments);
    }
}
