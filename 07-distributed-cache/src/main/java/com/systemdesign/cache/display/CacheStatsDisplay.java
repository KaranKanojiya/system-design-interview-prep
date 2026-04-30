package com.systemdesign.cache.display;

import com.systemdesign.cache.model.CacheStats;

import java.util.Map;

/**
 * CacheStatsDisplay — Formatted stats output for demos.
 *
 * Separates display logic from business logic.
 * CacheStats holds the data, CacheStatsDisplay formats it for human consumption.
 *
 * WIRING: AppConfig creates CacheStatsDisplay → injects into CacheController.
 *   CacheController.handleStats() → statsDisplay.printStats(stats)
 */
public class CacheStatsDisplay {

    private static final String THIN_SEP = "-".repeat(50);

    /**
     * Print formatted cache statistics.
     */
    public void printStats(CacheStats stats) {
        System.out.println(THIN_SEP);
        System.out.println("  CACHE STATISTICS");
        System.out.println(THIN_SEP);
        System.out.printf("  Total requests:  %d%n", stats.getTotalRequests());
        System.out.printf("  Cache hits:      %d%n", stats.getHits());
        System.out.printf("  Cache misses:    %d%n", stats.getMisses());
        System.out.printf("  Evictions:       %d%n", stats.getEvictions());
        System.out.printf("  Total puts:      %d%n", stats.getPuts());
        System.out.printf("  Hit rate:        %.2f%%%n", stats.getHitRate() * 100);
        System.out.printf("  Miss rate:       %.2f%%%n", stats.getMissRate() * 100);
        System.out.println(THIN_SEP);
    }

    /**
     * Print key distribution across nodes (for distributed cache demos).
     */
    public void printNodeDistribution(Map<String, Integer> distribution) {
        System.out.println(THIN_SEP);
        System.out.println("  KEY DISTRIBUTION ACROSS NODES");
        System.out.println(THIN_SEP);

        int totalKeys = distribution.values().stream().mapToInt(Integer::intValue).sum();

        for (Map.Entry<String, Integer> entry : distribution.entrySet()) {
            int count = entry.getValue();
            double percentage = totalKeys > 0 ? (double) count / totalKeys * 100 : 0;
            int barLength = totalKeys > 0 ? (int) (percentage / 2) : 0; // scale to ~50 chars max
            String bar = "#".repeat(barLength);

            System.out.printf("  %-12s: %4d keys (%5.1f%%) %s%n",
                    entry.getKey(), count, percentage, bar);
        }

        System.out.printf("  %-12s: %4d keys%n", "TOTAL", totalKeys);

        // Show standard deviation to measure distribution evenness
        if (distribution.size() > 1 && totalKeys > 0) {
            double mean = (double) totalKeys / distribution.size();
            double variance = distribution.values().stream()
                    .mapToDouble(v -> Math.pow(v - mean, 2))
                    .average()
                    .orElse(0);
            double stdDev = Math.sqrt(variance);
            System.out.printf("  Ideal (even): %.0f keys/node, StdDev: %.1f%n", mean, stdDev);
        }
        System.out.println(THIN_SEP);
    }

    /**
     * Print eviction policy information.
     */
    public void printEvictionInfo(String policyName, int trackedKeys, int maxSize) {
        System.out.println(THIN_SEP);
        System.out.println("  EVICTION INFO");
        System.out.println(THIN_SEP);
        System.out.printf("  Policy:        %s%n", policyName);
        System.out.printf("  Tracked keys:  %d%n", trackedKeys);
        System.out.printf("  Max capacity:  %d%n", maxSize);
        System.out.printf("  Utilization:   %.1f%%%n", (double) trackedKeys / maxSize * 100);
        System.out.println(THIN_SEP);
    }
}
