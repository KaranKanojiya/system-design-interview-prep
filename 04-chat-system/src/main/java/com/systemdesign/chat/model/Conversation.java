package com.systemdesign.chat.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a conversation (1:1 or group) with thread-safe member management
 * and monotonically increasing sequence numbers for message ordering.
 */
public class Conversation {

    private final String conversationId;
    private final ConversationType type;
    private String name;
    private final List<String> memberIds;
    private final String createdBy;
    private final LocalDateTime createdAt;
    private final AtomicLong sequenceCounter;

    public Conversation(String conversationId, ConversationType type, String name,
                        String createdBy) {
        this.conversationId = conversationId;
        this.type = type;
        this.name = name;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
        this.memberIds = new CopyOnWriteArrayList<>();
        this.sequenceCounter = new AtomicLong(0);
    }

    /**
     * Returns the next monotonically increasing sequence number for message ordering.
     */
    public long nextSequence() {
        return sequenceCounter.incrementAndGet();
    }

    // --- Member management ---

    public void addMember(String userId) {
        if (!memberIds.contains(userId)) {
            memberIds.add(userId);
        }
    }

    public void removeMember(String userId) {
        memberIds.remove(userId);
    }

    public boolean isMember(String userId) {
        return memberIds.contains(userId);
    }

    public int getMemberCount() {
        return memberIds.size();
    }

    /**
     * For 1:1 conversations, returns the other participant's ID.
     */
    public String getOtherMember(String userId) {
        if (type != ConversationType.ONE_TO_ONE) {
            throw new IllegalStateException("getOtherMember only valid for ONE_TO_ONE conversations");
        }
        return memberIds.stream()
                .filter(id -> !id.equals(userId))
                .findFirst()
                .orElse(null);
    }

    // --- Getters ---

    public String getConversationId() {
        return conversationId;
    }

    public ConversationType getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getMemberIds() {
        return memberIds;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return String.format("Conversation{%s, type=%s, name='%s', members=%d}",
                conversationId, type, name, memberIds.size());
    }
}
