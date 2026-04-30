package com.systemdesign.cache.strategy.eviction;

import com.systemdesign.cache.model.CacheEntry;

import java.util.HashMap;
import java.util.Map;

/**
 * LRUEvictionStrategy — O(1) Least Recently Used eviction using HashMap + Doubly Linked List.
 *
 * THIS IS THE CLASSIC INTERVIEW QUESTION: "Design an LRU Cache with O(1) get and put."
 *
 * KEY INSIGHT:
 *   - HashMap gives O(1) lookup by key → Node
 *   - Doubly linked list gives O(1) insert/remove if you have the node reference
 *   - Together: O(1) for everything
 *
 * DATA STRUCTURE:
 *   HashMap<String, Node> map     — key → node in the linked list
 *   [HEAD] ↔ [most recent] ↔ [recent] ↔ ... ↔ [least recent] ↔ [TAIL]
 *     ↑ sentinel (dummy)                                          ↑ sentinel (dummy)
 *
 *   - On access (get): move the accessed node to right after HEAD → O(1)
 *   - On eviction: remove the node right before TAIL → O(1)
 *   - On insert (put): add new node right after HEAD → O(1)
 *
 * WHY NOT just use LinkedHashMap?
 *   Java's LinkedHashMap with accessOrder=true is essentially an LRU cache.
 *   But interviewers want to see YOU implement the data structure.
 *   Using LinkedHashMap shows you know the API, not the algorithm.
 *
 * WHY sentinel nodes (dummy head/tail)?
 *   Without sentinels, every operation needs null checks:
 *     if (node.prev != null) node.prev.next = node.next;  // is it the head?
 *     else head = node.next;                                // special case!
 *     if (node.next != null) node.next.prev = node.prev;  // is it the tail?
 *     else tail = node.prev;                                // special case!
 *   With sentinels, the code is uniform — no special cases, no bugs.
 *
 * WIRING: AppConfig creates LRUEvictionStrategy(capacity) → injects into CacheService.
 */
public class LRUEvictionStrategy implements EvictionStrategy {

    // ===========================================================================================
    // Inner Node class for the doubly linked list.
    // Each node holds a key (so we can find it in the HashMap when evicting from the tail).
    // ===========================================================================================
    private static class Node {
        String key;
        Node prev;
        Node next;

        Node(String key) {
            this.key = key;
        }
    }

    // --- Core data structures ---
    private final Map<String, Node> map;    // O(1) lookup: key → linked list node
    private final Node head;                // sentinel — dummy node at the front (most recent side)
    private final Node tail;                // sentinel — dummy node at the back (least recent side)
    private final int capacity;

    public LRUEvictionStrategy(int capacity) {
        this.capacity = capacity;
        this.map = new HashMap<>();

        // Initialize sentinel nodes and link them together.
        // Starting state: HEAD ↔ TAIL (empty list)
        this.head = new Node("HEAD_SENTINEL");
        this.tail = new Node("TAIL_SENTINEL");
        head.next = tail;
        tail.prev = head;
    }

    /**
     * On get: move the accessed key to the front (most recently used position).
     *
     * Steps:
     *   1. Look up the node in the HashMap → O(1)
     *   2. Remove it from its current position in the linked list → O(1)
     *   3. Insert it right after HEAD → O(1)
     *
     * Before: HEAD ↔ A ↔ [B] ↔ C ↔ TAIL   (accessing B)
     * After:  HEAD ↔ [B] ↔ A ↔ C ↔ TAIL   (B moved to front)
     */
    @Override
    public void onGet(String key) {
        Node node = map.get(key);
        if (node != null) {
            moveToHead(node);
        }
    }

    /**
     * On put: add the new key at the front.
     * If the key already exists, just move it to the front (update).
     */
    @Override
    public void onPut(String key, CacheEntry entry) {
        Node existing = map.get(key);
        if (existing != null) {
            // Key already tracked — just refresh its position
            moveToHead(existing);
        } else {
            // New key — create node and add to front
            Node newNode = new Node(key);
            map.put(key, newNode);
            addAfterHead(newNode);
        }
    }

    /**
     * Evict the least recently used key (the one right before TAIL).
     *
     * Why the tail? Because every access moves a node to the head.
     * So the node closest to the tail hasn't been accessed for the longest time.
     *
     *   HEAD ↔ [most recent] ↔ ... ↔ [LEAST RECENT] ↔ TAIL
     *                                       ↑ evict this one
     */
    @Override
    public String evict() {
        if (map.isEmpty()) {
            return null;
        }

        // The node just before TAIL is the least recently used
        Node lru = tail.prev;
        if (lru == head) {
            return null; // list is empty (only sentinels)
        }

        String evictedKey = lru.key;
        removeNode(lru);
        map.remove(evictedKey);
        return evictedKey;
    }

    @Override
    public void remove(String key) {
        Node node = map.get(key);
        if (node != null) {
            removeNode(node);
            map.remove(key);
        }
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public String getEvictionPolicyName() {
        return "LRU (Least Recently Used)";
    }

    // ===========================================================================================
    // Private helpers — the actual linked list operations.
    //
    // These are the three operations interviewers want to see:
    //   1. removeNode(node)  — unlink a node from anywhere in the list
    //   2. addAfterHead(node) — insert a node at the front
    //   3. moveToHead(node) = removeNode + addAfterHead
    // ===========================================================================================

    /**
     * Remove a node from the doubly linked list.
     * Thanks to sentinels, this is always the same — no null checks needed.
     *
     * Before: ... ↔ [A] ↔ [node] ↔ [B] ↔ ...
     * After:  ... ↔ [A] ↔ [B] ↔ ...
     */
    private void removeNode(Node node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    /**
     * Add a node right after the HEAD sentinel (most recently used position).
     *
     * Before: HEAD ↔ [A] ↔ ...
     * After:  HEAD ↔ [node] ↔ [A] ↔ ...
     */
    private void addAfterHead(Node node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    /**
     * Move an existing node to the front = remove it + add it after head.
     * Called on every access to mark this key as "most recently used."
     */
    private void moveToHead(Node node) {
        removeNode(node);
        addAfterHead(node);
    }
}
