package com.systemdesign.newsfeed.service;

import com.systemdesign.newsfeed.model.Post;
import com.systemdesign.newsfeed.model.User;
import com.systemdesign.newsfeed.strategy.fanout.FanoutStrategy;
import com.systemdesign.newsfeed.store.TimelineCache;

import java.util.List;

/**
 * FanoutService — Orchestrates post distribution to follower timelines.
 *
 * Design notes for interview:
 * - This service is the bridge between PostService and FanoutStrategy.
 * - It gathers the follower list from SocialGraphService, then delegates
 *   the actual distribution to the configured FanoutStrategy.
 * - Logs fan-out count and time for monitoring (in production, these would
 *   be metrics in Datadog/Prometheus).
 *
 * Call chain:
 *   PostService.createPost()
 *     -> FanoutService.distribute()
 *       -> SocialGraphService.getFollowers(authorId)   [get follower list]
 *       -> FanoutStrategy.distribute(post, author, followers, cache)  [push or no-op]
 *       -> NotificationService.notifyNewPost(followers, post)         [send notifications]
 *
 * In production, this entire flow would be ASYNC:
 * 1. PostService saves the post and returns 200 OK immediately.
 * 2. A message is published to Kafka: "new post created by user X".
 * 3. Fan-out workers consume the message and execute the fan-out.
 * This decouples the write latency from the fan-out latency.
 */
public class FanoutService {

    private final FanoutStrategy fanoutStrategy;
    private final SocialGraphService socialGraphService;
    private final TimelineCache timelineCache;
    private final NotificationService notificationService;

    // --- Stats ---
    private long totalFanoutTimeMs = 0;
    private int totalFanoutCount = 0;

    public FanoutService(FanoutStrategy fanoutStrategy,
                         SocialGraphService socialGraphService,
                         TimelineCache timelineCache,
                         NotificationService notificationService) {
        this.fanoutStrategy = fanoutStrategy;
        this.socialGraphService = socialGraphService;
        this.timelineCache = timelineCache;
        this.notificationService = notificationService;
    }

    /**
     * Distribute a newly published post to the author's followers.
     *
     * Steps:
     * 1. Get follower list from social graph.
     * 2. Delegate to fan-out strategy (push to timelines, or no-op for celebrities).
     * 3. Send notifications to followers.
     * 4. Log metrics.
     */
    public void distribute(Post post, User author) {
        long startTime = System.nanoTime();

        // Step 1: Get follower IDs
        List<String> followerIds = socialGraphService.getFollowers(author.getUserId());

        if (followerIds.isEmpty()) {
            System.out.printf("   [Fanout] No followers for '%s' — skipping fan-out%n",
                    author.getName());
            return;
        }

        // Step 2: Delegate to strategy (push or no-op depending on celebrity status)
        fanoutStrategy.distribute(post, author, followerIds, timelineCache);

        // Step 3: Notify followers (async in production)
        notificationService.notifyNewPost(followerIds, post);

        // Step 4: Log metrics
        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
        totalFanoutTimeMs += elapsedMs;
        totalFanoutCount++;

        System.out.printf("   [Fanout] Strategy: %s | Followers: %d | Time: %dms%n",
                fanoutStrategy.getStrategyName(), followerIds.size(), elapsedMs);
    }

    // --- Stats getters ---

    public long getTotalFanoutTimeMs() {
        return totalFanoutTimeMs;
    }

    public int getTotalFanoutCount() {
        return totalFanoutCount;
    }

    public double getAverageFanoutTimeMs() {
        return totalFanoutCount == 0 ? 0 : (double) totalFanoutTimeMs / totalFanoutCount;
    }

    public FanoutStrategy getFanoutStrategy() {
        return fanoutStrategy;
    }
}
