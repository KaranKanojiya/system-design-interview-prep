package com.systemdesign.chat.model;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable-ish message with per-recipient delivery tracking.
 * Uses a manual Builder pattern for flexible construction.
 */
public class Message implements Comparable<Message> {

    private final String messageId;
    private final String conversationId;
    private final String senderId;
    private final String content;
    private final MessageType type;
    private MessageStatus status;
    private final long sequenceNumber;
    private final LocalDateTime createdAt;
    private final Map<String, MessageStatus> deliveryStatus; // per-recipient tracking

    private Message(Builder builder) {
        this.messageId = builder.messageId;
        this.conversationId = builder.conversationId;
        this.senderId = builder.senderId;
        this.content = builder.content;
        this.type = builder.type;
        this.status = builder.status;
        this.sequenceNumber = builder.sequenceNumber;
        this.createdAt = builder.createdAt;
        this.deliveryStatus = builder.deliveryStatus;
    }

    // --- Status transitions ---

    public void markAsSent() {
        this.status = MessageStatus.SENT;
    }

    public void markAsDelivered(String userId) {
        this.status = MessageStatus.DELIVERED;
        this.deliveryStatus.put(userId, MessageStatus.DELIVERED);
    }

    public void markAsRead(String userId) {
        this.status = MessageStatus.READ;
        this.deliveryStatus.put(userId, MessageStatus.READ);
    }

    // --- Delivery queries ---

    public MessageStatus getDeliveryStatusForUser(String userId) {
        return deliveryStatus.getOrDefault(userId, MessageStatus.SENT);
    }

    /**
     * True when every member (except sender) has at least DELIVERED status.
     */
    public boolean isFullyDelivered(List<String> memberIds) {
        return memberIds.stream()
                .filter(id -> !id.equals(senderId))
                .allMatch(id -> {
                    MessageStatus s = deliveryStatus.get(id);
                    return s == MessageStatus.DELIVERED || s == MessageStatus.READ;
                });
    }

    /**
     * True when every member (except sender) has READ status.
     */
    public boolean isFullyRead(List<String> memberIds) {
        return memberIds.stream()
                .filter(id -> !id.equals(senderId))
                .allMatch(id -> deliveryStatus.get(id) == MessageStatus.READ);
    }

    // --- Getters ---

    public String getMessageId() {
        return messageId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getSenderId() {
        return senderId;
    }

    public String getContent() {
        return content;
    }

    public MessageType getType() {
        return type;
    }

    public MessageStatus getStatus() {
        return status;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public Map<String, MessageStatus> getDeliveryStatus() {
        return Collections.unmodifiableMap(deliveryStatus);
    }

    @Override
    public int compareTo(Message other) {
        return Long.compare(this.sequenceNumber, other.sequenceNumber);
    }

    @Override
    public String toString() {
        return String.format("[%s] %s: %s %s (seq=%d)",
                type.getDisplayName(), senderId, content, status.getSymbol(), sequenceNumber);
    }

    // ========== Builder ==========

    public static class Builder {
        private String messageId;
        private String conversationId;
        private String senderId;
        private String content;
        private MessageType type = MessageType.TEXT;
        private MessageStatus status = MessageStatus.SENDING;
        private long sequenceNumber;
        private LocalDateTime createdAt = LocalDateTime.now();
        private final Map<String, MessageStatus> deliveryStatus = new ConcurrentHashMap<>();

        public Builder messageId(String messageId) {
            this.messageId = messageId;
            return this;
        }

        public Builder conversationId(String conversationId) {
            this.conversationId = conversationId;
            return this;
        }

        public Builder senderId(String senderId) {
            this.senderId = senderId;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder type(MessageType type) {
            this.type = type;
            return this;
        }

        public Builder status(MessageStatus status) {
            this.status = status;
            return this;
        }

        public Builder sequenceNumber(long sequenceNumber) {
            this.sequenceNumber = sequenceNumber;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder addRecipient(String userId) {
            this.deliveryStatus.put(userId, MessageStatus.SENT);
            return this;
        }

        public Message build() {
            return new Message(this);
        }
    }
}
