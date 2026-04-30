package com.systemdesign.cache.service;

import com.systemdesign.cache.model.CacheConfig.EvictionPolicy;
import com.systemdesign.cache.strategy.eviction.EvictionStrategy;

import java.util.HashMap;
import java.util.Map;

/**
 * EvictionService — Manages eviction strategy selection and runtime switching.
 *
 * WHY a separate service for this?
 *   In a real cache (like Redis), you can change the eviction policy at runtime:
 *     CONFIG SET maxmemory-policy allkeys-lfu
 *   EvictionService holds ALL available strategies and can swap the active one.
 *
 * HOW IT WORKS:
 *   - Holds a Map<EvictionPolicy, EvictionStrategy> of all registered strategies
 *   - getStrategy(policy) returns the strategy for a given policy
 *   - switchPolicy(newPolicy) swaps the active strategy
 *   - This is the Strategy pattern's "context" — it holds a reference to the current strategy
 *
 * WIRING:
 *   AppConfig creates LRU, LFU, TTL strategies → registers all in EvictionService
 *   → EvictionService.getActiveStrategy() → passed to CacheService
 *   → Demo 9 calls evictionService.switchPolicy() to compare LRU vs LFU hit rates
 *
 * WITHOUT EvictionService (ugly approach):
 *   // In CacheService constructor:
 *   if (config.getEvictionPolicy() == LRU) {
 *       this.strategy = new LRUEvictionStrategy(config.getMaxSize());
 *   } else if (config.getEvictionPolicy() == LFU) {
 *       this.strategy = new LFUEvictionStrategy(config.getMaxSize());
 *   }
 *   // Can't switch at runtime without rebuilding CacheService!
 *
 * WITH EvictionService:
 *   evictionService.switchPolicy(LFU); // one line, runtime switch
 */
public class EvictionService {

    // All available strategies, keyed by policy enum
    private final Map<EvictionPolicy, EvictionStrategy> strategies;

    // The currently active policy
    private EvictionPolicy activePolicy;

    public EvictionService() {
        this.strategies = new HashMap<>();
    }

    /**
     * Register an eviction strategy for a given policy.
     * Called by AppConfig during initialization.
     */
    public void registerStrategy(EvictionPolicy policy, EvictionStrategy strategy) {
        strategies.put(policy, strategy);
        System.out.printf("  [EvictionService] Registered strategy: %s → %s%n",
                policy, strategy.getEvictionPolicyName());
    }

    /**
     * Get the strategy for a specific policy.
     */
    public EvictionStrategy getStrategy(EvictionPolicy policy) {
        EvictionStrategy strategy = strategies.get(policy);
        if (strategy == null) {
            throw new IllegalArgumentException("No strategy registered for policy: " + policy);
        }
        return strategy;
    }

    /**
     * Get the currently active strategy.
     */
    public EvictionStrategy getActiveStrategy() {
        if (activePolicy == null) {
            throw new IllegalStateException("No active eviction policy set. Call setActivePolicy() first.");
        }
        return strategies.get(activePolicy);
    }

    /**
     * Set the active eviction policy.
     * Used during initialization and for runtime switching.
     */
    public void setActivePolicy(EvictionPolicy policy) {
        if (!strategies.containsKey(policy)) {
            throw new IllegalArgumentException("Cannot activate unregistered policy: " + policy);
        }
        this.activePolicy = policy;
        System.out.printf("  [EvictionService] Active policy set to: %s%n", policy);
    }

    /**
     * Switch the active policy at runtime.
     * Returns the new active strategy.
     *
     * NOTE: Switching policies doesn't migrate the eviction tracking data.
     * In a real system, you'd either:
     *   (a) Rebuild the new strategy's tracking from the cache entries, or
     *   (b) Accept that the new strategy starts "cold" and warms up over time.
     */
    public EvictionStrategy switchPolicy(EvictionPolicy newPolicy) {
        System.out.printf("  [EvictionService] Switching policy: %s → %s%n", activePolicy, newPolicy);
        setActivePolicy(newPolicy);
        return getActiveStrategy();
    }

    /**
     * Get all registered policies (for display).
     */
    public Map<EvictionPolicy, String> getRegisteredPolicies() {
        Map<EvictionPolicy, String> result = new HashMap<>();
        for (Map.Entry<EvictionPolicy, EvictionStrategy> entry : strategies.entrySet()) {
            result.put(entry.getKey(), entry.getValue().getEvictionPolicyName());
        }
        return result;
    }

    public EvictionPolicy getActivePolicy() {
        return activePolicy;
    }
}
