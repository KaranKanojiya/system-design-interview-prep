package com.systemdesign.feed.ranking;

import com.systemdesign.feed.model.FeedItem;
import com.systemdesign.feed.model.Tweet;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ranks feed items by engagement score with time decay.
 *
 * Score = freshnessFactor * (likes*1.0 + retweets*2.0 + replies*1.5 + 1)
 * freshnessFactor = 1.0 / (1 + hoursSincePosted * 0.1)
 *
 * This means:
 * - A tweet with high engagement stays visible longer.
 * - Even highly-engaged tweets decay over time.
 * - The +1 baseline ensures brand-new tweets with zero engagement still score > 0.
 */
public class EngagementRanker implements FeedRanker {

    @Override
    public List<FeedItem> rank(List<FeedItem> items) {
        List<FeedItem> ranked = new ArrayList<>(items);
        LocalDateTime now = LocalDateTime.now();

        for (FeedItem item : ranked) {
            Tweet tweet = item.getTweet();
            double hoursSincePosted = Duration.between(tweet.getCreatedAt(), now).toMinutes() / 60.0;
            double freshnessFactor = 1.0 / (1.0 + hoursSincePosted * 0.1);

            double engagementScore = tweet.getLikeCount() * 1.0
                    + tweet.getRetweetCount() * 2.0
                    + tweet.getReplyCount() * 1.5
                    + 1.0; // baseline

            double finalScore = freshnessFactor * engagementScore;
            item.setScore(finalScore);
        }

        ranked.sort(Comparator.comparing(FeedItem::getScore).reversed());
        return ranked;
    }

    @Override
    public String name() {
        return "Engagement-based";
    }
}
