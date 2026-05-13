package com.systemdesign.messagequeue.model;

/**
 * Retention configuration for a topic — controls when old messages are deleted or compacted.
 */
public class RetentionPolicy {

    private final long retentionMs;          // time-based retention (-1 = unlimited)
    private final long retentionBytes;       // size-based retention (-1 = unlimited)
    private final CleanupPolicy cleanupPolicy;  // deletion strategy

    private RetentionPolicy(long retentionMs, long retentionBytes, CleanupPolicy cleanupPolicy) {
        this.retentionMs = retentionMs;
        this.retentionBytes = retentionBytes;
        this.cleanupPolicy = cleanupPolicy;
    }

    // --- getters ---

    public long getRetentionMs() { return retentionMs; }
    public long getRetentionBytes() { return retentionBytes; }
    public CleanupPolicy getCleanupPolicy() { return cleanupPolicy; }

    // --- static factories ---

    /** Time-based retention — delete messages older than the given duration. */
    public static RetentionPolicy timeBased(long ms) {
        return new RetentionPolicy(ms, -1, CleanupPolicy.DELETE);
    }

    /** Size-based retention — delete oldest messages when partition exceeds the byte limit. */
    public static RetentionPolicy sizeBased(long bytes) {
        return new RetentionPolicy(-1, bytes, CleanupPolicy.DELETE);
    }

    /** Compaction — keep only the latest value for each key (log compaction). */
    public static RetentionPolicy compact() {
        return new RetentionPolicy(-1, -1, CleanupPolicy.COMPACT);
    }

    /** Combined time + size retention with a custom cleanup policy. */
    public static RetentionPolicy of(long retentionMs, long retentionBytes, CleanupPolicy policy) {
        return new RetentionPolicy(retentionMs, retentionBytes, policy);
    }

    @Override
    public String toString() {
        return "RetentionPolicy{retentionMs=" + retentionMs
                + ", retentionBytes=" + retentionBytes
                + ", cleanup=" + cleanupPolicy + "}";
    }

    // ===================== Inner Enum =====================

    /** Cleanup policy that determines how old data is removed. */
    public enum CleanupPolicy {
        /** Delete old segments when retention is exceeded. */
        DELETE,
        /** Compact the log — keep only the latest value per key. */
        COMPACT,
        /** Compact first, then delete segments exceeding retention. */
        COMPACT_DELETE
    }
}
