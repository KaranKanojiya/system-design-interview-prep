package com.systemdesign.newsfeed.strategy.ranking;

import com.systemdesign.newsfeed.model.FeedItem;
import com.systemdesign.newsfeed.model.User;

import java.util.List;

/**
 * RankingStrategy — Strategy interface for ordering feed items.
 *
 * Design notes for interview:
 * - Two main approaches in the industry:
 *   1. Chronological: newest first (Twitter classic, Mastodon).
 *      Pro: simple, transparent, no "algorithm" complaints.
 *      Con: misses relevant posts if user follows many accounts.
 *   2. Algorithmic: relevance-based ordering (Facebook, Instagram, LinkedIn).
 *      Pro: surfaces the most engaging/relevant content.
 *      Con: opaque, can create filter bubbles, "why am I seeing this?"
 *
 * - Modern platforms offer BOTH: algorithmic as default, chronological as opt-in.
 *   Twitter/X: "For You" (algorithmic) vs "Following" (chronological).
 *   Instagram: default feed (algorithmic) vs "Following" tab (chronological).
 *
 * - RankingService holds the current strategy and can switch at runtime,
 *   allowing A/B testing of different ranking algorithms.
 *
 * Call chain: FeedService.getFeed() -> RankingService.rank() -> RankingStrategy.rank()
 */
public interface RankingStrategy {

    /**
     * Rank/sort the given feed items for the specified viewer.
     *
     * @param items  unranked feed items (posts from timeline + pulled celebrity posts)
     * @param viewer the user viewing the feed (used for personalization)
     * @return ranked list of feed items (best/most relevant first)
     */
    List<FeedItem> rank(List<FeedItem> items, User viewer);

    /**
     * Human-readable name for logging/display.
     */
    String getStrategyName();
}
