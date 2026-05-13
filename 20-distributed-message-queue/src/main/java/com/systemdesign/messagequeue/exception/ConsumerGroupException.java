package com.systemdesign.messagequeue.exception;

/**
 * Thrown when a consumer group coordination error occurs.
 *
 * Flow: ConsumerGroupCoordinator.rebalance() / ConsumerService.subscribe()
 *       → validation failure → ConsumerGroupException
 */
public class ConsumerGroupException extends MessageQueueException {

    private final String groupId; // the affected consumer group

    public ConsumerGroupException(String groupId, String message) {
        super("Consumer group error: " + groupId + ": " + message);
        this.groupId = groupId;
    }

    public String getGroupId() {
        return groupId;
    }

    @Override
    public String getMessage() {
        return super.getMessage();
    }
}
