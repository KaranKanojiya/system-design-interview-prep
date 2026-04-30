package com.systemdesign.videostreaming.service;

import com.systemdesign.videostreaming.model.Video;
import com.systemdesign.videostreaming.model.VideoMetadata;
import com.systemdesign.videostreaming.model.WatchHistory;
import com.systemdesign.videostreaming.repository.VideoRepository;
import com.systemdesign.videostreaming.repository.WatchHistoryRepository;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Analytics service: records views, tracks watch history, provides aggregated metrics.
 *
 * Data flow in production:
 *   1. Client sends "heartbeat" events every 10s during playback (via WebSocket or HTTP)
 *   2. Events land in Kafka (high-throughput append-only log)
 *   3. Flink/Spark Streaming aggregates in near-real-time:
 *      - Increment view count (Redis INCR for immediate visibility)
 *      - Update watch duration (rolling aggregation)
 *   4. Batch pipeline (nightly): compute aggregated metrics, ML features
 *   5. Results stored in ClickHouse (analytics DB) for dashboard queries
 *
 * This simplified version does everything synchronously and in-memory.
 */
public class AnalyticsService {

    private final WatchHistoryRepository watchHistoryRepository;
    private final VideoRepository videoRepository;
    private final Map<String, VideoMetadata> metadataMap;

    public AnalyticsService(WatchHistoryRepository watchHistoryRepository,
                            VideoRepository videoRepository,
                            Map<String, VideoMetadata> metadataMap) {
        this.watchHistoryRepository = watchHistoryRepository;
        this.videoRepository = videoRepository;
        this.metadataMap = metadataMap;
    }

    /**
     * Record a view: increment counter + store watch history.
     * In production: this is an async event (fire-and-forget to Kafka).
     */
    public void recordView(String videoId, String userId, int watchDurationSeconds) {
        // Increment view count (atomic, thread-safe)
        VideoMetadata meta = metadataMap.get(videoId);
        if (meta != null) {
            meta.incrementViews();
        }

        // Store watch history entry
        Optional<Video> videoOpt = videoRepository.findById(videoId);
        String videoTitle = videoOpt.map(Video::getTitle).orElse("Unknown");
        int totalDuration = videoOpt.map(Video::getDurationSeconds).orElse(0);

        WatchHistory entry = new WatchHistory(
                userId, videoId, videoTitle,
                LocalDateTime.now(), watchDurationSeconds, totalDuration
        );
        watchHistoryRepository.save(entry);
    }

    /**
     * Get total view count for a video.
     */
    public long getViewCount(String videoId) {
        VideoMetadata meta = metadataMap.get(videoId);
        return meta != null ? meta.getViewCount() : 0;
    }

    /**
     * Get the most-watched videos (by view count), descending.
     */
    public List<Map.Entry<String, Long>> getMostWatched(int limit) {
        return metadataMap.entrySet().stream()
                .map(e -> Map.entry(e.getKey(), e.getValue().getViewCount()))
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Get watch history for a specific user.
     */
    public List<WatchHistory> getWatchHistory(String userId) {
        return watchHistoryRepository.findByUserId(userId);
    }

    /**
     * Get total watch time across all users (in minutes).
     */
    public long getTotalWatchTimeMinutes() {
        return watchHistoryRepository.findAll().stream()
                .mapToLong(WatchHistory::getWatchDurationSeconds)
                .sum() / 60;
    }

    /**
     * Get average watch time per session (in seconds).
     */
    public double getAverageWatchTimeSeconds() {
        List<WatchHistory> all = watchHistoryRepository.findAll();
        if (all.isEmpty()) return 0.0;
        return all.stream()
                .mapToInt(WatchHistory::getWatchDurationSeconds)
                .average()
                .orElse(0.0);
    }

    /**
     * Get total views across all videos.
     */
    public long getTotalViews() {
        return metadataMap.values().stream()
                .mapToLong(VideoMetadata::getViewCount)
                .sum();
    }
}
