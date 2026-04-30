package com.systemdesign.videostreaming.service;

import com.systemdesign.videostreaming.model.Video;
import com.systemdesign.videostreaming.strategy.recommendation.RecommendationStrategy;

import java.util.List;

/**
 * Thin service wrapper around the recommendation strategy.
 *
 * Why a separate service (not just using the strategy directly)?
 *   - Service can add cross-cutting concerns: logging, caching, A/B test routing
 *   - Service can compose multiple strategies (e.g., 70% personalized + 30% trending)
 *   - Service provides a stable API even as strategies change
 *
 * In production: the recommendation service would:
 *   1. Check A/B test assignment → pick strategy
 *   2. Call the ML model serving layer
 *   3. Filter out blocked/flagged content
 *   4. Apply business rules (e.g., boost promoted content)
 *   5. Log the recommendation for offline evaluation
 */
public class RecommendationService {

    private RecommendationStrategy strategy;

    public RecommendationService(RecommendationStrategy strategy) {
        this.strategy = strategy;
    }

    /**
     * Get recommendations for a user using the current strategy.
     */
    public List<Video> recommend(String userId, int limit) {
        return strategy.recommend(userId, limit);
    }

    /**
     * Swap the recommendation strategy at runtime (for A/B testing or comparison demos).
     */
    public void setStrategy(RecommendationStrategy strategy) {
        this.strategy = strategy;
    }

    public RecommendationStrategy getStrategy() {
        return strategy;
    }
}
