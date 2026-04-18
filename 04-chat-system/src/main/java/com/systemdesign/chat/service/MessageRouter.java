package com.systemdesign.chat.service;

import com.systemdesign.chat.handler.ConnectionHandler;
import com.systemdesign.chat.handler.ConnectionRegistry;
import com.systemdesign.chat.model.Conversation;
import com.systemdesign.chat.model.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core message routing engine. Routes messages to online recipients via their
 * ConnectionHandler, or queues them for offline delivery.
 *
 * In a distributed system this would publish to a message bus (Kafka/Redis);
 * here we simulate with in-memory routing and offline queues.
 */
public class MessageRouter {

    private final ConnectionRegistry registry;
    private final Map<String, ConnectionHandler> connections = new ConcurrentHashMap<>();
    private final Map<String, List<Message>> offlineQueues = new ConcurrentHashMap<>();

    public MessageRouter(ConnectionRegistry registry) {
        this.registry = registry;
    }

    /**
     * Routes a message to a single recipient. If online, delivers immediately
     * and marks as DELIVERED. If offline, queues for later delivery.
     */
    public void routeToUser(Message msg, String recipientId, String senderName) {
        if (registry.isOnline(recipientId)) {
            ConnectionHandler handler = connections.get(recipientId);
            if (handler != null) {
                msg.markAsDelivered(recipientId);
                handler.deliverMessage(msg, senderName);
            }
        } else {
            offlineQueues.computeIfAbsent(recipientId, k -> new ArrayList<>()).add(msg);
            System.out.printf("  [QUEUE] Message queued for offline user %s%n", recipientId);
        }
    }

    /**
     * Fan-out: routes a message to every member of a group except the sender.
     */
    public void routeToGroup(Message msg, Conversation group, String senderName) {
        int count = 0;
        for (String memberId : group.getMemberIds()) {
            if (!memberId.equals(msg.getSenderId())) {
                routeToUser(msg, memberId, senderName);
                count++;
            }
        }
        System.out.printf("  [FANOUT] Message routed to %d members of group '%s'%n",
                count, group.getName());
    }

    /**
     * Delivers all queued offline messages when a user comes online.
     */
    public void deliverOfflineMessages(String userId) {
        List<Message> queued = offlineQueues.remove(userId);
        if (queued == null || queued.isEmpty()) {
            return;
        }

        ConnectionHandler handler = connections.get(userId);
        if (handler == null) {
            return;
        }

        for (Message msg : queued) {
            msg.markAsDelivered(userId);
            handler.deliverMessage(msg, msg.getSenderId());
        }
        System.out.printf("  [SYNC] Delivered %d offline messages to %s%n", queued.size(), userId);
    }

    public void registerConnection(String userId, ConnectionHandler handler) {
        connections.put(userId, handler);
    }

    public int getOfflineQueueSize(String userId) {
        List<Message> queue = offlineQueues.get(userId);
        return queue == null ? 0 : queue.size();
    }
}
