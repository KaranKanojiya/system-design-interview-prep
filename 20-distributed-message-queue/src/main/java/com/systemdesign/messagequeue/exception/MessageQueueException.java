package com.systemdesign.messagequeue.exception;

/**
 * Base exception for all Distributed Message Queue errors.
 *
 * Hierarchy:
 *   MessageQueueException (this)
 *     ├── TopicNotFoundException       — topic does not exist in the registry
 *     ├── PartitionNotFoundException   — partition does not exist for a topic
 *     └── ConsumerGroupException       — consumer group coordination error
 */
public class MessageQueueException extends RuntimeException {

    public MessageQueueException(String message) {
        super(message);
    }

    public MessageQueueException(String message, Throwable cause) {
        super(message, cause);
    }
}
