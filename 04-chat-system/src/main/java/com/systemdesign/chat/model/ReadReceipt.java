package com.systemdesign.chat.model;

import java.time.LocalDateTime;

/**
 * Captures the event of a user acknowledging receipt/read of a specific message.
 */
public class ReadReceipt {

    private final String messageId;
    private final String userId;
    private final MessageStatus status;
    private final LocalDateTime timestamp;

    public ReadReceipt(String messageId, String userId, MessageStatus status) {
        this.messageId = messageId;
        this.userId = userId;
        this.status = status;
        this.timestamp = LocalDateTime.now();
    }

    public String getMessageId() {
        return messageId;
    }

    public String getUserId() {
        return userId;
    }

    public MessageStatus getStatus() {
        return status;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return String.format("ReadReceipt{msg=%s, user=%s, status=%s, at=%s}",
                messageId, userId, status.getSymbol(), timestamp);
    }
}
