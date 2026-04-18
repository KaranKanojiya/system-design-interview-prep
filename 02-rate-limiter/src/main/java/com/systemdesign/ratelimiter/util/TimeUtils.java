package com.systemdesign.ratelimiter.util;

/**
 * Utility methods for time calculations used across rate limiting strategies.
 */
public final class TimeUtils {

    private TimeUtils() {} // utility class

    /** Returns the start of the fixed window that contains the given timestamp. */
    public static long currentWindowStart(long timestamp, long windowSizeMs) {
        return (timestamp / windowSizeMs) * windowSizeMs;
    }

    /**
     * Returns how far into the current window we are, as a fraction [0.0, 1.0).
     * Used by the Sliding Window Counter to weight the previous window's count.
     */
    public static double windowOverlapFraction(long timestamp, long windowStart, long windowSizeMs) {
        return (double) (timestamp - windowStart) / windowSizeMs;
    }

    /** Formats milliseconds into a human-readable duration string. */
    public static String formatDuration(long ms) {
        if (ms < 1000) {
            return ms + "ms";
        }
        long seconds = ms / 1000;
        long remainingMs = ms % 1000;
        if (remainingMs == 0) {
            return seconds + "s";
        }
        return seconds + "s " + remainingMs + "ms";
    }
}
