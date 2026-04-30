package com.systemdesign.videostreaming.strategy.recommendation;

import com.systemdesign.videostreaming.model.Video;
import com.systemdesign.videostreaming.model.VideoMetadata;
import com.systemdesign.videostreaming.model.VideoStatus;
import com.systemdesign.videostreaming.repository.VideoRepository;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Trending recommendation: return videos sorted by view count (most popular first).
 *
 * No personalization — same results for every user.
 * This is the baseline recommendation strategy.
 *
 * When is this useful?
 *   - Cold start: new user with no watch history
 *   - Anonymous browsing: user not logged in
 *   - Homepage "Trending" section: intentionally non-personalized
 *
 * Limitations:
 *   - Popular videos get more popular (rich-get-richer / Matthew effect)
 *   - No discovery of niche content
 *   - Same recommendations for everyone = no engagement optimization
 *
 * In production: "trending" would be time-decayed (views in last 24h, not all-time)
 * to prevent stale content from dominating.
 */
public class TrendingRecommendation implements RecommendationStrategy {

    private final VideoRepository videoRepository;
    private final Map<String, VideoMetadata> metadataMap;

    public TrendingRecommendation(VideoRepository videoRepository, Map<String, VideoMetadata> metadataMap) {
        this.videoRepository = videoRepository;
        this.metadataMap = metadataMap;
    }

    @Override
    public List<Video> recommend(String userId, int limit) {
        // Get all ready videos, sort by view count descending
        return videoRepository.findAll().stream()
                .filter(v -> v.getStatus() == VideoStatus.READY)
                .sorted((a, b) -> {
                    long viewsA = getViewCount(a.getVideoId());
                    long viewsB = getViewCount(b.getVideoId());
                    return Long.compare(viewsB, viewsA); // Descending
                })
                .limit(limit)
                .collect(Collectors.toList());
    }

    private long getViewCount(String videoId) {
        VideoMetadata meta = metadataMap.get(videoId);
        return meta != null ? meta.getViewCount() : 0;
    }

    @Override
    public String toString() {
        return "TrendingRecommendation (sort by view count)";
    }
}
