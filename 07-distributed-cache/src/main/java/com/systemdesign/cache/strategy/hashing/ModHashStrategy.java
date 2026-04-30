package com.systemdesign.cache.strategy.hashing;

import com.systemdesign.cache.model.CacheNode;

import java.util.ArrayList;
import java.util.List;

/**
 * ModHashStrategy — Simple modular hashing (ANTI-PATTERN, included for comparison).
 *
 * HOW IT WORKS:
 *   node = nodes.get( hash(key) % numNodes )
 *   That's it. Simple, right? Too simple.
 *
 * WHY THIS IS BAD — THE REHASHING CATASTROPHE:
 *   Suppose you have 3 nodes and 9 keys:
 *     key0 → hash=0, 0%3=node0    key3 → hash=3, 3%3=node0    key6 → hash=6, 6%3=node0
 *     key1 → hash=1, 1%3=node1    key4 → hash=4, 4%3=node1    key7 → hash=7, 7%3=node1
 *     key2 → hash=2, 2%3=node2    key5 → hash=5, 5%3=node2    key8 → hash=8, 8%3=node2
 *
 *   Now ADD a 4th node. New formula: hash(key) % 4
 *     key0 → 0%4=node0 (same)     key3 → 3%4=node3 (MOVED!)   key6 → 6%4=node2 (MOVED!)
 *     key1 → 1%4=node1 (same)     key4 → 4%4=node0 (MOVED!)   key7 → 7%4=node3 (MOVED!)
 *     key2 → 2%4=node2 (same)     key5 → 5%4=node1 (MOVED!)   key8 → 8%4=node0 (MOVED!)
 *
 *   6 out of 9 keys moved! That's 67% of all data needs to be migrated.
 *   In general: (N-1)/N keys need to move. With 100 nodes: 99% of keys move. DISASTER.
 *
 *   Compare with consistent hashing: only ~1/N keys move (~33% with 3 nodes, ~1% with 100).
 *
 * THIS CLASS EXISTS ONLY TO SHOW THE PROBLEM. Use ConsistentHashStrategy in production.
 *
 * WIRING: AppConfig can create ModHashStrategy for demo comparison.
 *   Demo 5 uses both strategies to show the redistribution difference.
 */
public class ModHashStrategy implements HashingStrategy {

    private final List<CacheNode> nodes;

    public ModHashStrategy() {
        this.nodes = new ArrayList<>();
    }

    /**
     * Simple mod-based routing: hash(key) % numNodes.
     *
     * Works fine as long as the number of nodes NEVER changes.
     * The moment you add or remove a node, nearly all keys map to different nodes.
     */
    @Override
    public CacheNode getNode(String key, List<CacheNode> nodeList) {
        if (nodes.isEmpty()) {
            throw new IllegalStateException("No nodes available for mod hashing.");
        }

        int hash = Math.abs(key.hashCode());
        int index = hash % nodes.size();
        return nodes.get(index);
    }

    @Override
    public void addNode(CacheNode node) {
        nodes.add(node);
        // NOTE: Adding a node changes nodes.size(), which changes the mod result
        // for almost ALL existing keys. In a real system, this means a massive
        // cache miss storm (thundering herd) as all clients try to re-fetch data
        // from the database simultaneously.
        System.out.printf("  [ModHash] Added node '%s'. Total nodes: %d. " +
                "WARNING: ~%d%% of keys need remapping!%n",
                node.getNodeId(), nodes.size(),
                nodes.size() > 1 ? (int)((1.0 - 1.0/nodes.size()) * 100) : 0);
    }

    @Override
    public void removeNode(CacheNode node) {
        nodes.remove(node);
        System.out.printf("  [ModHash] Removed node '%s'. Total nodes: %d. " +
                "WARNING: ~%d%% of keys need remapping!%n",
                node.getNodeId(), nodes.size(),
                nodes.size() > 0 ? (int)((1.0 - 1.0/(nodes.size()+1)) * 100) : 100);
    }

    @Override
    public int getNodeCount() {
        return nodes.size();
    }
}
