package com.systemdesign.messagequeue.strategy.delivery;

import com.systemdesign.messagequeue.model.DeliveryGuarantee;
import com.systemdesign.messagequeue.model.Message;

// Strategy Pattern (GoF) — determines delivery guarantee semantics
public interface DeliveryStrategy {

    /**
     * Delivers a message to the specified consumer.
     *
     * @param message    the message to deliver
     * @param consumerId the target consumer identifier
     * @return true if the message was successfully delivered (acked)
     */
    boolean deliver(Message message, String consumerId);

    /** Returns the delivery guarantee level this strategy implements. */
    DeliveryGuarantee getGuarantee();

    /** Returns a human-readable name for this strategy. */
    String getStrategyName();
}
