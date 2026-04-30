package com.systemdesign.newsfeed.strategy.fanout;

import com.systemdesign.newsfeed.model.Post;
import com.systemdesign.newsfeed.model.User;
import com.systemdesign.newsfeed.store.TimelineCache;

import java.util.List;

/**
 * FanoutOnReadStrategy — Pull model: do NOTHING on publish; pull posts at read time.
 *
 * Design notes for interview:
 * - O(1) write cost, O(following) read cost.
 * - When a celebrity publishes a post, we do NOTHING here.
 *   Instead, when a follower opens their feed, FeedService will:
 *   1. Check which celebrities the user follows.
 *   2. Pull recent posts from each celebrity.
 *   3. Merge them with the pre-computed timeline (from fan-out-on-write for normal users).
 *
 * - Great for celebrities: posting is instant, no write amplification.
 * - Slow for users who follow many celebrities: each feed load requires
 *   multiple read queries to fetch celebrity posts.
 *
 * Tradeoff visualization:
 *   Write cost: O(1) — just save the post  [LOW]
 *   Read cost:  O(M) where M = celebrities followed  [HIGHER per read]
 *   Storage:    O(posts) — one copy per post  [LOW]
 *   Freshness:  Slight delay — posts appear on next feed refresh  [OK]
 *
 * Used by: Twitter (for high-follower accounts), Facebook (for pages)
 */
public class FanoutOnReadStrategy implements FanoutStrategy {

    @Override
    public void distribute(Post post, User author, List<String> followerIds, TimelineCache cache) {
        // --- NO-OP ---
        // Celebrity posts are NOT pushed to follower timelines.
        // They will be PULLED at read time by FeedService.getFeed().
        //
        // This is the key insight: by doing nothing here, we avoid the
        // O(N) write cost for celebrities with millions of followers.
        System.out.printf("   [FanoutOnRead] Post '%s' by celebrity '%s' — skipped push (will be pulled on read)%n",
                post.getPostId(), author.getName());
    }

    @Override
    public String getStrategyName() {
        return "Fan-out on Read (Pull)";
    }
}
