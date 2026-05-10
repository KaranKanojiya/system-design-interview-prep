package com.systemdesign.gateway.display;

// Wiring: GatewayStatsDisplay is a console output helper that prints formatted tables
// for routes, service instances, circuit breakers, and the service registry.

import com.systemdesign.gateway.engine.ServiceRegistry;
import com.systemdesign.gateway.model.CircuitBreakerState;
import com.systemdesign.gateway.model.CircuitState;
import com.systemdesign.gateway.model.Route;
import com.systemdesign.gateway.model.ServiceInstance;
import com.systemdesign.gateway.service.CircuitBreakerService;
import com.systemdesign.gateway.service.GatewayService;
import com.systemdesign.gateway.service.LoadBalancerService;

import java.util.List;
import java.util.Map;

/**
 * Console display helper for API Gateway statistics and state.
 *
 * Prints formatted tables for:
 *   - Route table (path, target, methods, rate limit, priority)
 *   - Service instances (id, host:port, health, weight, zone)
 *   - Circuit breaker status (service, state, failures, successes)
 *   - Service registry (service names + instance counts)
 *   - Summary statistics
 */
public class GatewayStatsDisplay {

    private final GatewayService gatewayService;
    private final LoadBalancerService loadBalancerService;
    private final CircuitBreakerService circuitBreakerService;
    private final ServiceRegistry serviceRegistry;

    public GatewayStatsDisplay(GatewayService gatewayService,
                               LoadBalancerService loadBalancerService,
                               CircuitBreakerService circuitBreakerService,
                               ServiceRegistry serviceRegistry) {
        this.gatewayService = gatewayService;
        this.loadBalancerService = loadBalancerService;
        this.circuitBreakerService = circuitBreakerService;
        this.serviceRegistry = serviceRegistry;
    }

    // ── Route Table ─────────────────────────────────────────────────────

    /**
     * Prints all configured routes as a formatted table.
     * Columns: Path Pattern | Target Service | Methods | Rate Limit | Priority
     */
    public void printRouteTable() {
        printSeparator("ROUTE TABLE");
        List<Route> routes = gatewayService.getAllRoutes();

        if (routes.isEmpty()) {
            System.out.println("  (no routes configured)");
            return;
        }

        System.out.printf("  %-30s %-20s %-20s %-12s %-8s%n",
                "PATH PATTERN", "TARGET SERVICE", "METHODS", "RATE LIMIT", "PRIORITY");
        System.out.printf("  %-30s %-20s %-20s %-12s %-8s%n",
                "─".repeat(30), "─".repeat(20), "─".repeat(20), "─".repeat(12), "─".repeat(8));

        for (Route route : routes) {
            String methods = route.getMethods().toString();
            String rateLimit = route.getRateLimitPerSecond() > 0
                    ? route.getRateLimitPerSecond() + " req/s"
                    : "unlimited";

            System.out.printf("  %-30s %-20s %-20s %-12s %-8d%n",
                    truncate(route.getPathPattern(), 30),
                    truncate(route.getTargetService(), 20),
                    truncate(methods, 20),
                    rateLimit,
                    route.getPriority());
        }
    }

    // ── Service Instances ───────────────────────────────────────────────

    /**
     * Prints all instances for a given service as a formatted table.
     * Columns: ID (8 chars) | Host:Port | Health | Weight | Zone
     */
    public void printServiceInstances(String serviceName) {
        printSeparator("SERVICE INSTANCES: " + serviceName);
        List<ServiceInstance> instances = serviceRegistry.getInstances(serviceName);

        if (instances.isEmpty()) {
            System.out.println("  (no instances registered for " + serviceName + ")");
            return;
        }

        System.out.printf("  %-10s %-25s %-12s %-8s %-15s%n",
                "ID", "HOST:PORT", "HEALTH", "WEIGHT", "ZONE");
        System.out.printf("  %-10s %-25s %-12s %-8s %-15s%n",
                "─".repeat(10), "─".repeat(25), "─".repeat(12), "─".repeat(8), "─".repeat(15));

        for (ServiceInstance instance : instances) {
            System.out.printf("  %-10s %-25s %-12s %-8d %-15s%n",
                    truncateId(instance.getId()),
                    instance.getAddress(),
                    instance.getHealthStatus(),
                    instance.getWeight(),
                    instance.getZone());
        }
    }

    // ── Circuit Breaker Status ──────────────────────────────────────────

    /**
     * Prints the circuit breaker state for all tracked services.
     * Columns: Service | State | Failures | Successes
     */
    public void printCircuitBreakerStatus() {
        printSeparator("CIRCUIT BREAKER STATUS");
        Map<String, CircuitBreakerState> breakers = circuitBreakerService.getAllCircuitBreakerStates();

        if (breakers.isEmpty()) {
            System.out.println("  (no circuit breakers registered)");
            return;
        }

        System.out.printf("  %-25s %-12s %-10s %-10s%n",
                "SERVICE", "STATE", "FAILURES", "SUCCESSES");
        System.out.printf("  %-25s %-12s %-10s %-10s%n",
                "─".repeat(25), "─".repeat(12), "─".repeat(10), "─".repeat(10));

        for (Map.Entry<String, CircuitBreakerState> entry : breakers.entrySet()) {
            CircuitBreakerState state = entry.getValue();
            System.out.printf("  %-25s %-12s %-10d %-10d%n",
                    truncate(entry.getKey(), 25),
                    state.getState(),
                    state.getFailureCount(),
                    state.getSuccessCount());
        }
    }

    // ── Service Registry ────────────────────────────────────────────────

    /**
     * Prints all registered services with their instance counts.
     */
    public void printServiceRegistry() {
        printSeparator("SERVICE REGISTRY");
        Map<String, List<ServiceInstance>> allServices = serviceRegistry.getAllServices();

        if (allServices.isEmpty()) {
            System.out.println("  (no services registered)");
            return;
        }

        System.out.printf("  %-30s %-15s%n", "SERVICE NAME", "INSTANCES");
        System.out.printf("  %-30s %-15s%n", "─".repeat(30), "─".repeat(15));

        for (Map.Entry<String, List<ServiceInstance>> entry : allServices.entrySet()) {
            long healthyCount = entry.getValue().stream()
                    .filter(ServiceInstance::isHealthy)
                    .count();
            System.out.printf("  %-30s %d total (%d healthy)%n",
                    truncate(entry.getKey(), 30),
                    entry.getValue().size(),
                    healthyCount);
        }
    }

    // ── Summary Stats ───────────────────────────────────────────────────

    /**
     * Prints a final summary of gateway statistics.
     */
    public void printStats() {
        printSeparator("GATEWAY SUMMARY");

        List<Route> routes = gatewayService.getAllRoutes();
        Map<String, List<ServiceInstance>> allServices = serviceRegistry.getAllServices();
        Map<String, CircuitBreakerState> breakers = circuitBreakerService.getAllCircuitBreakerStates();

        int totalInstances = allServices.values().stream()
                .mapToInt(List::size)
                .sum();
        long healthyInstances = allServices.values().stream()
                .flatMap(List::stream)
                .filter(ServiceInstance::isHealthy)
                .count();
        long openCircuits = breakers.values().stream()
                .filter(cb -> cb.getState() == CircuitState.OPEN)
                .count();

        System.out.printf("  Routes configured:     %d%n", routes.size());
        System.out.printf("  Services registered:   %d%n", allServices.size());
        System.out.printf("  Total instances:       %d (%d healthy)%n", totalInstances, healthyInstances);
        System.out.printf("  Circuit breakers:      %d (%d open)%n", breakers.size(), openCircuits);
        System.out.printf("  Gateway status:        %s%n", gatewayService.getServiceStatus());
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * Prints a section separator with a title.
     */
    public void printSeparator(String title) {
        System.out.println();
        System.out.println("═".repeat(80));
        System.out.println("  " + title);
        System.out.println("═".repeat(80));
    }

    /** Truncates an ID to 8 characters for compact display. */
    private String truncateId(String id) {
        if (id == null) return "null";
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    /** Truncates a string to the given max length, appending ".." if truncated. */
    private String truncate(String value, int maxLength) {
        if (value == null) return "null";
        if (value.length() <= maxLength) return value;
        return value.substring(0, maxLength - 2) + "..";
    }
}
