package com.systemdesign.newsfeed.strategy.ranking;

import com.systemdesign.newsfeed.model.FeedItem;
import com.systemdesign.newsfeed.model.User;

import java.util.ArrayList;
import java.util.List;

/**
 * ChronologicalRankingStrategy — Sort by creation time, newest first.
 *
 * Design notes for interview:
 * - The simplest ranking: just sort by timestamp descending.
 * - Score = post.createdAt as epoch seconds (higher = newer = better).
 * - This was Twitter's original (and only) ranking until 2016.
 * - Mastodon still uses this exclusively (anti-algorithm philosophy).
 *
 * Pros:
 * - Transparent: users understand why they see what they see.
 * - No filter bubble: all posts from followed users appear.
 * - Simple to implement and debug.
 *
 * Cons:
 * - If you follow 1000 accounts, you'll miss posts from close friends
 *   that were buried by high-volume accounts.
 * - No engagement optimization (platform can't boost revenue-generating content).
 * - Power users who post frequently dominate the feed.
 */
public class ChronologicalRankingStrategy implements RankingStrategy {

    @Override
    public List<FeedItem> rank(List<FeedItem> items, User viewer) {
        // --- Simple: sort by createdAt descending ---
        // Score = epoch second value (higher = more recent = higher in feed)
        List<FeedItem> ranked = new ArrayList<>(items);

        for (FeedItem item : ranked) {
            // Convert timestamp to a numeric score for consistent scoring
            // Using epoch second ensures newer posts have higher scores
            double timestampScore = item.getPost().getCreatedAt()
                    .toLocalDate().toEpochDay() * 86400.0
                    + item.getPost().getCreatedAt().toLocalTime().toSecondOfDay();
            item.setScore(timestampScore);
        }

        // Sort using Comparable (FeedItem sorts by score DESC)
        ranked.sort(null);

        // Assign positions (1-indexed for display)
        for (int i = 0; i < ranked.size(); i++) {
            ranked.get(i).setPosition(i + 1);
        }

        return ranked;
    }

    @Override
    public String getStrategyName() {
        return "Chronological (Newest First)";
    }
}
