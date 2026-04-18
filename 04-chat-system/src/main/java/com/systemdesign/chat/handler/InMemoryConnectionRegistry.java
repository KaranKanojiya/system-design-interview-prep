package com.systemdesign.chat.handler;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory implementation of the connection registry.
 * Maps userId to the serverId they are connected to.
 */
public class InMemoryConnectionRegistry implements ConnectionRegistry {

    private final ConcurrentHashMap<String, String> connections = new ConcurrentHashMap<>();

    @Override
    public void register(String userId, String serverId) {
        connections.put(userId, serverId);
    }

    @Override
    public void unregister(String userId) {
        connections.remove(userId);
    }

    @Override
    public Optional<String> getServer(String userId) {
        return Optional.ofNullable(connections.get(userId));
    }

    @Override
    public boolean isOnline(String userId) {
        return connections.containsKey(userId);
    }

    @Override
    public int getOnlineCount() {
        return connections.size();
    }
}
