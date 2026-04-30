package com.systemdesign.videostreaming.controller;

import com.systemdesign.videostreaming.exception.VideoStreamingException;
import com.systemdesign.videostreaming.model.Resolution;
import com.systemdesign.videostreaming.model.Video;
import com.systemdesign.videostreaming.service.AnalyticsService;
import com.systemdesign.videostreaming.service.RecommendationService;
import com.systemdesign.videostreaming.service.SearchService;
import com.systemdesign.videostreaming.service.VideoService;

import java.util.List;
import java.util.Map;

/**
 * Simulated REST controller — translates "HTTP requests" into service calls.
 *
 * In production: Spring @RestController with @GetMapping / @PostMapping.
 * Each method here maps to an endpoint:
 *   POST /api/videos           → handleUpload
 *   GET  /api/videos/{id}/stream → handleStream
 *   GET  /api/videos/search     → handleSearch
 *   GET  /api/recommendations   → handleRecommendations
 *   GET  /api/stats             → handleGetStats
 *
 * Error handling: catch VideoStreamingException → return appropriate HTTP status.
 */
public class VideoController {

    private final VideoService videoService;
    private final RecommendationService recommendationService;

    public VideoController(VideoService videoService, RecommendationService recommendationService) {
        this.videoService = videoService;
        this.recommendationService = recommendationService;
    }

    /**
     * POST /api/videos
     * Simulates receiving a video upload request.
     */
    public String handleUpload(String userId, String title, String description,
                               long fileSizeBytes, Resolution resolution) {
        try {
            Video video = videoService.uploadVideo(userId, title, description, fileSizeBytes, resolution);
            return "201 CREATED: Video uploaded successfully. ID=" + video.getVideoId()
                    + ", Status=" + video.getStatus();
        } catch (VideoStreamingException e) {
            return "500 ERROR: " + e.getMessage();
        }
    }

    /**
     * GET /api/videos/{id}/stream
     * Simulates a streaming session request.
     */
    public String handleStream(String videoId, String userId, long bandwidthKbps) {
        try {
            List<String> log = videoService.streamVideo(videoId, userId, bandwidthKbps);
            StringBuilder sb = new StringBuilder("200 OK: Streaming session started\n");
            for (String entry : log) {
                sb.append(entry).append("\n");
            }
            return sb.toString();
        } catch (VideoStreamingException e) {
            return "404 NOT FOUND: " + e.getMessage();
        }
    }

    /**
     * GET /api/videos/search?q={query}
     * Simulates a search request.
     */
    public String handleSearch(String query) {
        List<Video> results = videoService.searchVideos(query);
        StringBuilder sb = new StringBuilder("200 OK: Found " + results.size() + " results for '" + query + "'\n");
        for (Video video : results) {
            sb.append("  - ").append(video.getTitle())
                    .append(" (").append(video.getVideoId()).append(")\n");
        }
        return sb.toString();
    }

    /**
     * GET /api/recommendations?userId={id}&limit={n}
     * Returns personalized or trending recommendations.
     */
    public String handleRecommendations(String userId, int limit) {
        List<Video> recommendations = recommendationService.recommend(userId, limit);
        StringBuilder sb = new StringBuilder("200 OK: " + recommendations.size()
                + " recommendations for user " + userId + "\n");
        for (int i = 0; i < recommendations.size(); i++) {
            Video v = recommendations.get(i);
            sb.append("  ").append(i + 1).append(". ").append(v.getTitle())
                    .append(" (").append(v.getVideoId()).append(")\n");
        }
        return sb.toString();
    }

    /**
     * GET /api/stats
     * Returns platform-level statistics.
     */
    public String handleGetStats() {
        AnalyticsService analytics = videoService.getAnalyticsService();
        StringBuilder sb = new StringBuilder("200 OK: Platform Stats\n");
        sb.append("  Total views: ").append(analytics.getTotalViews()).append("\n");
        sb.append("  Total watch time: ").append(analytics.getTotalWatchTimeMinutes()).append(" minutes\n");
        sb.append("  Avg session: ").append(String.format("%.1f", analytics.getAverageWatchTimeSeconds())).append(" seconds\n");

        List<Map.Entry<String, Long>> mostWatched = analytics.getMostWatched(5);
        sb.append("  Most watched:\n");
        for (Map.Entry<String, Long> entry : mostWatched) {
            sb.append("    ").append(entry.getKey()).append(": ").append(entry.getValue()).append(" views\n");
        }
        return sb.toString();
    }

    public VideoService getVideoService() { return videoService; }
}
