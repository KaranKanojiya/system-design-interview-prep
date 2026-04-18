package com.systemdesign.feed.fanout;

import com.systemdesign.feed.model.FeedItem;
import com.systemdesign.feed.model.FeedSource;
import com.systemdesign.feed.model.Tweet;
import com.systemdesign.feed.model.User;
import com.systemdesign.feed.repository.TimelineCacheRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * PUSH model: When a user posts, immediately write the tweet
 * to every follower's pre-computed timeline cache.
 *
 * Pros: Fast reads (feed is pre-built).
 * Cons: Expensive writes for users with many followers.
 */
public class FanoutOnWriteStrategy implements FanoutStrategy {

    @Override
    public void fanout(Tweet tweet, User poster, List<String> followerIds,
                       TimelineCacheRepository timelineCache) {
        long start = System.currentTimeMillis();

        double freshnessScore = computeFreshnessScore(tweet.getCreatedAt());

        for (String followerId : followerIds) {
            FeedItem item = new FeedItem(tweet, freshnessScore, FeedSource.FANOUT_WRITE);
            timelineCache.addToTimeline(followerId, item);
        }

        long elapsed = System.currentTimeMillis() - start;
        String preview = tweet.getContent().length() > 40
                ? tweet.getContent().substring(0, 40) + "..."
                : tweet.getContent();
        System.out.println("  [FANOUT-WRITE] Pushed tweet '" + preview + "' to "
                + followerIds.size() + " timelines (" + elapsed + "ms)");
    }

    @Override
    public String name() {
        return "Fan-out on Write (Push)";
    }

    private double computeFreshnessScore(LocalDateTime createdAt) {
        long secondsAgo = Duration.between(createdAt, LocalDateTime.now()).getSeconds();
        return 1000.0 / (1.0 + secondsAgo * 0.01);
    }
}
