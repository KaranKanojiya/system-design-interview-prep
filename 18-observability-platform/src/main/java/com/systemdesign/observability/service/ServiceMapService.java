package com.systemdesign.observability.service;

// Wiring: ServiceMapService builds a service dependency graph from observed calls.
// No external dependencies — maintains its own in-memory graph of ServiceNodes.
// Populated by registerCall() as services communicate with each other.

import com.systemdesign.observability.model.ServiceNode;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ServiceMapService — builds and queries the service dependency topology.
 *
 * FLOW — registerCall(callerService, calleeService, latencyMs, success):
 *   1. Get or create ServiceNode for both caller and callee
 *   2. Add callee as a dependency of caller
 *   3. Add caller as a dependent of callee
 *   4. Update rolling stats (request count, error rate, avg latency)
 *
 * FLOW — getTopology():
 *   1. Build adjacency list: for each service, list its downstream dependencies
 *   2. Return as Map<String, Set<String>>
 *
 * FLOW — printServiceMap():
 *   1. Walk the topology graph
 *   2. Print ASCII representation of service dependencies
 */
public class ServiceMapService {

    // serviceName -> ServiceNode
    private final Map<String, ServiceNode> serviceNodes = new LinkedHashMap<>();

    // Rolling stats for each edge: "caller->callee" -> [totalRequests, errors, totalLatencyMs]
    private final Map<String, long[]> edgeStats = new HashMap<>();

    public ServiceMapService() {
        // no-arg constructor — graph is built incrementally via registerCall()
    }

    // ---- graph construction ----

    /**
     * Registers an observed call between two services and updates the dependency graph.
     *
     * @param callerService the upstream service making the call
     * @param calleeService the downstream service being called
     * @param latencyMs     the call latency in milliseconds
     * @param success       true if the call succeeded, false if it failed
     */
    public void registerCall(String callerService, String calleeService,
                             long latencyMs, boolean success) {
        // 1. Get or create nodes
        ServiceNode caller = serviceNodes.computeIfAbsent(callerService, ServiceNode::new);
        ServiceNode callee = serviceNodes.computeIfAbsent(calleeService, ServiceNode::new);

        // 2. Wire dependency edges
        caller.addDependency(calleeService);
        callee.addDependent(callerService);

        // 3. Update edge stats
        String edgeKey = callerService + "->" + calleeService;
        long[] stats = edgeStats.computeIfAbsent(edgeKey, k -> new long[]{0, 0, 0});
        stats[0]++;                             // total requests
        if (!success) stats[1]++;               // errors
        stats[2] += latencyMs;                  // cumulative latency

        // 4. Update caller node stats
        updateNodeStats(callerService);
        updateNodeStats(calleeService);

        System.out.println("[SERVICE_MAP] " + callerService + " -> " + calleeService
                + " | latency=" + latencyMs + "ms | success=" + success);
    }

    // ---- querying ----

    /**
     * Returns the ServiceNode for the given service name.
     */
    public Optional<ServiceNode> getServiceNode(String serviceName) {
        return Optional.ofNullable(serviceNodes.get(serviceName));
    }

    /**
     * Returns all known service nodes.
     */
    public List<ServiceNode> getAllServices() {
        return new ArrayList<>(serviceNodes.values());
    }

    /**
     * Returns the downstream dependencies of the given service.
     */
    public Set<String> getDependencies(String serviceName) {
        ServiceNode node = serviceNodes.get(serviceName);
        if (node == null) return Set.of();
        return node.getDependencies();
    }

    /**
     * Returns the upstream dependents (callers) of the given service.
     */
    public Set<String> getDependents(String serviceName) {
        ServiceNode node = serviceNodes.get(serviceName);
        if (node == null) return Set.of();
        return node.getDependents();
    }

    /**
     * Returns the full service topology as an adjacency list.
     *
     * @return map of service name to its set of downstream dependencies
     */
    public Map<String, Set<String>> getTopology() {
        Map<String, Set<String>> topology = new LinkedHashMap<>();
        for (Map.Entry<String, ServiceNode> entry : serviceNodes.entrySet()) {
            topology.put(entry.getKey(), entry.getValue().getDependencies());
        }
        return topology;
    }

    /**
     * Prints an ASCII representation of the service dependency graph.
     */
    public void printServiceMap() {
        System.out.println("=== SERVICE DEPENDENCY MAP ===");
        System.out.println();

        if (serviceNodes.isEmpty()) {
            System.out.println("  (no services registered)");
            System.out.println();
            System.out.println("==============================");
            return;
        }

        // Find root services (no dependents — entry points)
        List<String> roots = serviceNodes.values().stream()
                .filter(n -> n.getDependents().isEmpty())
                .map(ServiceNode::getServiceName)
                .collect(Collectors.toList());

        // If no clear roots, start from all services
        if (roots.isEmpty()) {
            roots = new ArrayList<>(serviceNodes.keySet());
        }

        Set<String> visited = new HashSet<>();
        for (String root : roots) {
            printNode(root, "", true, visited);
        }

        // Print edge stats
        System.out.println();
        System.out.println("--- Edge Statistics ---");
        for (Map.Entry<String, long[]> entry : edgeStats.entrySet()) {
            long[] stats = entry.getValue();
            long requests = stats[0];
            long errors = stats[1];
            double avgLatency = requests > 0 ? (double) stats[2] / requests : 0;
            double errorRate = requests > 0 ? (double) errors / requests : 0;

            System.out.printf("  %s | requests=%d | errorRate=%.1f%% | avgLatency=%.1fms%n",
                    entry.getKey(), requests, errorRate * 100, avgLatency);
        }

        System.out.println();
        System.out.println("==============================");
    }

    // ---- private helpers ----

    private void printNode(String serviceName, String prefix, boolean isLast,
                           Set<String> visited) {
        String connector = isLast ? "\\-- " : "|-- ";
        System.out.println(prefix + connector + serviceName);

        if (visited.contains(serviceName)) {
            System.out.println(prefix + (isLast ? "    " : "|   ") + "  (circular ref)");
            return;
        }
        visited.add(serviceName);

        ServiceNode node = serviceNodes.get(serviceName);
        if (node == null) return;

        List<String> deps = new ArrayList<>(node.getDependencies());
        for (int i = 0; i < deps.size(); i++) {
            String childPrefix = prefix + (isLast ? "    " : "|   ");
            printNode(deps.get(i), childPrefix, i == deps.size() - 1, visited);
        }
    }

    /**
     * Recalculates the aggregate stats for a service node based on all its edges.
     */
    private void updateNodeStats(String serviceName) {
        ServiceNode node = serviceNodes.get(serviceName);
        if (node == null) return;

        long totalRequests = 0;
        long totalErrors = 0;
        long totalLatency = 0;

        // Aggregate stats from all outgoing edges (this service as caller)
        for (Map.Entry<String, long[]> entry : edgeStats.entrySet()) {
            if (entry.getKey().startsWith(serviceName + "->")) {
                long[] stats = entry.getValue();
                totalRequests += stats[0];
                totalErrors += stats[1];
                totalLatency += stats[2];
            }
        }

        double errorRate = totalRequests > 0 ? (double) totalErrors / totalRequests : 0.0;
        double avgLatency = totalRequests > 0 ? (double) totalLatency / totalRequests : 0.0;

        node.updateStats(totalRequests, errorRate, avgLatency);
    }
}
