package com.systemdesign.messagequeue.strategy.delivery;

import com.systemdesign.messagequeue.model.DeliveryGuarantee;
import com.systemdesign.messagequeue.model.Message;

import java.util.concurrent.ThreadLocalRandom;

/**
 * At-least-once delivery — delivers the message and waits for an ack.
 * If no ack is received (simulated 5% failure chance per attempt), the message
 * is redelivered up to MAX_RETRIES times. Duplicates are possible on the consumer side.
 */
// wiring: simulate ack failure with 5% random chance; retry loop up to 3 attempts
public class AtLeastOnceDeliveryStrategy implements DeliveryStrategy {

    private static final int MAX_RETRIES = 3;
    private static final double FAILURE_RATE = 0.05;

    @Override
    public boolean deliver(Message message, String consumerId) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            boolean acked = ThreadLocalRandom.current().nextDouble() >= FAILURE_RATE;

            if (acked) {
                System.out.println("[DELIVERY] AT_LEAST_ONCE — delivered message "
                        + message.getId() + " to consumer " + consumerId
                        + " (attempt " + attempt + "/" + MAX_RETRIES + ", acked)");
                return true;
            }

            System.out.println("[DELIVERY] AT_LEAST_ONCE — no ack for message "
                    + message.getId() + " from consumer " + consumerId
                    + " (attempt " + attempt + "/" + MAX_RETRIES + ", retrying...)");
        }

        System.out.println("[DELIVERY] AT_LEAST_ONCE — FAILED to deliver message "
                + message.getId() + " to consumer " + consumerId
                + " after " + MAX_RETRIES + " attempts");
        return false;
    }

    @Override
    public DeliveryGuarantee getGuarantee() {
        return DeliveryGuarantee.AT_LEAST_ONCE;
    }

    @Override
    public String getStrategyName() {
        return "AtLeastOnceDelivery";
    }
}
