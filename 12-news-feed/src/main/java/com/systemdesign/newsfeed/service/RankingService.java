package com.systemdesign.newsfeed.service;

import com.systemdesign.newsfeed.model.FeedItem;
import com.systemdesign.newsfeed.model.User;
import com.systemdesign.newsfeed.strategy.ranking.RankingStrategy;

import java.util.List;

/**
 * RankingService — Holds the current ranking strategy; can switch at runtime.
 *
 * Design notes for interview:
 * - This service wraps the RankingStrategy interface so that the strategy
 *   can be swapped at runtime without affecting callers (FeedService).
 * - Use cases for runtime switching:
 *   1. A/B testing: 50% of users get algorithmic, 50% get chronological.
 *   2. User preference: user toggles "Show me latest first" in settings.
 *   3. Gradual rollout: switch 1% -> 5% -> 25% -> 100% to new algorithm.
 *
 * Call chain:
 *   FeedService.getFeed()
 *     -> RankingService.rank(items, viewer)
 *       -> RankingStrategy.rank(items, viewer)  [algorithmic or chronological]
 */
public class RankingService {

    private RankingStrategy strategy;

    public RankingService(RankingStrategy strategy) {
        this.strategy = strategy;
    }

    /**
     * Rank feed items using the current strategy.
     */
    public List<FeedItem> rank(List<FeedItem> items, User viewer) {
        return strategy.rank(items, viewer);
    }

    /**
     * Switch ranking strategy at runtime.
     * Thread-safe because strategy is only read during rank() calls;
     * switching between calls is safe.
     */
    public void setStrategy(RankingStrategy strategy) {
        System.out.printf("   [RankingService] Switching strategy: %s -> %s%n",
                this.strategy.getStrategyName(), strategy.getStrategyName());
        this.strategy = strategy;
    }

    public RankingStrategy getStrategy() {
        return strategy;
    }

    public String getStrategyName() {
        return strategy.getStrategyName();
    }
}
