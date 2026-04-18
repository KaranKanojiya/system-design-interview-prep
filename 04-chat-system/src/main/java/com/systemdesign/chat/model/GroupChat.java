package com.systemdesign.chat.model;

import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Extends Conversation with group-specific features: admin roles,
 * member limits, and a description field.
 */
public class GroupChat extends Conversation {

    private static final int DEFAULT_MAX_MEMBERS = 256;

    private final int maxMembers;
    private final Set<String> adminIds;
    private String description;

    public GroupChat(String conversationId, String name, String createdBy, String description) {
        super(conversationId, ConversationType.GROUP, name, createdBy);
        this.maxMembers = DEFAULT_MAX_MEMBERS;
        this.adminIds = new ConcurrentSkipListSet<>();
        this.description = description;
    }

    // --- Admin management ---

    public boolean isAdmin(String userId) {
        return adminIds.contains(userId);
    }

    public void addAdmin(String userId) {
        adminIds.add(userId);
    }

    public void removeAdmin(String userId) {
        adminIds.remove(userId);
    }

    public Set<String> getAdminIds() {
        return adminIds;
    }

    // --- Overridden member management with capacity check ---

    @Override
    public void addMember(String userId) {
        if (getMemberCount() >= maxMembers) {
            throw new IllegalStateException(
                    "Group has reached maximum capacity of " + maxMembers + " members");
        }
        super.addMember(userId);
    }

    // --- Getters ---

    public int getMaxMembers() {
        return maxMembers;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public String toString() {
        return String.format("GroupChat{%s, name='%s', members=%d, admins=%d, desc='%s'}",
                getConversationId(), getName(), getMemberCount(), adminIds.size(), description);
    }
}
