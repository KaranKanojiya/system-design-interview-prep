package com.systemdesign.messagequeue.strategy.delivery;

import com.systemdesign.messagequeue.model.DeliveryGuarantee;
import com.systemdesign.messagequeue.model.Message;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exactly-once delivery — uses consumer-side idempotency via a deduplication set.
 * If a message ID has already been delivered, the duplicate is silently skipped.
 * This simulates the idempotent consumer pattern used in Kafka's exactly-once semantics.
 */
// wiring: ConcurrentHashMap-backed set tracks delivered message IDs for dedup
public class ExactlyOnceDeliveryStrategy implements DeliveryStrategy {

    private final Set<String> deliveredIds = ConcurrentHashMap.newKeySet();

    @Override
    public boolean deliver(Message message, String consumerId) {
        String messageId = message.getId();

        if (deliveredIds.contains(messageId)) {
            System.out.println("[DELIVERY] EXACTLY_ONCE — duplicate detected for message "
                    + messageId + " to consumer " + consumerId + " (skipped, idempotent)");
            return true; // idempotent — treat as success
        }

        deliveredIds.add(messageId);
        System.out.println("[DELIVERY] EXACTLY_ONCE — delivered message "
                + messageId + " to consumer " + consumerId
                + " (dedup set size: " + deliveredIds.size() + ")");
        return true;
    }

    @Override
    public DeliveryGuarantee getGuarantee() {
        return DeliveryGuarantee.EXACTLY_ONCE;
    }

    @Override
    public String getStrategyName() {
        return "ExactlyOnceDelivery";
    }
}
