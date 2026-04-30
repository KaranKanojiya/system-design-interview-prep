package com.systemdesign.videostreaming.strategy.recommendation;

import com.systemdesign.videostreaming.model.Video;
import com.systemdesign.videostreaming.model.VideoMetadata;
import com.systemdesign.videostreaming.model.VideoStatus;
import com.systemdesign.videostreaming.model.WatchHistory;
import com.systemdesign.videostreaming.repository.VideoRepository;
import com.systemdesign.videostreaming.repository.WatchHistoryRepository;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Personalized recommendation based on user's watch history.
 *
 * Algorithm:
 *   1. Analyze user's watch history → find most-watched categories
 *   2. Filter out videos the user has already watched
 *   3. From remaining, prioritize videos in preferred categories
 *   4. Boost by engagement rate (high engagement = interesting content)
 *   5. Return top N
 *
 * This is a simplified content-based filtering approach.
 *
 * In production:
 *   - Collaborative filtering: "users who watched X also watched Y"
 *   - Deep learning embeddings: video content → vector, user history → vector, cosine similarity
 *   - Multi-armed bandit: explore vs exploit (show some random videos to gather data)
 *   - The real YouTube algorithm considers 100+ signals including:
 *     watch time, click-through rate, session length, freshness, creator authority
 */
public class PersonalizedRecommendation implements RecommendationStrategy {

    private final VideoRepository videoRepository;
    private final WatchHistoryRepository watchHistoryRepository;
    private final Map<String, VideoMetadata> metadataMap;

    public PersonalizedRecommendation(VideoRepository videoRepository,
                                      WatchHistoryRepository watchHistoryRepository,
                                      Map<String, VideoMetadata> metadataMap) {
        this.videoRepository = videoRepository;
        this.watchHistoryRepository = watchHistoryRepository;
        this.metadataMap = metadataMap;
    }

    @Override
    public List<Video> recommend(String userId, int limit) {
        // Step 1: Analyze watch history → category preferences
        List<WatchHistory> history = watchHistoryRepository.findByUserId(userId);
        Map<String, Integer> categoryScores = buildCategoryProfile(history);

        // Step 2: Get videos the user has already watched (to exclude)
        Set<String> watchedVideoIds = history.stream()
                .map(WatchHistory::getVideoId)
                .collect(Collectors.toSet());

        // Step 3: Score all unwatched, ready videos
        List<Video> candidates = videoRepository.findAll().stream()
                .filter(v -> v.getStatus() == VideoStatus.READY)
                .filter(v -> !watchedVideoIds.contains(v.getVideoId()))
                .collect(Collectors.toList());

        // Step 4: Rank by (category preference score * engagement rate boost)
        candidates.sort((a, b) -> {
            double scoreA = computeRelevanceScore(a.getVideoId(), categoryScores);
            double scoreB = computeRelevanceScore(b.getVideoId(), categoryScores);
            return Double.compare(scoreB, scoreA); // Descending
        });

        // Step 5: Return top N
        return candidates.stream().limit(limit).collect(Collectors.toList());
    }

    /**
     * Build a category preference profile from watch history.
     * Count how many times the user watched videos in each category.
     * More watches in a category = stronger preference signal.
     */
    private Map<String, Integer> buildCategoryProfile(List<WatchHistory> history) {
        Map<String, Integer> categoryScores = new HashMap<>();
        for (WatchHistory entry : history) {
            VideoMetadata meta = metadataMap.get(entry.getVideoId());
            if (meta != null) {
                String category = meta.getCategory();
                categoryScores.merge(category, 1, Integer::sum);
            }
        }
        return categoryScores;
    }

    /**
     * Compute relevance score for a candidate video:
     *   score = categoryPreference + (engagementRate * 10)
     *
     * The engagement rate boost ensures high-quality content rises even
     * if the category match is weak. The *10 multiplier normalizes
     * engagement rate (0.0-1.0) to be comparable with category scores (1-10+).
     */
    private double computeRelevanceScore(String videoId, Map<String, Integer> categoryScores) {
        VideoMetadata meta = metadataMap.get(videoId);
        if (meta == null) return 0.0;

        // Category preference: how often has the user watched this category?
        int categoryScore = categoryScores.getOrDefault(meta.getCategory(), 0);

        // Engagement boost: high engagement rate = interesting content
        double engagementBoost = meta.getEngagementRate() * 10;

        return categoryScore + engagementBoost;
    }

    @Override
    public String toString() {
        return "PersonalizedRecommendation (history-based + engagement boost)";
    }
}
