package com.systemdesign.chat.model;

import java.time.LocalDateTime;

/**
 * Represents a user in the chat system with presence tracking.
 */
public class User {

    private final String userId;
    private final String username;
    private final String avatarUrl;
    private UserStatus status;
    private LocalDateTime lastSeen;

    public User(String userId, String username, String avatarUrl) {
        this.userId = userId;
        this.username = username;
        this.avatarUrl = avatarUrl;
        this.status = UserStatus.OFFLINE;
        this.lastSeen = LocalDateTime.now();
    }

    // --- Getters ---

    public String getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public UserStatus getStatus() {
        return status;
    }

    public LocalDateTime getLastSeen() {
        return lastSeen;
    }

    // --- Presence transitions ---

    public void goOnline() {
        this.status = UserStatus.ONLINE;
        this.lastSeen = LocalDateTime.now();
    }

    public void goOffline() {
        this.status = UserStatus.OFFLINE;
        this.lastSeen = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return String.format("User{%s '%s' %s %s}",
                userId, username, status.getIndicator(), status);
    }
}
