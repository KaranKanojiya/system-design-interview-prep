package com.systemdesign.gateway.strategy.loadbalancing;

import com.systemdesign.gateway.model.HttpRequest;
import com.systemdesign.gateway.model.ServiceInstance;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Consistent hash load balancer using a virtual-node ring (TreeMap).
 *
 * Flow:
 *   1. Build a hash ring: each healthy instance gets virtualNodeCount positions
 *      (hash of "instanceId-vnode-N")
 *   2. Hash the request path
 *   3. Find the ceiling entry on the ring (TreeMap.ceilingEntry)
 *   4. Wrap around to first entry if null (ring is circular)
 *
 * Benefit: cache-affinity — the same request path consistently routes to the same
 * instance, improving local cache hit rates. Adding/removing instances only
 * redistributes ~1/N of the keys.
 *
 * // wiring: used when cache-affinity routing is configured for a service
 */
public class ConsistentHashLoadBalancer implements LoadBalancingStrategy {

    private static final int DEFAULT_VIRTUAL_NODE_COUNT = 150;

    private final int virtualNodeCount;

    /**
     * Creates a consistent hash load balancer with a custom virtual node count.
     *
     * @param virtualNodeCount number of virtual nodes per physical instance on the ring
     */
    public ConsistentHashLoadBalancer(int virtualNodeCount) {
        this.virtualNodeCount = virtualNodeCount > 0 ? virtualNodeCount : DEFAULT_VIRTUAL_NODE_COUNT;
    }

    /** Creates a consistent hash load balancer with the default 150 virtual nodes. */
    public ConsistentHashLoadBalancer() {
        this(DEFAULT_VIRTUAL_NODE_COUNT);
    }

    @Override
    public Optional<ServiceInstance> selectInstance(List<ServiceInstance> instances, HttpRequest request) {
        if (instances == null || instances.isEmpty()) {
            return Optional.empty();
        }

        // Step 1: filter to healthy instances
        List<ServiceInstance> healthy = instances.stream()
                .filter(ServiceInstance::isHealthy)
                .toList();

        if (healthy.isEmpty()) {
            return Optional.empty();
        }

        // Step 2: build the hash ring with virtual nodes
        TreeMap<Integer, ServiceInstance> ring = buildRing(healthy);

        // Step 3: hash the request path and find the target
        int requestHash = hash(request.getPath());
        Map.Entry<Integer, ServiceInstance> entry = ring.ceilingEntry(requestHash);

        // Step 4: wrap around if we're past the last node on the ring
        if (entry == null) {
            entry = ring.firstEntry();
        }

        ServiceInstance selected = entry.getValue();
        System.out.printf("[CONSISTENT_HASH] path='%s' hash=%d → instance %s (ring size=%d)%n",
                request.getPath(), requestHash, selected.getId(), ring.size());

        return Optional.of(selected);
    }

    @Override
    public String getStrategyName() {
        return "CONSISTENT_HASH";
    }

    // ── Private helpers ──

    /**
     * Builds the virtual-node ring for the given instances.
     */
    private TreeMap<Integer, ServiceInstance> buildRing(List<ServiceInstance> instances) {
        TreeMap<Integer, ServiceInstance> ring = new TreeMap<>();
        for (ServiceInstance instance : instances) {
            for (int i = 0; i < virtualNodeCount; i++) {
                String virtualNodeKey = instance.getId() + "-vnode-" + i;
                ring.put(hash(virtualNodeKey), instance);
            }
        }
        return ring;
    }

    /**
     * Simple hash function using FNV-1a variant for good distribution.
     */
    private int hash(String key) {
        int hash = 0x811c9dc5;  // FNV offset basis
        for (int i = 0; i < key.length(); i++) {
            hash ^= key.charAt(i);
            hash *= 0x01000193; // FNV prime
        }
        return hash & 0x7FFFFFFF; // ensure non-negative
    }
}
