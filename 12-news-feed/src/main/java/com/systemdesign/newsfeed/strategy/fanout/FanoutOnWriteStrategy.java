package com.systemdesign.newsfeed.strategy.fanout;

import com.systemdesign.newsfeed.model.Post;
import com.systemdesign.newsfeed.model.User;
import com.systemdesign.newsfeed.store.TimelineCache;

import java.util.List;

/**
 * FanoutOnWriteStrategy — Push model: write post to ALL follower timelines on publish.
 *
 * Design notes for interview:
 * - O(followers) write cost, O(1) read cost.
 * - When user publishes a post, we iterate ALL their followers and add the
 *   postId to each follower's timeline cache (a sorted set of postIds by timestamp).
 * - Great for normal users (100-1000 followers): the write cost is bounded.
 * - TERRIBLE for celebrities (1M+ followers): writing to 1M timelines per post
 *   is extremely expensive and causes write amplification.
 *   Example: if a celebrity with 10M followers posts once, that's 10M cache writes.
 *   If they post 10 times/day, that's 100M writes/day from ONE user.
 *
 * Tradeoff visualization:
 *   Write cost: O(N) where N = follower count  [HIGH for celebrities]
 *   Read cost:  O(1) — just read pre-computed timeline  [LOW for everyone]
 *   Storage:    O(N * posts) — every follower stores a copy  [HIGH]
 *   Freshness:  Immediate — post appears in feed instantly  [BEST]
 *
 * Used by: Twitter (originally), Instagram (for normal users)
 */
public class FanoutOnWriteStrategy implements FanoutStrategy {

    @Override
    public void distribute(Post post, User author, List<String> followerIds, TimelineCache cache) {
        // --- Core fan-out loop ---
        // For each follower, add this post to their timeline cache.
        // In production, this would be an async operation using a message queue
        // (Kafka, SQS) to avoid blocking the post creation API call.
        // Workers would consume fan-out tasks and write to Redis/Memcached timelines.
        for (String followerId : followerIds) {
            cache.addToTimeline(followerId, post.getPostId(), post.getCreatedAt());
        }

        System.out.printf("   [FanoutOnWrite] Pushed post '%s' to %d follower timelines%n",
                post.getPostId(), followerIds.size());
    }

    @Override
    public String getStrategyName() {
        return "Fan-out on Write (Push)";
    }
}
