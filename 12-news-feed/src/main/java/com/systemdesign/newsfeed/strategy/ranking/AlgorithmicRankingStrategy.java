package com.systemdesign.newsfeed.strategy.ranking;

import com.systemdesign.newsfeed.model.FeedItem;
import com.systemdesign.newsfeed.model.User;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AlgorithmicRankingStrategy — Relevance-based ranking used by Facebook/Instagram/LinkedIn.
 *
 * Design notes for interview:
 *
 * SCORING FORMULA:
 *   score = affinityScore * recencyDecay * engagementBoost * contentTypeWeight
 *
 * Each factor explained:
 *
 * 1. AFFINITY SCORE (how close is viewer to author?)
 *    - Based on interaction history: has the viewer liked/commented on this author's posts?
 *    - Range: [1.0, 2.0]
 *    - Default: 1.0 (no interaction history)
 *    - Boost: +0.1 per interaction, capped at 2.0
 *    - Why: surfaces posts from people you ACTUALLY interact with, not just follow.
 *    - Facebook calls this "affinity" — it's their #1 ranking signal.
 *
 * 2. RECENCY DECAY (how old is the post?)
 *    - Formula: Math.exp(-0.05 * hoursAge)
 *    - This is exponential decay: a 1-hour-old post scores 0.95, a 24-hour-old post scores 0.30.
 *    - Why exponential? Because relevance drops off FAST. A 1-day-old post is
 *      much less interesting than a 1-hour-old post. But a 7-day-old and 8-day-old
 *      are about equally irrelevant — exponential decay models this correctly.
 *    - The decay constant (0.05) is tunable. Higher = faster decay = more recency bias.
 *
 * 3. ENGAGEMENT BOOST (is this post popular?)
 *    - Formula: 1.0 + Math.log(1 + likes + comments*2 + shares*3) / 10.0
 *    - Uses LOG to prevent viral posts from completely dominating the feed.
 *      A post with 1M likes shouldn't score 1M times higher — that would make
 *      the feed just a "most popular" list. Log compression ensures diminishing returns.
 *    - Comments weighted 2x (higher intent), shares weighted 3x (strongest signal).
 *    - Range: [1.0, ~2.5] for typical posts. Even a viral post caps around 2.5.
 *
 * 4. CONTENT TYPE WEIGHT (video > image > text)
 *    - From ContentType enum: VIDEO=1.5, POLL=1.4, IMAGE=1.3, LINK=1.1, TEXT=1.0
 *    - Why: platforms optimize for engagement/watch-time. Video content drives
 *      the highest engagement, so it gets boosted.
 *    - In production, these weights are learned from ML models, not hardcoded.
 *
 * EXAMPLE CALCULATION:
 *   Post: 2 hours old, 50 likes, 10 comments, 5 shares, VIDEO, viewer has 5 interactions with author
 *   affinityScore  = min(1.0 + 5 * 0.1, 2.0) = 1.5
 *   recencyDecay   = exp(-0.05 * 2) = 0.905
 *   engagementBoost = 1.0 + log(1 + 50 + 20 + 15) / 10.0 = 1.0 + 4.45/10 = 1.445
 *   contentTypeWeight = 1.5 (VIDEO)
 *   FINAL SCORE = 1.5 * 0.905 * 1.445 * 1.5 = 2.94
 */
public class AlgorithmicRankingStrategy implements RankingStrategy {

    // --- Affinity tracking ---
    // Map<viewerId, Map<authorId, interactionCount>>
    // In production, this would be stored in a graph database or feature store.
    // Here we use a simple in-memory map for demonstration.
    private final Map<String, Map<String, Integer>> interactionHistory;

    // --- Tuning constants ---
    private static final double RECENCY_DECAY_CONSTANT = 0.05;  // Higher = faster decay
    private static final double MAX_AFFINITY = 2.0;
    private static final double AFFINITY_PER_INTERACTION = 0.1;
    private static final double ENGAGEMENT_LOG_DIVISOR = 10.0;

    public AlgorithmicRankingStrategy() {
        this.interactionHistory = new ConcurrentHashMap<>();
    }

    @Override
    public List<FeedItem> rank(List<FeedItem> items, User viewer) {
        List<FeedItem> ranked = new ArrayList<>(items);

        for (FeedItem item : ranked) {
            double score = computeScore(item, viewer);
            item.setScore(score);
        }

        // Sort using Comparable (FeedItem sorts by score DESC)
        ranked.sort(null);

        // Assign positions
        for (int i = 0; i < ranked.size(); i++) {
            ranked.get(i).setPosition(i + 1);
        }

        return ranked;
    }

    /**
     * Compute the composite ranking score for a feed item.
     *
     * score = affinityScore * recencyDecay * engagementBoost * contentTypeWeight
     */
    private double computeScore(FeedItem item, User viewer) {
        // --- 1. Affinity Score ---
        // How often does the viewer interact with this post's author?
        double affinityScore = computeAffinityScore(viewer.getUserId(), item.getPost().getAuthorId());

        // --- 2. Recency Decay ---
        // Exponential decay based on post age in hours.
        double recencyDecay = computeRecencyDecay(item.getPost().getCreatedAt());

        // --- 3. Engagement Boost ---
        // Log-compressed engagement score.
        double engagementBoost = computeEngagementBoost(item.getPost());

        // --- 4. Content Type Weight ---
        // From the ContentType enum (VIDEO=1.5, IMAGE=1.3, etc.)
        double contentTypeWeight = item.getPost().getContentType().getWeight();

        // --- FINAL SCORE = product of all factors ---
        return affinityScore * recencyDecay * engagementBoost * contentTypeWeight;
    }

    /**
     * Affinity: [1.0, 2.0] based on viewer's interaction history with author.
     */
    private double computeAffinityScore(String viewerId, String authorId) {
        Map<String, Integer> viewerHistory = interactionHistory.get(viewerId);
        if (viewerHistory == null) {
            return 1.0; // No history -> default affinity
        }
        int interactions = viewerHistory.getOrDefault(authorId, 0);
        // Each interaction adds 0.1, capped at 2.0
        return Math.min(1.0 + interactions * AFFINITY_PER_INTERACTION, MAX_AFFINITY);
    }

    /**
     * Recency: exponential decay. exp(-0.05 * hoursAge)
     * - 0 hours old: 1.0 (brand new)
     * - 1 hour old:  0.95
     * - 6 hours old:  0.74
     * - 12 hours old: 0.55
     * - 24 hours old: 0.30
     * - 48 hours old: 0.09
     * - 72 hours old: 0.03  (effectively invisible)
     */
    private double computeRecencyDecay(LocalDateTime createdAt) {
        long hoursAge = Duration.between(createdAt, LocalDateTime.now()).toHours();
        if (hoursAge < 0) {
            hoursAge = 0; // Future posts (edge case in testing)
        }
        return Math.exp(-RECENCY_DECAY_CONSTANT * hoursAge);
    }

    /**
     * Engagement boost: 1.0 + log(1 + weightedEngagement) / 10.0
     * Log compression prevents viral posts from completely dominating.
     */
    private double computeEngagementBoost(com.systemdesign.newsfeed.model.Post post) {
        double weightedEngagement = post.getLikeCount()
                + post.getCommentCount() * 2.0
                + post.getShareCount() * 3.0;
        return 1.0 + Math.log(1 + weightedEngagement) / ENGAGEMENT_LOG_DIVISOR;
    }

    // --- Affinity tracking methods ---
    // Called by EngagementService when a user likes/comments/shares.

    /**
     * Record an interaction from viewer to author (like, comment, share).
     * This increases the viewer's affinity score for the author.
     */
    public void recordInteraction(String viewerId, String authorId) {
        interactionHistory
                .computeIfAbsent(viewerId, k -> new ConcurrentHashMap<>())
                .merge(authorId, 1, Integer::sum);
    }

    /**
     * Get the current affinity score between viewer and author.
     */
    public double getAffinityScore(String viewerId, String authorId) {
        return computeAffinityScore(viewerId, authorId);
    }

    @Override
    public String getStrategyName() {
        return "Algorithmic (Affinity * Recency * Engagement * ContentType)";
    }
}
