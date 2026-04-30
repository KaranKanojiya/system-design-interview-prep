package com.systemdesign.videostreaming.strategy.recommendation;

import com.systemdesign.videostreaming.model.Video;

import java.util.List;

/**
 * Strategy for generating video recommendations.
 *
 * Why Strategy pattern?
 *   - Different recommendation approaches suit different contexts:
 *     - Trending: no personalization, good for new/anonymous users (cold start)
 *     - Personalized: based on watch history, good for returning users
 *   - A/B testing: swap strategies to measure engagement impact
 *   - Easy to add new strategies: collaborative filtering, content-based, hybrid
 *
 * In production: recommendations are precomputed by ML pipelines (TensorFlow/PyTorch),
 * stored in a feature store, and served by a low-latency inference service.
 * The strategy pattern here models the serving layer.
 */
public interface RecommendationStrategy {

    /**
     * Generate video recommendations for a user.
     *
     * @param userId the user to recommend for
     * @param limit  maximum number of recommendations to return
     * @return ordered list of recommended videos (most relevant first)
     */
    List<Video> recommend(String userId, int limit);
}
