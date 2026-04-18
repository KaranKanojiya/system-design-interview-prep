package com.systemdesign.chat.handler;

import com.systemdesign.chat.model.Message;
import com.systemdesign.chat.model.ReadReceipt;
import com.systemdesign.chat.model.UserStatus;

/**
 * Simulates a WebSocket connection to a specific user.
 * In production this would wrap an actual WebSocket session;
 * here it prints to console to demonstrate the message flow.
 */
public class ConnectionHandler {

    private final String userId;
    private final String serverId;
    private final ConnectionRegistry registry;

    public ConnectionHandler(String userId, String serverId, ConnectionRegistry registry) {
        this.userId = userId;
        this.serverId = serverId;
        this.registry = registry;
    }

    public void connect() {
        registry.register(userId, serverId);
        System.out.printf("  [WS] %s connected to %s%n", userId, serverId);
    }

    public void disconnect() {
        registry.unregister(userId);
        System.out.printf("  [WS] %s disconnected%n", userId);
    }

    public void deliverMessage(Message msg, String senderName) {
        System.out.printf("  [WS -> %s] %s: %s %s%n",
                userId, senderName, msg.getContent(), msg.getStatus().getSymbol());
    }

    public void deliverPresenceUpdate(String targetUserId, UserStatus status) {
        System.out.printf("  [PRESENCE -> %s] %s is now %s %s%n",
                userId, targetUserId, status.getIndicator(), status);
    }

    public void deliverReadReceipt(ReadReceipt receipt) {
        System.out.printf("  [RECEIPT -> %s] %s read message %s%n",
                userId, receipt.getUserId(), receipt.getMessageId());
    }

    public String getUserId() {
        return userId;
    }

    public String getServerId() {
        return serverId;
    }
}
