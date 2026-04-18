package com.systemdesign.feed.model;

import java.time.LocalDateTime;

public class TrendingTopic implements Comparable<TrendingTopic> {

    private final String hashtag;
    private final long count;
    private final double score;
    private final LocalDateTime updatedAt;

    public TrendingTopic(String hashtag, long count, double score) {
        this.hashtag = hashtag;
        this.count = count;
        this.score = score;
        this.updatedAt = LocalDateTime.now();
    }

    public String getHashtag() { return hashtag; }
    public long getCount() { return count; }
    public double getScore() { return score; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    @Override
    public int compareTo(TrendingTopic other) {
        // Higher score first (descending)
        return Double.compare(other.score, this.score);
    }

    @Override
    public String toString() {
        return String.format("#%s (count: %d, score: %.2f)", hashtag, count, score);
    }
}
