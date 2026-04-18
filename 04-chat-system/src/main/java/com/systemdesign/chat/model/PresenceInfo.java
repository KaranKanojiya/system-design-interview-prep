package com.systemdesign.chat.model;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Tracks a user's presence state, including heartbeat-based timeout detection.
 */
public class PresenceInfo {

    private final String userId;
    private UserStatus status;
    private LocalDateTime lastHeartbeat;
    private String connectedServer;

    public PresenceInfo(String userId, UserStatus status, LocalDateTime lastHeartbeat,
                        String connectedServer) {
        this.userId = userId;
        this.status = status;
        this.lastHeartbeat = lastHeartbeat;
        this.connectedServer = connectedServer;
    }

    /**
     * Returns true if the last heartbeat is older than the given timeout duration.
     */
    public boolean isTimedOut(Duration timeout) {
        return lastHeartbeat.plus(timeout).isBefore(LocalDateTime.now());
    }

    // --- Getters ---

    public String getUserId() {
        return userId;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }

    public LocalDateTime getLastHeartbeat() {
        return lastHeartbeat;
    }

    public void setLastHeartbeat(LocalDateTime lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }

    public String getConnectedServer() {
        return connectedServer;
    }

    public void setConnectedServer(String connectedServer) {
        this.connectedServer = connectedServer;
    }

    @Override
    public String toString() {
        return String.format("PresenceInfo{%s, %s %s, server=%s, heartbeat=%s}",
                userId, status.getIndicator(), status, connectedServer, lastHeartbeat);
    }
}
