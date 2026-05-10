package com.systemdesign.observability.model;

// Wiring: ServiceNode is a vertex in the service dependency graph.
// Built by ServiceMapBuilder from Span data -> visualized as a dependency topology.

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * A node in the service dependency map, tracking which services this node
 * calls (dependencies) and which services call it (dependents), plus live stats.
 */
public class ServiceNode {

    private final String serviceName;
    private final Set<String> dependencies;  // downstream services this node calls
    private final Set<String> dependents;    // upstream services that call this node
    private long requestCount;
    private double errorRate;
    private double avgLatencyMs;

    public ServiceNode(String serviceName) {
        this.serviceName = serviceName;
        this.dependencies = new HashSet<>();
        this.dependents = new HashSet<>();
    }

    // ---- mutations ----

    public void addDependency(String downstream) {
        dependencies.add(downstream);
    }

    public void addDependent(String upstream) {
        dependents.add(upstream);
    }

    /** Updates the rolling statistics for this service node. */
    public void updateStats(long requests, double errorRate, double avgLatency) {
        this.requestCount = requests;
        this.errorRate = errorRate;
        this.avgLatencyMs = avgLatency;
    }

    // ---- getters ----

    public String getServiceName() { return serviceName; }
    public Set<String> getDependencies() { return Collections.unmodifiableSet(dependencies); }
    public Set<String> getDependents() { return Collections.unmodifiableSet(dependents); }
    public long getRequestCount() { return requestCount; }
    public double getErrorRate() { return errorRate; }
    public double getAvgLatencyMs() { return avgLatencyMs; }

    @Override
    public String toString() {
        return "ServiceNode{'" + serviceName + "', deps=" + dependencies.size()
                + ", dependents=" + dependents.size()
                + ", requests=" + requestCount
                + ", errorRate=" + String.format("%.2f%%", errorRate * 100)
                + ", avgLatency=" + String.format("%.1fms", avgLatencyMs) + "}";
    }
}
