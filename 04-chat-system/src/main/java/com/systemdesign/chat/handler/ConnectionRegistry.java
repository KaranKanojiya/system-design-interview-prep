package com.systemdesign.chat.handler;

import java.util.Optional;

/**
 * Tracks which users are connected and to which server,
 * enabling message routing in a distributed environment.
 */
public interface ConnectionRegistry {

    void register(String userId, String serverId);

    void unregister(String userId);

    Optional<String> getServer(String userId);

    boolean isOnline(String userId);

    int getOnlineCount();
}
