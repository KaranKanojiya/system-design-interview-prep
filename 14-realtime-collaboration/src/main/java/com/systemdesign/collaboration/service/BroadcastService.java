package com.systemdesign.collaboration.service;

import com.systemdesign.collaboration.model.CursorPosition;
import com.systemdesign.collaboration.model.Operation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simulated WebSocket broadcast service.
 *
 * In a real system, this would maintain WebSocket connections to each client
 * and push operations / cursor updates in real time.  Here we simulate it
 * with console output and in-memory connection tracking.
 *
 * Architecture:
 *   - Each "connection" is a (userId, docId) pair.
 *   - When an operation is applied, it's broadcast to all users connected to
 *     that document EXCEPT the user who submitted it (they already have it).
 *   - Cursor updates are similarly broadcast so every user sees everyone else's cursor.
 *
 * In production: use a pub/sub system (Redis Pub/Sub, Kafka) to fan out
 * operations to WebSocket server instances.
 */
public class BroadcastService {

    /** docId → set of connected userIds */
    private final Map<String, Set<String>> connections = new ConcurrentHashMap<>();

    /** Whether to print broadcast messages to console (for demo). */
    private boolean verbose = true;

    /**
     * Register a user's connection to a document (simulates WebSocket connect).
     */
    public void registerConnection(String userId, String docId) {
        connections.computeIfAbsent(docId, k -> ConcurrentHashMap.newKeySet())
                   .add(userId);
        if (verbose) {
            System.out.println("   [WebSocket] " + userId + " connected to doc " + docId);
        }
    }

    /**
     * Remove a user's connection (simulates WebSocket disconnect).
     */
    public void removeConnection(String userId, String docId) {
        Set<String> docConnections = connections.get(docId);
        if (docConnections != null) {
            docConnections.remove(userId);
            if (verbose) {
                System.out.println("   [WebSocket] " + userId + " disconnected from doc " + docId);
            }
        }
    }

    /**
     * Broadcast an operation to all connected users of a document,
     * excluding the user who submitted it.
     *
     * In production: serialize the operation to JSON and push via WebSocket.
     */
    public void broadcastOperation(String docId, Operation operation, String excludeUserId) {
        Set<String> docConnections = connections.get(docId);
        if (docConnections == null) return;

        for (String userId : docConnections) {
            if (!userId.equals(excludeUserId)) {
                if (verbose) {
                    System.out.println("   [WebSocket] → Sending to " + userId + ": " + operation);
                }
            }
        }
    }

    /**
     * Broadcast a cursor position update to all connected users.
     */
    public void broadcastCursorUpdate(String docId, CursorPosition cursorPosition) {
        Set<String> docConnections = connections.get(docId);
        if (docConnections == null) return;

        for (String userId : docConnections) {
            if (!userId.equals(cursorPosition.getUserId())) {
                if (verbose) {
                    System.out.println("   [WebSocket] → Cursor update to " + userId +
                                       ": " + cursorPosition);
                }
            }
        }
    }

    /**
     * Get all users connected to a document.
     */
    public List<String> getConnectedUsers(String docId) {
        Set<String> docConnections = connections.get(docId);
        if (docConnections == null) return List.of();
        return new ArrayList<>(docConnections);
    }

    /** Enable/disable console output for broadcasts. */
    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }
}
