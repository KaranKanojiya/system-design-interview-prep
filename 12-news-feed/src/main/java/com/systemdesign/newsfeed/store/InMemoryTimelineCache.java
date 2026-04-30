package com.systemdesign.newsfeed.store;

import com.systemdesign.newsfeed.model.FeedCursor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemoryTimelineCache — In-memory implementation of TimelineCache.
 *
 * Design notes for interview:
 * - Uses Map<String, TreeMap<LocalDateTime, String>> — one TreeMap per user.
 * - TreeMap is sorted by timestamp (descending via reverseOrder), giving us
 *   efficient range queries for pagination.
 * - CAPPED at 1000 entries per user. When full, the oldest entry is evicted.
 *   This mirrors Redis's ZREMRANGEBYRANK for maintaining sorted set size.
 * - Thread-safe via synchronized blocks on the per-user TreeMap.
 *   In production, Redis handles concurrency natively.
 *
 * Why TreeMap and not just a List?
 * - TreeMap gives O(log N) insert, O(log N) lookup by timestamp range.
 * - Cursor pagination needs "give me entries AFTER timestamp X" — TreeMap.headMap() does this in O(log N).
 * - A sorted List would need binary search or O(N) scan for cursor pagination.
 */
public class InMemoryTimelineCache implements TimelineCache {

    // --- Per-user timeline storage ---
    // Key: userId
    // Value: TreeMap<timestamp, postId> sorted newest-first (reverseOrder)
    private final Map<String, TreeMap<LocalDateTime, String>> timelines;

    // --- Max entries per user timeline ---
    // When exceeded, the OLDEST entry is evicted (the last in reverse-order TreeMap).
    private static final int MAX_TIMELINE_SIZE = 1000;

    public InMemoryTimelineCache() {
        this.timelines = new ConcurrentHashMap<>();
    }

    @Override
    public void addToTimeline(String userId, String postId, LocalDateTime timestamp) {
        // Get or create the user's timeline TreeMap.
        // Using computeIfAbsent for atomic creation.
        TreeMap<LocalDateTime, String> timeline = timelines.computeIfAbsent(
                userId, k -> new TreeMap<>(Collections.reverseOrder())
        );

        synchronized (timeline) {
            // Handle timestamp collision: if two posts have the exact same timestamp,
            // append a tiny offset to avoid overwriting. In production, you'd use
            // a composite key (timestamp + postId) or a SortedSet with custom comparator.
            LocalDateTime effectiveTimestamp = timestamp;
            while (timeline.containsKey(effectiveTimestamp)) {
                effectiveTimestamp = effectiveTimestamp.plusNanos(1);
            }

            timeline.put(effectiveTimestamp, postId);

            // --- Eviction: cap at MAX_TIMELINE_SIZE ---
            // Remove the OLDEST entry (last in reverse-order TreeMap).
            // This is O(1) for TreeMap.lastKey().
            while (timeline.size() > MAX_TIMELINE_SIZE) {
                timeline.pollLastEntry(); // Removes oldest (smallest timestamp in reverse order = oldest)
            }
        }
    }

    @Override
    public List<String> getTimeline(String userId, int limit) {
        TreeMap<LocalDateTime, String> timeline = timelines.get(userId);
        if (timeline == null) {
            return new ArrayList<>();
        }

        synchronized (timeline) {
            List<String> result = new ArrayList<>();
            int count = 0;
            // TreeMap is in reverse order (newest first), so iterating in order
            // gives us newest-first postIds.
            for (Map.Entry<LocalDateTime, String> entry : timeline.entrySet()) {
                if (count >= limit) break;
                result.add(entry.getValue());
                count++;
            }
            return result;
        }
    }

    @Override
    public List<String> getTimelineWithCursor(String userId, FeedCursor cursor) {
        TreeMap<LocalDateTime, String> timeline = timelines.get(userId);
        if (timeline == null) {
            return new ArrayList<>();
        }

        synchronized (timeline) {
            if (cursor.isFirstPage()) {
                // First page: just return top N
                return getTimeline(userId, cursor.getPageSize());
            }

            // --- Cursor-based pagination ---
            // We need entries AFTER (older than) the cursor timestamp.
            // Since TreeMap is in reverse order (newest first),
            // "after" means entries with timestamps LESS THAN the cursor timestamp.
            // TreeMap.tailMap(cursorTimestamp, false) gives us entries with keys
            // less than cursorTimestamp (because the TreeMap is in reverse order,
            // "tail" = smaller timestamps = older posts).
            NavigableMap<LocalDateTime, String> afterCursor =
                    timeline.tailMap(cursor.getLastTimestamp(), false);

            List<String> result = new ArrayList<>();
            int count = 0;
            boolean pastCursorPost = (cursor.getLastPostId() == null);

            for (Map.Entry<LocalDateTime, String> entry : afterCursor.entrySet()) {
                // Skip entries until we pass the cursor's lastPostId
                // (handles same-timestamp edge case)
                if (!pastCursorPost) {
                    pastCursorPost = true; // After the cursor timestamp boundary, start collecting
                }
                if (count >= cursor.getPageSize()) break;
                result.add(entry.getValue());
                count++;
            }
            return result;
        }
    }

    @Override
    public void removeFromTimeline(String userId, String postId) {
        TreeMap<LocalDateTime, String> timeline = timelines.get(userId);
        if (timeline == null) return;

        synchronized (timeline) {
            // Need to find and remove by value (postId).
            // O(N) scan — acceptable because removals are rare.
            timeline.values().removeIf(id -> id.equals(postId));
        }
    }

    @Override
    public int getTimelineSize(String userId) {
        TreeMap<LocalDateTime, String> timeline = timelines.get(userId);
        if (timeline == null) return 0;
        synchronized (timeline) {
            return timeline.size();
        }
    }
}
