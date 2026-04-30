package com.systemdesign.newsfeed.store;

import java.time.LocalDateTime;
import java.util.List;

import com.systemdesign.newsfeed.model.FeedCursor;

/**
 * TimelineCache — Interface for per-user timeline storage.
 *
 * Design notes for interview:
 * - Each user has a timeline: a sorted list of postIds ordered by timestamp.
 * - In production, this would be Redis Sorted Sets (ZADD/ZRANGE):
 *   Key: "timeline:{userId}"
 *   Score: post timestamp (epoch millis)
 *   Member: postId
 * - Redis Sorted Sets give O(log N) insert and O(log N + M) range queries,
 *   which is perfect for timeline operations.
 * - The cache is CAPPED (typically 800-1000 entries per user) because:
 *   1. Memory is finite. 1B users * 1000 entries = 1T entries in Redis.
 *   2. Users rarely scroll past the first ~100 posts anyway.
 *   3. Older posts can be fetched from the database (slower but acceptable).
 *
 * Call chain:
 *   Fan-out on write: FanoutOnWriteStrategy -> TimelineCache.addToTimeline()
 *   Feed read:        FeedService -> TimelineService -> TimelineCache.getTimeline()
 */
public interface TimelineCache {

    /**
     * Add a post to a user's timeline.
     * If the timeline exceeds the max size, the oldest entry is evicted.
     */
    void addToTimeline(String userId, String postId, LocalDateTime timestamp);

    /**
     * Get the most recent postIds from a user's timeline.
     *
     * @param userId the user whose timeline to read
     * @param limit  max number of postIds to return
     * @return list of postIds, newest first
     */
    List<String> getTimeline(String userId, int limit);

    /**
     * Get postIds from a user's timeline using cursor-based pagination.
     * Returns posts AFTER the cursor position.
     */
    List<String> getTimelineWithCursor(String userId, FeedCursor cursor);

    /**
     * Remove a post from a user's timeline (e.g., when post is deleted).
     */
    void removeFromTimeline(String userId, String postId);

    /**
     * Get the number of entries in a user's timeline.
     */
    int getTimelineSize(String userId);
}
