package com.systemdesign.videostreaming.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metadata associated with a video: tags, category, engagement counters.
 *
 * Why AtomicLong for counters?
 *   - View/like counts are incremented by many threads concurrently
 *   - AtomicLong uses CAS (compare-and-swap) — lock-free, high throughput
 *   - In production: these counters live in Redis (INCR command is atomic)
 *     and are periodically flushed to the database
 *
 * Engagement rate formula:
 *   (likes + dislikes) / views — measures how many viewers feel strongly enough to react.
 *   A high engagement rate (even with dislikes) signals interesting content.
 */
public class VideoMetadata {

    private final String videoId;
    private final List<String> tags;
    private final String category;
    private final String language;
    private final AtomicLong viewCount;
    private final AtomicLong likeCount;
    private final AtomicLong dislikeCount;

    public VideoMetadata(String videoId, List<String> tags, String category, String language) {
        this.videoId = videoId;
        this.tags = new ArrayList<>(tags);
        this.category = category;
        this.language = language;
        this.viewCount = new AtomicLong(0);
        this.likeCount = new AtomicLong(0);
        this.dislikeCount = new AtomicLong(0);
    }

    /** Thread-safe increment — maps to Redis INCR in production. */
    public long incrementViews() { return viewCount.incrementAndGet(); }

    /** Thread-safe increment for likes. */
    public long incrementLikes() { return likeCount.incrementAndGet(); }

    /** Thread-safe increment for dislikes. */
    public long incrementDislikes() { return dislikeCount.incrementAndGet(); }

    /**
     * Engagement rate = (likes + dislikes) / views.
     * Returns 0.0 if no views yet (avoid division by zero).
     */
    public double getEngagementRate() {
        long views = viewCount.get();
        if (views == 0) return 0.0;
        return (double) (likeCount.get() + dislikeCount.get()) / views;
    }

    public String getVideoId() { return videoId; }
    public List<String> getTags() { return Collections.unmodifiableList(tags); }
    public String getCategory() { return category; }
    public String getLanguage() { return language; }
    public long getViewCount() { return viewCount.get(); }
    public long getLikeCount() { return likeCount.get(); }
    public long getDislikeCount() { return dislikeCount.get(); }

    /** Directly set the view count — used for seeding demo data. */
    public void setViewCount(long count) { viewCount.set(count); }

    /** Directly set the like count — used for seeding demo data. */
    public void setLikeCount(long count) { likeCount.set(count); }

    @Override
    public String toString() {
        return "VideoMetadata{videoId='" + videoId + "', category='" + category
                + "', views=" + viewCount.get() + ", likes=" + likeCount.get()
                + ", engagement=" + String.format("%.2f", getEngagementRate()) + "}";
    }
}
