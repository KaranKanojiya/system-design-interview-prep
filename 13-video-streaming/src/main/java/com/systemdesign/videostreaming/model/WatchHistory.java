package com.systemdesign.videostreaming.model;

import java.time.LocalDateTime;

/**
 * Records a single watch session: one user watching one video.
 *
 * Why track watch duration separately from video duration?
 *   - Completion rate (watchDuration / totalDuration) is a key engagement metric
 *   - A 10% completion rate may indicate clickbait or poor content quality
 *   - Netflix uses completion rate to decide whether to renew a show
 *   - YouTube uses it for ranking in the recommendation algorithm
 *
 * In production: watch events are streamed to Kafka → aggregated in Flink/Spark
 * → stored in a time-series DB (ClickHouse) for analytics dashboards.
 */
public class WatchHistory {

    private final String userId;
    private final String videoId;
    private final String videoTitle;
    private final LocalDateTime watchedAt;
    private final int watchDurationSeconds;
    private final int totalDurationSeconds;

    public WatchHistory(String userId, String videoId, String videoTitle,
                        LocalDateTime watchedAt, int watchDurationSeconds, int totalDurationSeconds) {
        this.userId = userId;
        this.videoId = videoId;
        this.videoTitle = videoTitle;
        this.watchedAt = watchedAt;
        this.watchDurationSeconds = watchDurationSeconds;
        this.totalDurationSeconds = totalDurationSeconds;
    }

    /**
     * Completion percentage: how much of the video the user actually watched.
     * Returns 0-100. Values > 100 indicate rewatching (clamped to 100).
     */
    public double getCompletionPercent() {
        if (totalDurationSeconds == 0) return 0.0;
        return Math.min(100.0, (watchDurationSeconds * 100.0) / totalDurationSeconds);
    }

    public String getUserId() { return userId; }
    public String getVideoId() { return videoId; }
    public String getVideoTitle() { return videoTitle; }
    public LocalDateTime getWatchedAt() { return watchedAt; }
    public int getWatchDurationSeconds() { return watchDurationSeconds; }
    public int getTotalDurationSeconds() { return totalDurationSeconds; }

    @Override
    public String toString() {
        return "WatchHistory{user='" + userId + "', video='" + videoTitle
                + "', watched=" + watchDurationSeconds + "s/" + totalDurationSeconds + "s"
                + " (" + String.format("%.1f", getCompletionPercent()) + "%)}";
    }
}
