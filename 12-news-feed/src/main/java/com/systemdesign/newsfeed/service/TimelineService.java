package com.systemdesign.newsfeed.service;

import com.systemdesign.newsfeed.model.FeedCursor;
import com.systemdesign.newsfeed.model.Post;
import com.systemdesign.newsfeed.repository.PostRepository;
import com.systemdesign.newsfeed.store.TimelineCache;

import java.time.LocalDateTime;
import java.util.List;

/**
 * TimelineService — Manages per-user timeline reads and writes.
 *
 * Design notes for interview:
 * - The timeline is a pre-computed list of postIds for each user.
 * - It's populated by fan-out-on-write (FanoutOnWriteStrategy adds postIds
 *   to follower timelines when a normal user publishes).
 * - On feed read, FeedService calls TimelineService to get the cached timeline,
 *   then hydrates the postIds into full Post objects.
 *
 * - "Hydration" = converting postIds to full Post objects.
 *   The timeline cache only stores postIds (not full posts) because:
 *   1. Memory: storing full posts in every follower's timeline = massive duplication.
 *   2. Freshness: if a post is edited (content change, like count update),
 *      the cached copy would be stale. With postIds, we always fetch the
 *      latest version from PostRepository at read time.
 *
 * Call chain:
 *   FeedService.getFeed()
 *     -> TimelineService.getTimeline(userId, cursor)   [get postIds from cache]
 *     -> PostRepository.findByIds(postIds)              [hydrate to full Posts]
 */
public class TimelineService {

    private final TimelineCache timelineCache;
    private final PostRepository postRepository;

    public TimelineService(TimelineCache timelineCache, PostRepository postRepository) {
        this.timelineCache = timelineCache;
        this.postRepository = postRepository;
    }

    /**
     * Add a post to a user's timeline.
     * Called by FanoutOnWriteStrategy during fan-out.
     */
    public void addToTimeline(String userId, String postId, LocalDateTime timestamp) {
        timelineCache.addToTimeline(userId, postId, timestamp);
    }

    /**
     * Get timeline posts using cursor-based pagination.
     * Returns hydrated Post objects (not just postIds).
     */
    public List<Post> getTimeline(String userId, FeedCursor cursor) {
        // Step 1: Get postIds from timeline cache
        List<String> postIds;
        if (cursor.isFirstPage()) {
            postIds = timelineCache.getTimeline(userId, cursor.getPageSize());
        } else {
            postIds = timelineCache.getTimelineWithCursor(userId, cursor);
        }

        // Step 2: Hydrate postIds -> full Post objects
        // In production, this would be a multi-get from the Post cache (Redis)
        // or a batch query to the Post database.
        return postRepository.findByIds(postIds);
    }

    /**
     * Get timeline size for a user (number of cached entries).
     */
    public int getTimelineSize(String userId) {
        return timelineCache.getTimelineSize(userId);
    }

    /**
     * Remove a post from a user's timeline (e.g., post deleted).
     */
    public void removeFromTimeline(String userId, String postId) {
        timelineCache.removeFromTimeline(userId, postId);
    }
}
