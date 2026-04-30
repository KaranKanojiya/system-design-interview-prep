package com.systemdesign.newsfeed.strategy.fanout;

import com.systemdesign.newsfeed.model.Post;
import com.systemdesign.newsfeed.model.User;
import com.systemdesign.newsfeed.store.TimelineCache;

import java.util.List;

/**
 * FanoutStrategy — Strategy interface for distributing posts to followers.
 *
 * Design notes for interview:
 * - This is THE core design decision in a news feed system.
 * - Two extremes:
 *   1. Fan-out on WRITE (push model): when a user posts, push it to ALL followers' timelines.
 *      Pro: O(1) read time (feed is pre-computed). Con: O(N) write time where N = followers.
 *   2. Fan-out on READ (pull model): when a user opens feed, pull posts from all followed users.
 *      Pro: O(1) write time. Con: O(M) read time where M = following count.
 * - Real systems (Facebook, Instagram, Twitter) use a HYBRID approach:
 *   Normal users -> fan-out on write (their follower count is manageable).
 *   Celebrities -> fan-out on read (can't push to millions of followers per post).
 *
 * Call chain: PostService.createPost() -> FanoutService.distribute() -> FanoutStrategy.distribute()
 */
public interface FanoutStrategy {

    /**
     * Distribute a post to the given follower timelines.
     *
     * @param post        the newly created post
     * @param author      the post author (used to check celebrity status)
     * @param followerIds list of user IDs that follow the author
     * @param cache       the timeline cache to write into (for push model)
     */
    void distribute(Post post, User author, List<String> followerIds, TimelineCache cache);

    /**
     * Human-readable name for logging/display.
     */
    String getStrategyName();
}
