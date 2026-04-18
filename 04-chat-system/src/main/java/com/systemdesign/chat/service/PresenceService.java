package com.systemdesign.chat.service;

import com.systemdesign.chat.handler.ConnectionRegistry;
import com.systemdesign.chat.model.PresenceInfo;
import com.systemdesign.chat.model.UserStatus;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages user presence using heartbeat-based detection.
 * Users that fail to heartbeat within the timeout window are automatically marked offline.
 */
public class PresenceService {

    private final ConcurrentHashMap<String, PresenceInfo> presenceMap = new ConcurrentHashMap<>();
    private final ConnectionRegistry registry;
    private final Duration timeout;

    public PresenceService(ConnectionRegistry registry) {
        this.registry = registry;
        this.timeout = Duration.ofSeconds(60);
    }

    /**
     * Records a heartbeat for the user, setting them to ONLINE.
     */
    public void heartbeat(String userId, String serverId) {
        presenceMap.compute(userId, (key, existing) -> {
            if (existing == null) {
                return new PresenceInfo(userId, UserStatus.ONLINE, LocalDateTime.now(), serverId);
            }
            existing.setStatus(UserStatus.ONLINE);
            existing.setLastHeartbeat(LocalDateTime.now());
            existing.setConnectedServer(serverId);
            return existing;
        });
    }

    /**
     * Marks a user as disconnected/offline.
     */
    public void disconnect(String userId) {
        PresenceInfo info = presenceMap.get(userId);
        if (info != null) {
            info.setStatus(UserStatus.OFFLINE);
        }
        registry.unregister(userId);
    }

    public PresenceInfo getPresence(String userId) {
        return presenceMap.getOrDefault(userId,
                new PresenceInfo(userId, UserStatus.OFFLINE, LocalDateTime.now(), null));
    }

    public boolean isOnline(String userId) {
        return registry.isOnline(userId);
    }

    /**
     * Scans all presence entries and marks timed-out users as OFFLINE.
     */
    public void checkTimeouts() {
        presenceMap.forEach((userId, info) -> {
            if (info.getStatus() == UserStatus.ONLINE && info.isTimedOut(timeout)) {
                info.setStatus(UserStatus.OFFLINE);
                registry.unregister(userId);
                System.out.printf("  [PRESENCE] %s timed out -> OFFLINE%n", userId);
            }
        });
    }

    public List<String> getOnlineUsers() {
        return presenceMap.entrySet().stream()
                .filter(e -> e.getValue().getStatus() == UserStatus.ONLINE)
                .map(java.util.Map.Entry::getKey)
                .toList();
    }
}
